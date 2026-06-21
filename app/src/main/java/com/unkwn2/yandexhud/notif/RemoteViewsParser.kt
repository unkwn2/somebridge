package com.unkwn2.yandexhud.notif

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.RemoteViews
import android.widget.TextView
import com.unkwn2.yandexhud.util.Logger
import com.unkwn2.yandexhud.util.ResCache
import java.io.ByteArrayOutputStream

data class RvNaviInfo(
    val instruction: String = "",
    val road: String = "",
    val distToManeuverM: Int = 0,
    val totalDistM: Int = 0,
    val arrivalTime: String = "",
    val remainingTimeSec: Int = 0,
    val maneuverPng: ByteArray? = null,
    val trafficLightColor: String = "",
    val trafficLightSeconds: Int = 0,
    val cameraAlert: String = "",
    val maneuverEnum: Int? = null     // детерминированный манёвр из имени ресурса (null = не извлекался)
)

object RemoteViewsParser {
    private const val TAG = "RvParser"
    private const val TIMEOUT_MS = 3000L

    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)(?!\S)""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)(?!\S)""", RegexOption.IGNORE_CASE)
    private val TIME_HHMM = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val DUR = Regex("""(?:(\d+)\s*ч)?\s*(?:(\d+)\s*мин)?""")

    private const val F_DIST_TO_MANEUVER = "titleview"
    private const val F_DESCRIPTION = "descriptionview"
    private const val F_TOTAL_DIST = "remainingdistanceview"
    private const val F_ARRIVAL = "timeofarrivalview"
    private const val F_REMAINING = "remainingtimeview"

    private val IMAGE_BLOCKLIST = listOf(
        "traffic_light", "etaprogress", "progress", "action", "eta", "dots", "primaryicon"
    )

    private val ACTION_BUTTON_TEXTS = setOf(
        "завершить маршрут", "отмена", "отменить", "закрыть",
        "без уведомлений", "выключить уведомления", "парковки", "обзор"
    )

    private val MAPS_PKGS = setOf(
        "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta",
        "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore"
    )

    private data class NamedText(val name: String, val value: String)
    private data class NamedImage(val name: String, val drawable: Drawable)

    fun parse(ctx: Context, n: Notification, pkg: String, probe: Boolean): RvNaviInfo? {
        val rv = n.bigContentView ?: n.contentView ?: n.headsUpContentView
            ?: run { Logger.w(TAG, "no RemoteViews"); return null }

        val fromActions = RemoteViewsActionExtractor.extractActions(ctx, rv, pkg)
            .takeIf { it.isNotEmpty() }
            ?.let { buildFromActions(it) }

        val fromRender = if (Looper.myLooper() == Looper.getMainLooper()) {
            parseOnMain(ctx, rv, pkg, probe)
        } else {
            var result: RvNaviInfo? = null
            val lock = Object()
            Handler(Looper.getMainLooper()).post {
                result = parseOnMain(ctx, rv, pkg, probe)
                synchronized(lock) { lock.notifyAll() }
            }
            synchronized(lock) {
                try { lock.wait(TIMEOUT_MS) } catch (_: InterruptedException) {}
            }
            result
        }

        return mergePreferActions(fromActions, fromRender)
    }

    // --- Actions path (детерминированный, через mActions) ---

    private fun buildFromActions(actions: List<RemoteViewsActionExtractor.RvAction>): RvNaviInfo {
        var maneuverEnum: Int? = null
        var road: String? = null
        var distStr: String? = null
        var cameraAlert: String? = null
        var trafficColor: String? = null
        var trafficSeconds: Int = 0

        for (a in actions) {
            when (a.viewIdName) {
                "primaryicontinted" -> {
                    if (a.op == RemoteViewsActionExtractor.Op.IMAGE_RES && a.value.isNotEmpty()) {
                        val m = ManeuverMapper.fromYandexRes(a.value)
                        if (m != ManeuverMapper.M_UNKNOWN) maneuverEnum = m
                    }
                }
                "primaryicon" -> {
                    if (a.op == RemoteViewsActionExtractor.Op.IMAGE_RES && a.value.isNotEmpty()) {
                        val alert = ManeuverMapper.roadAlertFromRes(a.value)
                        if (alert.isNotEmpty()) cameraAlert = alert
                    }
                }
                "titleview" -> {
                    if (a.op == RemoteViewsActionExtractor.Op.TEXT && a.value.isNotEmpty()
                        && a.value != "setText") distStr = a.value
                }
                "descriptionview" -> {
                    if (a.op == RemoteViewsActionExtractor.Op.TEXT && a.value.isNotEmpty()
                        && a.value != "setText" && !ManeuverMapper.isServicePhrase(a.value)
                        && !TIME_HHMM.containsMatchIn(a.value)) {
                        road = a.value
                    }
                }
            }
            if (a.viewIdName.startsWith("traffic_light")) {
                if (a.op == RemoteViewsActionExtractor.Op.BG_RES) {
                    ManeuverMapper.trafficColorFromBgRes(a.value)?.let { trafficColor = it }
                }
                if (a.op == RemoteViewsActionExtractor.Op.TEXT) {
                    val secs = a.value.filter { it.isDigit() }.toIntOrNull()
                    if (secs != null && secs > 0) trafficSeconds = secs
                }
            }
        }

        return RvNaviInfo(
            road = road ?: "",
            distToManeuverM = distStr?.let { parseDistance(it) } ?: 0,
            trafficLightColor = trafficColor ?: "",
            trafficLightSeconds = trafficSeconds,
            cameraAlert = cameraAlert ?: "",
            maneuverEnum = maneuverEnum
        )
    }

    // --- Merge: actions-путь приоритетнее рендера ---

    private fun mergePreferActions(actions: RvNaviInfo?, render: RvNaviInfo?): RvNaviInfo? {
        if (actions == null) return render
        if (render == null) return actions

        return RvNaviInfo(
            instruction = render.instruction.ifEmpty { actions.instruction },
            road = actions.road.ifEmpty { render.road },
            distToManeuverM = actions.distToManeuverM.takeIf { it > 0 } ?: render.distToManeuverM,
            totalDistM = render.totalDistM.takeIf { it > 0 } ?: actions.totalDistM,
            arrivalTime = render.arrivalTime.ifEmpty { actions.arrivalTime },
            remainingTimeSec = render.remainingTimeSec.takeIf { it > 0 } ?: actions.remainingTimeSec,
            maneuverPng = render.maneuverPng ?: actions.maneuverPng,
            trafficLightColor = actions.trafficLightColor.ifEmpty { render.trafficLightColor },
            trafficLightSeconds = actions.trafficLightSeconds.takeIf { it > 0 } ?: render.trafficLightSeconds,
            cameraAlert = actions.cameraAlert.ifEmpty { render.cameraAlert },
            maneuverEnum = actions.maneuverEnum ?: render.maneuverEnum
        )
    }

    // --- Render path (текущая реализация, fallback) ---

    private fun parseOnMain(ctx: Context, rv: RemoteViews, pkg: String, probe: Boolean): RvNaviInfo? = try {
        val parent = FrameLayout(ctx)
        val view = rv.apply(ctx, parent)

        val res = ResCache.get(ctx, pkg)
        fun nameOf(id: Int): String {
            if (id == View.NO_ID || res == null) return ""
            return try { res.getResourceEntryName(id).lowercase() } catch (_: Throwable) { "" }
        }

        val rawTexts = mutableListOf<Pair<Int, String>>()
        val rawImages = mutableListOf<Pair<Int, Drawable>>()
        walk(view, rawTexts, rawImages)

        val texts = rawTexts.map { NamedText(nameOf(it.first), it.second) }
        val images = rawImages.map { NamedImage(nameOf(it.first), it.second) }

        if (probe) dump(pkg, texts, images)
        classify(texts, images, pkg in MAPS_PKGS)
    } catch (t: Throwable) {
        Logger.w(TAG, "parse error: ${t.message}")
        null
    }

    private fun walk(v: View, texts: MutableList<Pair<Int, String>>, images: MutableList<Pair<Int, Drawable>>) {
        when (v) {
            is TextView -> {
                val s = v.text?.toString()?.trim().orEmpty()
                if (s.isNotEmpty()) texts.add(v.id to s)
            }
            is ImageView -> {
                val d = v.drawable ?: return
                if (d.intrinsicWidth > 0 && d.intrinsicHeight > 0) images.add(v.id to d)
            }
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), texts, images)
    }

    private fun dump(pkg: String, texts: List<NamedText>, images: List<NamedImage>) {
        Logger.i(TAG, "=== RV DUMP ($pkg) ===")
        for (t in texts) Logger.i(TAG, "TEXT [${t.name.ifEmpty { "NO_ID" }}] = \"${t.value}\"")
        for (im in images) Logger.i(TAG, "IMAGE [${im.name.ifEmpty { "NO_ID" }}] ${im.drawable.intrinsicWidth}x${im.drawable.intrinsicHeight}")
        Logger.i(TAG, "=== END DUMP ===")
    }

    private fun classify(texts: List<NamedText>, images: List<NamedImage>, isMaps: Boolean): RvNaviInfo {
        fun byName(n: String) = texts.firstOrNull { it.name == n }?.value

        val titleVal = byName(F_DIST_TO_MANEUVER)
        val descVal = byName(F_DESCRIPTION)
        val totalVal = byName(F_TOTAL_DIST)
        val arrivalByName = byName(F_ARRIVAL)?.let { TIME_HHMM.find(it)?.value }
        val remainingByName = byName(F_REMAINING)?.let { parseDuration(it).takeIf { s -> s > 0 } }

        var instruction = ""
        var road = ""
        if (descVal != null) {
            if (isMaps) {
                instruction = descVal
            } else {
                val m = ManeuverMapper.fromRussianText(descVal)
                if (m != ManeuverMapper.M_UNKNOWN) instruction = descVal
                else if (!TIME_HHMM.containsMatchIn(descVal)) road = descVal
            }
        }

        var distManeuver = titleVal?.let { parseDistance(it) } ?: 0
        var distTotal = totalVal?.let { parseDistance(it) } ?: 0
        var arrival = arrivalByName ?: ""
        var remaining = remainingByName ?: 0

        val trafficLightImgs = images.filter { "traffic_light" in it.name }
        val trafficLightColor = trafficLightImgs.firstNotNullOfOrNull { detectDominantColor(it.drawable) } ?: ""
        val trafficLightSeconds = texts.firstOrNull { "traffic_light" in it.name }
            ?.value?.filter { it.isDigit() }?.toIntOrNull() ?: 0

        val cameraAlert = when {
            images.any { it.name == "primaryicon" } -> "camera"
            else -> ""
        }

        if (distManeuver == 0 || distTotal == 0 || arrival.isEmpty() || remaining == 0 || (road.isEmpty() && instruction.isEmpty())) {
            val values = texts.map { it.value }
            val dists = values.mapNotNull { parseDistance(it) }
            if (distManeuver == 0) distManeuver = dists.getOrElse(0) { 0 }
            if (distTotal == 0) distTotal = dists.getOrElse(1) { 0 }
            if (arrival.isEmpty()) arrival = values.firstNotNullOfOrNull { TIME_HHMM.find(it)?.value } ?: ""
            if (remaining == 0) remaining = values.firstNotNullOfOrNull { v ->
                if (v.contains("мин") || v.contains("ч")) parseDuration(v).takeIf { it > 0 } else null
            } ?: 0

            val textual = values.filter { s ->
                s.any { it.isLetter() } &&
                    parseDistance(s) == null &&
                    !TIME_HHMM.matches(s) &&
                    ACTION_BUTTON_TEXTS.none { it in s.lowercase() }
            }.sortedByDescending { it.length }

            if (instruction.isEmpty()) {
                instruction = textual.firstOrNull { ManeuverMapper.fromRussianText(it) != ManeuverMapper.M_UNKNOWN } ?: ""
            }
            if (road.isEmpty()) {
                road = textual.firstOrNull { it != instruction } ?: ""
            }
        }

        if (ACTION_BUTTON_TEXTS.any { it in road.lowercase() } ||
            road == "Навигатор запущен") road = ""

        val byIconName = images.firstOrNull { it.name == "primaryicontinted" }
            ?: images.firstOrNull { "nextmaneuver" in it.name }
        val pngDrawable = byIconName?.drawable ?: images
            .filter { im -> IMAGE_BLOCKLIST.none { it in im.name } }
            .minByOrNull { im ->
                val w = im.drawable.intrinsicWidth; val h = im.drawable.intrinsicHeight
                if (w <= 0 || h <= 0) Double.MAX_VALUE
                else kotlin.math.abs(w.toDouble() / h.toDouble() - 1.0)
            }?.drawable

        return RvNaviInfo(
            instruction, road, distManeuver, distTotal, arrival, remaining,
            pngDrawable?.let { toPng(it) },
            trafficLightColor, trafficLightSeconds, cameraAlert
        )
    }

    private fun detectDominantColor(d: Drawable): String? {
        val bmp = if (d is BitmapDrawable) d.bitmap else return null
        val w = bmp.width; val h = bmp.height
        if (w < 4 || h < 4) return null
        val pixels = IntArray(w * h).also { bmp.getPixels(it, 0, w, 0, 0, w, h) }
        var r = 0L; var g = 0L; var b = 0L; var count = 0L
        for (y in 0 until h) {
            for (x in 0 until w) {
                val p = pixels[y * w + x]
                if ((p ushr 24) and 0xff > 128) {
                    r += (p ushr 16) and 0xff
                    g += (p ushr 8) and 0xff
                    b += p and 0xff
                    count++
                }
            }
        }
        if (count < 3) return null
        r /= count; g /= count; b /= count
        return when {
            r > 180 && g < 120 && b < 120 -> "red"
            r > 180 && g > 150 && b < 100 -> "yellow"
            g > 150 && r < 130 && b < 130 -> "green"
            else -> null
        }
    }

    private fun parseDistance(s: String): Int? {
        DIST_KM.find(s)?.let {
            return (it.groupValues[1].replace(',', '.').toDoubleOrNull()?.times(1000))?.toInt()
        }
        DIST_M.find(s)?.let { return it.groupValues[1].toIntOrNull() }
        return null
    }

    private fun parseDuration(s: String): Int {
        val m = DUR.find(s) ?: return 0
        val h = m.groupValues[1].toIntOrNull() ?: 0
        val min = m.groupValues[2].toIntOrNull() ?: 0
        return h * 3600 + min * 60
    }

    private fun toPng(d: Drawable): ByteArray? = try {
        val isShared = d is BitmapDrawable
        val bmp = if (d is BitmapDrawable) d.bitmap
        else {
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b); d.setBounds(0, 0, w, h); d.draw(c)
            }
        }
        try {
            ByteArrayOutputStream().use { out ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
            }
        } finally { if (!isShared) bmp.recycle() }
    } catch (_: Throwable) { null }
}
