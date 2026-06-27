package com.unkwn2.yandexhud.notif

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.unkwn2.yandexhud.bridge.HudForegroundService
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.util.Logger
import com.unkwn2.yandexhud.util.ResCache

class YandexNaviNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "YandexNotif"
        private const val REMOVE_DEBOUNCE_MS = 3000L

        // RemoteViews probe: toggle через HudForegroundService.probeRv (кнопка RV DUMP)
        private val PROBE_RV get() = HudForegroundService.probeRv
        val YANDEX_PKGS = setOf(
            "ru.yandex.yandexnavi", "ru.yandex.yandexnavi.inhouse", "ru.yandex.yandexnavi.rustore",
            "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
        )
        private val YANDEX_MAPS_PKGS = setOf(
            "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
        )
        private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\S)""", RegexOption.IGNORE_CASE)
        private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\S)""", RegexOption.IGNORE_CASE)
        @Volatile private var removePostedMs = 0L
    }

    override fun onListenerConnected() {
        Logger.i(TAG, "listener connected")
    }

    override fun onListenerDisconnected() {
        Logger.w(TAG, "listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in YANDEX_PKGS) return

        val n = sbn.notification
        val ext = n.extras ?: return

        // RemoteViews — приоритетный источник (структурированные данные)
        val rv = try {
            RemoteViewsParser.parse(applicationContext, n, sbn.packageName, PROBE_RV)
        } catch (t: Throwable) {
            Logger.w(TAG, "rv parse error: ${t.message}")
            null
        }

        if (rv != null) {
            val maneuver = rv.maneuverEnum ?: ManeuverMapper.fromRussianText(rv.instruction)
            val etaSeconds = if (rv.remainingTimeSec > 0) rv.remainingTimeSec
                else parseEtaSeconds(ext.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "")
            Logger.i(TAG, "posted rv m=$maneuver(${ManeuverMapper.maneuverName(maneuver)}) d=${rv.distToManeuverM}m road='${rv.road}' eta=${etaSeconds}s tl=${rv.trafficLightColor}${if (rv.trafficLightSeconds > 0) " ${rv.trafficLightSeconds}s" else ""} cam='${rv.cameraAlert}' png=${if (rv.maneuverPng != null) "${rv.maneuverPng.size}B" else "none"}")
            removePostedMs = 0L
            HudState.update { prev ->
                val roadChanged = rv.road.isNotEmpty() && prev.road != rv.road
                val maneuverChanged = maneuver != ManeuverMapper.M_UNKNOWN && prev.maneuver != maneuver
                val gaodeStale = roadChanged || maneuverChanged
                prev.copy(
                    active = true,
                    maneuver = if (maneuver != ManeuverMapper.M_UNKNOWN) maneuver else prev.maneuver,
                    distanceMeters = if (rv.distToManeuverM > 0) rv.distToManeuverM else prev.distanceMeters,
                    road = if (rv.road.isNotEmpty()) rv.road else prev.road,
                    etaSeconds = if (etaSeconds > 0) etaSeconds else prev.etaSeconds,
                    totalDistMeters = if (rv.totalDistM > 0) rv.totalDistM else prev.totalDistMeters,
                    totalTimeSeconds = prev.totalTimeSeconds,
                    iconPng = rv.maneuverPng ?: prev.iconPng,
                    trafficLightColor = if (rv.trafficLightColor.isNotEmpty()) rv.trafficLightColor else prev.trafficLightColor,
                    trafficLightSeconds = if (rv.trafficLightSeconds > 0) rv.trafficLightSeconds else prev.trafficLightSeconds,
                    cameraAlert = if (rv.cameraAlert.isNotEmpty()) rv.cameraAlert else prev.cameraAlert,
                    cameraDistanceMeters = if (rv.cameraAlert.isEmpty()) 0
                        else rv.cameraDistanceM.takeIf { it > 0 } ?: prev.cameraDistanceMeters,
                    cameraIconPng = if (rv.cameraAlert.isEmpty()) null
                        else rv.cameraIconPng ?: prev.cameraIconPng,
                    maneuverGaode = prev.maneuverGaode,
                    maneuverGaodeMs = if (gaodeStale) 0L else prev.maneuverGaodeMs,
                    lastUpdateMs = System.currentTimeMillis()
                )
            }
            return
        }

        // Fallback: старые эвристики (extras)
        val title = ext.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ext.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = ext.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val isMaps = sbn.packageName in YANDEX_MAPS_PKGS

        val maneuverFromText = ManeuverMapper.fromRussianText("$title $text")
        val smallIconName = resolveIconName(sbn.packageName, n.smallIcon?.resId ?: 0)
        val maneuver = if (maneuverFromText == ManeuverMapper.M_UNKNOWN) ManeuverMapper.fromIconName(smallIconName) else maneuverFromText
        val distanceMeters = parseDistance(text) ?: parseDistance(title) ?: 0
        val road = extractRoad(title, text)
        val etaSeconds = parseEtaSeconds(subText)

        Logger.i(TAG, "posted fallback m=$maneuver(${ManeuverMapper.maneuverName(maneuver)}) txt=${maneuverFromText} icon=$smallIconName d=${distanceMeters}m road='$road' eta=${etaSeconds}s maps=$isMaps")

        if (smallIconName == "notifications_app_logo" ||
            (distanceMeters == 0 && road.contains("навигатор", ignoreCase = true))) {
            Logger.i(TAG, "skipping idle notification: $road")
            return
        }

        removePostedMs = 0L

        val notifManeuverKnown = maneuver != ManeuverMapper.M_UNKNOWN

        HudState.update { prev ->
            val a11yHasManeuver = prev.active && prev.maneuver != ManeuverMapper.M_UNKNOWN
            val mergeEta = if (etaSeconds > 0) etaSeconds else prev.etaSeconds
            val mergeManeuver = when {
                notifManeuverKnown && !(isMaps && a11yHasManeuver) -> maneuver
                isMaps && a11yHasManeuver -> prev.maneuver
                notifManeuverKnown -> maneuver
                else -> prev.maneuver
            }
            val mergeDist = if (distanceMeters > 0) distanceMeters else prev.distanceMeters
            val mergeRoad = if (road.isNotEmpty() && road != "Навигатор запущен") road else prev.road
            val roadChanged = mergeRoad.isNotEmpty() && prev.road != mergeRoad
            val maneuverChanged = notifManeuverKnown && prev.maneuver != maneuver
            val gaodeStale = roadChanged || maneuverChanged
            prev.copy(
                active = true,
                maneuver = mergeManeuver,
                distanceMeters = mergeDist,
                road = mergeRoad,
                etaSeconds = mergeEta,
                totalTimeSeconds = prev.totalTimeSeconds,
                maneuverGaode = prev.maneuverGaode,
                maneuverGaodeMs = if (gaodeStale) 0L else prev.maneuverGaodeMs,
                lastUpdateMs = System.currentTimeMillis()
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in YANDEX_PKGS) return
        val now = System.currentTimeMillis()
        if (now - removePostedMs < REMOVE_DEBOUNCE_MS) return
        removePostedMs = now
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (removePostedMs != 0L && System.currentTimeMillis() - removePostedMs >= REMOVE_DEBOUNCE_MS - 100) {
                Logger.i(TAG, "removed after debounce — deactivating HUD")
                HudState.deactivate()
                removePostedMs = 0L
            }
        }, REMOVE_DEBOUNCE_MS)
        Logger.i(TAG, "removed — debounce ${REMOVE_DEBOUNCE_MS}ms")
    }

    private fun resolveIconName(pkg: String, resId: Int): String {
        if (resId == 0) return ""
        return try {
            ResCache.get(this, pkg)?.getResourceEntryName(resId) ?: ""
        } catch (_: Throwable) { "" }
    }

    private fun extractRoad(title: String, text: String): String {
        val hasDist = DIST_KM.containsMatchIn(title) || DIST_M.containsMatchIn(title)
        return if (hasDist) text.trim() else title.trim()
    }

    private fun parseDistance(s: String): Int? {
        val km = DIST_KM.find(s)
        if (km != null) {
            val v = km.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            return (v * 1000).toInt()
        }
        val m = DIST_M.find(s)
        if (m != null) return m.groupValues[1].toIntOrNull()
        return null
    }

    private fun parseEtaSeconds(s: String): Int {
        if (s.isBlank()) return 0
        val h = Regex("""(\d+)\s*ч""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val m = Regex("""(\d+)\s*мин""").find(s)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return h * 3600 + m * 60
    }
}
