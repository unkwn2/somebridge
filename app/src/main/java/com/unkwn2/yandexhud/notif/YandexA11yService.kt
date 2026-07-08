package com.unkwn2.yandexhud.notif

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
import android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
import com.unkwn2.yandexhud.bridge.HudState
import com.unkwn2.yandexhud.util.Logger

class YandexA11yService : AccessibilityService() {

    companion object {
        private const val TAG = "YA11Y"
        private const val DEBOUNCE_MS = 500L
        private val YANDEX_MAPS_PKGS = setOf(
            "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
        )
        private val YANDEX_PKGS = setOf(
            "ru.yandex.yandexnavi", "ru.yandex.yandexnavi.inhouse", "ru.yandex.yandexnavi.rustore",
            "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta", "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
        )

        private val VID_MANEUVER_ICON = "image_maneuverballoon_maneuver"
        private val VID_MANEUVER_DIST = "text_maneuverballoon_distance"
        private val VID_MANEUVER_UNIT = "text_maneuverballoon_metrics"
        private val VID_NEXTSTREET = "text_nextstreet"
        private val VID_ROADSIGN = "roadsign_container"
        private val VID_ETA_DIST = "textview_eta_distance"
        private val VID_ETA_TIME = "textview_eta_time"
        private val VID_SPEEDLIMIT = "text_speedlimit"
        private val VID_EXIT_NUM = "exit_number_text"
        private val VID_THEN_ICON = "image_maneuverballoon_maneuver_second"
        private val VID_THEN_DIST = "text_maneuverballoon_distance_second"
        private val VID_THEN_ROAD = "text_nextstreet_second"

        private val TARGET_VIDS = setOf(
            VID_MANEUVER_ICON, VID_MANEUVER_DIST, VID_MANEUVER_UNIT,
            VID_NEXTSTREET, VID_THEN_ICON, VID_THEN_DIST, VID_THEN_ROAD,
            VID_EXIT_NUM
        )

        private val IGNORE_VID = setOf(
            "control_ruler_text", "control_weather_text", "control_traffic",
            "control_carparks", "control_tilt_reset",
            "control_zoom_in_touch_zone", "control_zoom_out_touch_zone",
            "control_position_combined_find_me", "control_position_combined_compass",
            "top_notification_title", "top_notification_description",
            "suggest_primary_text", "tab_navigation_bottom_search_line",
            "tab_navigation_alice_button", "tab_navigation_hint_switcher",
            "tab_navigation_bookmarks",
            "navi_guidance_toolbar_icon", "text_floatingbutton_caption",
            "text_waitcursor_message", "button_waitcursor_cancel",
            "button_message_cancel", "button_message_confirm",
            "text_message", "overviewicon", "resetroutebutton2",
            "activity_search_map_view", "text_speed_value"
        )

        @Volatile private var lastProcessMs = 0L

        private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\S)""", RegexOption.IGNORE_CASE)
        private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\S)""", RegexOption.IGNORE_CASE)
        private val ETA_MIN = Regex("""(\d+)\s*мин""", RegexOption.IGNORE_CASE)
        private val ETA_HR_MIN = Regex("""(\d+)\s*ч\s*(\d+)\s*мин""", RegexOption.IGNORE_CASE)

        @Volatile private var lastDumpMs = 0L
        @Volatile var dumpRequested = false
    }

    override fun onServiceConnected() {
        Logger.i(TAG, "connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName == null) return
        val pkg = event.packageName.toString()
        if (pkg !in YANDEX_PKGS) return
        val et = event.eventType
        if (et != TYPE_WINDOW_CONTENT_CHANGED && et != TYPE_WINDOW_STATE_CHANGED) return

        val now = System.currentTimeMillis()
        if (now - lastProcessMs < DEBOUNCE_MS) return
        lastProcessMs = now

        val root = rootInActiveWindow ?: return
        try {
            val collected = mutableListOf<NodeData>()
            collectNodes(root, collected, 0)
            if (collected.isEmpty()) return

            if (dumpRequested) {
                dumpRequested = false
                Logger.i(TAG, "=== FULL DUMP (${collected.size} nodes) ===")
                for (n in collected) {
                    Logger.i(TAG, "node d=${n.depth} vid=${n.vid} cls=${n.cls} t='${n.text}' desc='${n.desc}'")
                }
                Logger.i(TAG, "=== END DUMP ===")
            }

            val nowDump = System.currentTimeMillis()
            if (nowDump - lastDumpMs > 15000) {
                lastDumpMs = nowDump
                val relevant = collected.filter {
                    val short = it.vid.substringAfter('/', "")
                    short.isNotEmpty() && short !in IGNORE_VID
                }
                if (relevant.isNotEmpty()) {
                    for (n in relevant.take(15)) {
                        Logger.i(TAG, "node d=${n.depth} vid=${n.vid} cls=${n.cls} t='${n.text}' desc='${n.desc}'")
                    }
                }
            }

            val byVid = mutableMapOf<String, NodeData>()
            for (n in collected) {
                val short = n.vid.substringAfter('/', "")
                if (short.isNotEmpty() && short !in IGNORE_VID) byVid[short] = n
            }

            val hasManeuverBalloon = byVid.containsKey(VID_MANEUVER_ICON) ||
                                     byVid.containsKey(VID_MANEUVER_DIST) ||
                                     byVid.containsKey(VID_NEXTSTREET)

            val maneuver = resolveManeuver(byVid)
            val maneuverGaode = resolveManeuverGaode(byVid)
            val distance = resolveDistance(byVid)
            val road = resolveRoad(byVid)
            val eta = resolveEta(byVid)
            val totalDist = resolveTotalDist(byVid)
            val speedLimit = resolveSpeedLimit(byVid)
            val nextNextManeuver = resolveNextNextManeuver(byVid)

            if (hasManeuverBalloon) {
                val mStr = ManeuverMapper.maneuverName(maneuver)
                val nnStr = if (nextNextManeuver > 0) ManeuverMapper.maneuverName(nextNextManeuver) else ""
                Logger.i(TAG, "pkg=$pkg m=$mStr gaode=$maneuverGaode d=${distance}m road='$road' eta=${eta}s balloon=$hasManeuverBalloon nextNext=$nnStr")
                HudState.update { prev ->
                    val mergeManeuver = if (maneuver != ManeuverMapper.M_UNKNOWN) maneuver else prev.maneuver
                    val mergeGaode = if (maneuverGaode > 0) maneuverGaode else prev.maneuverGaode
                    val mergeGaodeMs = if (maneuverGaode > 0) System.currentTimeMillis() else prev.maneuverGaodeMs
                    val mergeDist = if (distance > 0) distance else prev.distanceMeters
                    val mergeRoad = if (road.isNotEmpty()) road else prev.road
                    val mergeEta = if (eta > 0) eta else prev.etaSeconds
                    val mergeTotalDist = if (totalDist > 0) totalDist else prev.totalDistMeters
                    val mergeTotalTime = if (eta > 0) eta else prev.totalTimeSeconds
                    val mergeSpeedLimit = if (speedLimit > 0) speedLimit else prev.speedLimit
                    val mergeNextNext = if (nextNextManeuver > 0) nextNextManeuver else prev.nextNextManeuver
                    prev.copy(
                        active = true,
                        maneuver = mergeManeuver,
                        maneuverGaode = mergeGaode,
                        maneuverGaodeMs = mergeGaodeMs,
                        distanceMeters = mergeDist,
                        road = mergeRoad,
                        etaSeconds = mergeEta,
                        totalDistMeters = mergeTotalDist,
                        totalTimeSeconds = mergeTotalTime,
                        speedLimit = mergeSpeedLimit,
                        nextNextManeuver = mergeNextNext,
                        lastUpdateMs = System.currentTimeMillis()
                    )
                }
            }
        } catch (t: Throwable) {
            Logger.e(TAG, "parse error: ${t.message}")
        } finally {
            try { root.recycle() } catch (_: Throwable) {}
        }
    }

    override fun onInterrupt() {
        Logger.w(TAG, "interrupted")
    }

    private data class NodeData(
        val text: String,
        val desc: String,
        val cls: String,
        val vid: String,
        val depth: Int
    )

    private fun collectNodes(node: AccessibilityNodeInfo, out: MutableList<NodeData>, depth: Int) {
        val text = node.text?.toString()?.trim() ?: ""
        val desc = node.contentDescription?.toString()?.trim() ?: ""
        val cls = node.className?.toString() ?: ""
        val vid = node.viewIdResourceName ?: ""
        val short = vid.substringAfter('/', "")

        if (text.isNotEmpty() || desc.isNotEmpty() || short in TARGET_VIDS) {
            out += NodeData(text, desc, cls, vid, depth)
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectNodes(child, out, depth + 1)
            child.recycle()
        }
    }

    private fun resolveManeuver(byVid: Map<String, NodeData>): Int {
        val iconNode = byVid[VID_MANEUVER_ICON]
        if (iconNode != null) {
            if (iconNode.desc.isNotEmpty()) {
                val m = ManeuverMapper.fromRussianText(iconNode.desc)
                if (m != ManeuverMapper.M_UNKNOWN) return m
            }
            // desc="" and text="" means Yandex didn't expose the icon — do NOT
            // assume STRAIGHT, fall through to other passes
        }

        for ((vid, n) in byVid) {
            if (n.desc.isEmpty()) continue
            if (vid == VID_NEXTSTREET || vid == VID_ROADSIGN || vid == "statusPanel" ||
                vid == VID_THEN_ICON || vid == VID_THEN_DIST || vid == VID_THEN_ROAD) continue
            val m = ManeuverMapper.fromRussianText(n.desc)
            if (m != ManeuverMapper.M_UNKNOWN) return m
        }

        for ((vid, n) in byVid) {
            if (n.text.isEmpty()) continue
            if (vid == VID_NEXTSTREET || vid == VID_ROADSIGN || vid == "statusPanel" ||
                vid == VID_THEN_ICON || vid == VID_THEN_DIST || vid == VID_THEN_ROAD) continue
            val m = ManeuverMapper.fromRussianText(n.text)
            if (m != ManeuverMapper.M_UNKNOWN) return m
        }

        for ((vid, n) in byVid) {
            if (vid !in TARGET_VIDS) continue
            val t = n.desc.ifEmpty { n.text }
            if (t.isNotEmpty()) {
                val m = ManeuverMapper.fromIconName(t)
                if (m != ManeuverMapper.M_UNKNOWN) return m
            }
        }

        return ManeuverMapper.M_UNKNOWN
    }

    private fun resolveManeuverGaode(byVid: Map<String, NodeData>): Int {
        val iconNode = byVid[VID_MANEUVER_ICON]
        if (iconNode == null) return 0

        val desc = iconNode.desc.ifEmpty { iconNode.text }.ifEmpty { null }

        if (desc == null) return 0

        val exitNode = byVid[VID_EXIT_NUM]
        // Наличие exit_number_text с числом 1..10 — достаточный признак кольца,
        // даже если desc балуна = "Поверните направо/правее" (Яндекс так делает на малых кольцах).
        val exitNumParsed = exitNode?.text?.let { Regex("""\d+""").find(it)?.value }?.toIntOrNull()
        if (exitNumParsed != null && exitNumParsed in 1..10) {
            return 24
        }

        val isRoundaboutDesc = "кольцев" in desc.lowercase() || "выезд с кольца" in desc.lowercase() || "съезд" in desc.lowercase()
        if (isRoundaboutDesc && exitNode != null && exitNode.text.isNotEmpty()) {
            val fullDesc = "$desc ${exitNode.text}-й съезд"
            return ManeuverMapper.fromA11yDescription(fullDesc)
        }

        return ManeuverMapper.fromA11yDescription(desc)
    }

    private fun resolveDistance(byVid: Map<String, NodeData>): Int {
        val distNode = byVid[VID_MANEUVER_DIST]
        val unitNode = byVid[VID_MANEUVER_UNIT]
        if (distNode != null) {
            val raw = distNode.text.trim()
            val num = raw.toIntOrNull()
            if (num != null) {
                val unit = unitNode?.text?.trim()?.lowercase() ?: "м"
                return if (unit.startsWith("км") || unit.startsWith("km")) num * 1000 else num
            }
            val frac = raw.replace(',', '.').toDoubleOrNull()
            if (frac != null) {
                val unit = unitNode?.text?.trim()?.lowercase() ?: "м"
                return if (unit.startsWith("км") || unit.startsWith("km")) (frac * 1000).toInt() else frac.toInt()
            }
        }

        val etaNode = byVid[VID_ETA_DIST]
        if (etaNode != null) {
            val s = "${etaNode.text} ${etaNode.desc}"
            val km = DIST_KM.find(s)
            if (km != null) {
                val v = km.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
                return (v * 1000).toInt()
            }
            val m = DIST_M.find(s)
            if (m != null) return m.groupValues[1].toIntOrNull() ?: 0
        }

        return 0
    }

    private fun resolveRoad(byVid: Map<String, NodeData>): String {
        val streetNode = byVid[VID_NEXTSTREET]
        if (streetNode != null && streetNode.text.isNotEmpty()) return streetNode.text

        val roadSignNode = byVid[VID_ROADSIGN]
        if (roadSignNode != null && roadSignNode.text.isNotEmpty()) return roadSignNode.text

        val statusPanel = byVid["statusPanel"]
        if (statusPanel != null && statusPanel.text.isNotEmpty()) return statusPanel.text

        val roadPrefix = Regex("""(?i)\b(ул|улица|пр-т|проспект|просп|ш|шоссе|пер|переулок|наб|бул|пл)\b""")
        for ((_, n) in byVid) {
            if (n.text.isNotEmpty() && roadPrefix.containsMatchIn(n.text)) return n.text
        }
        return ""
    }

    private fun resolveEta(byVid: Map<String, NodeData>): Int {
        val etaTimeNode = byVid[VID_ETA_TIME]
        if (etaTimeNode != null) {
            val s = etaTimeNode.desc.ifEmpty { etaTimeNode.text }
            val hm = ETA_HR_MIN.find(s)
            if (hm != null) {
                val h = hm.groupValues[1].toIntOrNull() ?: 0
                val m = hm.groupValues[2].toIntOrNull() ?: 0
                return h * 3600 + m * 60
            }
            val mn = ETA_MIN.find(s)
            if (mn != null) return (mn.groupValues[1].toIntOrNull() ?: 0) * 60
        }
        return 0
    }

    private fun resolveTotalDist(byVid: Map<String, NodeData>): Int {
        val etaDistNode = byVid[VID_ETA_DIST]
        if (etaDistNode != null) {
            val s = etaDistNode.text
            val km = DIST_KM.find(s)
            if (km != null) {
                val v = km.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return 0
                return (v * 1000).toInt()
            }
            val m = DIST_M.find(s)
            if (m != null) return m.groupValues[1].toIntOrNull() ?: 0
        }
        return 0
    }

    private fun resolveSpeedLimit(byVid: Map<String, NodeData>): Int {
        val slNode = byVid[VID_SPEEDLIMIT]
        if (slNode != null) {
            return slNode.text.trim().toIntOrNull() ?: 0
        }
        return 0
    }

    private fun resolveNextNextManeuver(byVid: Map<String, NodeData>): Int {
        val thenIcon = byVid[VID_THEN_ICON]
        if (thenIcon != null && thenIcon.desc.isNotEmpty()) {
            val m = ManeuverMapper.fromRussianText(thenIcon.desc)
            if (m != ManeuverMapper.M_UNKNOWN) return m
        }
        for ((vid, n) in byVid) {
            if (vid.contains("second") || vid.contains("_then") || vid.contains("_next_maneuver")) {
                if (n.desc.isNotEmpty()) {
                    val m = ManeuverMapper.fromRussianText(n.desc)
                    if (m != ManeuverMapper.M_UNKNOWN) return m
                }
                if (n.text.isNotEmpty()) {
                    val m = ManeuverMapper.fromRussianText(n.text)
                    if (m != ManeuverMapper.M_UNKNOWN) return m
                }
                val t = n.desc.ifEmpty { n.text }
                if (t.isNotEmpty()) {
                    val m = ManeuverMapper.fromIconName(t)
                    if (m != ManeuverMapper.M_UNKNOWN) return m
                }
            }
        }
        return 0
    }
}
