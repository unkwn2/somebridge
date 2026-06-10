package com.unkwn2.yandexhud.notif

import android.app.Notification
import android.graphics.Bitmap
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.util.Logger

class YandexNaviNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "YandexNotif"
        private const val REMOVE_DEBOUNCE_MS = 3000L
        val YANDEX_PKGS = setOf(
            "ru.yandex.yandexnavi", "ru.yandex.yandexnavi.inhouse", "ru.yandex.yandexnavi.rustore",
            "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
        )
        private val YANDEX_MAPS_PKGS = setOf(
            "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
        )
        private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)\b""", RegexOption.IGNORE_CASE)
        private val DIST_M = Regex("""(\d+)\s*(м|m)\b""", RegexOption.IGNORE_CASE)
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

        val title = ext.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = ext.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val subText = ext.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: ""
        val isMaps = sbn.packageName in YANDEX_MAPS_PKGS

        val largeIcon = ext.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON)
        val iconPngBytes = if (largeIcon != null) {
            try {
                val s = java.io.ByteArrayOutputStream()
                largeIcon.compress(Bitmap.CompressFormat.PNG, 100, s)
                s.toByteArray().also { Logger.i(TAG, "icon PNG ${it.size}B") }
            } catch (_: Throwable) { null }
        } else null
        val maneuverFromIcon = if (largeIcon != null) detectManeuverFromBitmap(largeIcon) else null
        val smallIconName = resolveIconName(sbn.packageName, n.smallIcon?.resId ?: 0)
        val maneuverFromSmall = ManeuverMapper.fromIconName(smallIconName)
        val maneuverFromText = ManeuverMapper.fromRussianText("$title $text")

        val maneuver = maneuverFromIcon
            ?: if (maneuverFromSmall != ManeuverMapper.M_UNKNOWN) maneuverFromSmall else maneuverFromText
        val distanceMeters = parseDistance(text) ?: parseDistance(title) ?: 0
        val road = extractRoad(title, text)
        val etaSeconds = parseEtaSeconds(subText)

        Logger.i(TAG, "posted icon=$smallIconName m=$maneuver(${ManeuverMapper.maneuverName(maneuver)}) d=${distanceMeters}m road='$road' eta=${etaSeconds}s maps=$isMaps")

        removePostedMs = 0L

        val notifManeuverKnown = maneuver != ManeuverMapper.M_UNKNOWN

        if (HudState.isTestLatched()) {
            HudState.update { it.copy(active = true, lastUpdateMs = System.currentTimeMillis(), iconPng = iconPngBytes) }
            return
        }

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
            val mergeTotalTime = if (etaSeconds > 0) etaSeconds else prev.totalTimeSeconds
            prev.copy(
                active = true,
                maneuver = mergeManeuver,
                distanceMeters = mergeDist,
                road = mergeRoad,
                etaSeconds = mergeEta,
                totalTimeSeconds = mergeTotalTime,
                lastUpdateMs = System.currentTimeMillis(),
                iconPng = iconPngBytes ?: prev.iconPng
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName !in YANDEX_PKGS) return
        if (removePostedMs != 0L) return
        removePostedMs = System.currentTimeMillis()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (removePostedMs != 0L && System.currentTimeMillis() - removePostedMs >= REMOVE_DEBOUNCE_MS - 100) {
                Logger.i(TAG, "removed after debounce — deactivating HUD")
                HudState.update { it.copy(active = false, lastUpdateMs = System.currentTimeMillis()) }
                removePostedMs = 0L
            }
        }, REMOVE_DEBOUNCE_MS)
        Logger.i(TAG, "removed — debounce ${REMOVE_DEBOUNCE_MS}ms")
    }

    private fun resolveIconName(pkg: String, resId: Int): String {
        if (resId == 0) return ""
        return try {
            val ctx = createPackageContext(pkg, 0)
            ctx.resources.getResourceEntryName(resId)
        } catch (_: Throwable) { "" }
    }

    private fun detectManeuverFromBitmap(bmp: Bitmap): Int? {
        val w = bmp.width; val h = bmp.height
        if (w < 8 || h < 8) return null
        val pixels = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var left = 0L; var right = 0L; var top = 0L; var bottom = 0L; var count = 0
        for (y in 0 until h) {
            for (x in 0 until w) {
                val a = (pixels[y * w + x] ushr 24) and 0xff
                if (a > 128) {
                    count++
                    if (x < w / 2) left++; else right++
                    if (y < h / 2) top++; else bottom++
                }
            }
        }
        if (count < 5) return null
        val hBias = (right - left).toDouble() / count.toDouble()
        val vBias = (bottom - top).toDouble() / count.toDouble()
        return when {
            hBias > 0.25 && kotlin.math.abs(vBias) < 0.25 -> ManeuverMapper.M_RIGHT
            hBias < -0.25 && kotlin.math.abs(vBias) < 0.25 -> ManeuverMapper.M_LEFT
            vBias < -0.25 && kotlin.math.abs(hBias) < 0.25 -> ManeuverMapper.M_STRAIGHT
            vBias > 0.35 && kotlin.math.abs(hBias) < 0.25 -> ManeuverMapper.M_UTURN_LEFT
            hBias > 0.25 && vBias < -0.25 -> ManeuverMapper.M_SLIGHT_RIGHT
            hBias < -0.25 && vBias < -0.25 -> ManeuverMapper.M_SLIGHT_LEFT
            else -> null
        }
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
