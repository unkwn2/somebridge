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
import java.io.ByteArrayOutputStream

data class RvNaviInfo(
    val instruction: String = "",
    val road: String = "",
    val distToManeuverM: Int = 0,
    val totalDistM: Int = 0,
    val arrivalTime: String = "",
    val remainingTimeSec: Int = 0,
    val maneuverPng: ByteArray? = null,
    val trafficLightColor: String = "",  // "red", "green", "yellow", или ""
    val trafficLightSeconds: Int = 0,
    val cameraAlert: String = ""         // "camera", "accident", "roadworks", "other", или ""
)

object RemoteViewsParser {
    private const val TAG = "RvParser"
    private const val TIMEOUT_MS = 3000L

    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)\b""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)\b""", RegexOption.IGNORE_CASE)
    private val TIME_HHMM = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val DUR = Regex("""(?:(\d+)\s*ч)?\s*(?:(\d+)\s*мин)?""")

    // --- Имена полей из RV DUMP (точные id Яндекса, в lowercase) ---
    private const val F_DIST_TO_MANEUVER = "titleview"
    private const val F_DESCRIPTION = "descriptionview"
    private const val F_TOTAL_DIST = "remainingdistanceview"
    private const val F_ARRIVAL = "timeofarrivalview"
    private const val F_REMAINING = "remainingtimeview"

    // Имена картинок-стрелок (квадратные 48x48) — точное совпадение с resource entry name
    private val MANEUVER_ICON_NAMES = setOf(
        "primaryicontinted", "nextmaneuverviewtinted", "nextmaneuverview"
    )
    // Картинки, которые НИКОГДА не должны попасть в f8
    private val IMAGE_BLOCKLIST = listOf(
        "traffic_light", "etaprogress", "progress", "action", "eta", "dots"
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

        return if (Looper.myLooper() == Looper.getMainLooper()) {
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
    }

    private fun parseOnMain(ctx: Context, rv: RemoteViews, pkg: String, probe: Boolean): RvNaviInfo? = try {
        val parent = FrameLayout(ctx)
        val view = rv.apply(ctx, parent)

        // Резолвер имён ресурсов исходного пакета (Яндекса)
        val res = try { ctx.createPackageContext(pkg, 0).resources } catch (_: Throwable) { null }
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

        // --- 1. Детерминированно по именам полей ---
        val titleVal = byName(F_DIST_TO_MANEUVER)
        val descVal = byName(F_DESCRIPTION)
        val totalVal = byName(F_TOTAL_DIST)
        val arrivalByName = byName(F_ARRIVAL)?.let { TIME_HHMM.find(it)?.value }
        val remainingByName = byName(F_REMAINING)?.let { parseDuration(it).takeIf { s -> s > 0 } }

        // descriptionView: у Карт = манёвр всегда; у Навигатора = ИЛИ улица ИЛИ манёвр
        var instruction = ""
        var road = ""
        if (descVal != null) {
            if (isMaps) {
                instruction = descVal
            } else {
                val m = ManeuverMapper.fromRussianText(descVal)
                if (m != ManeuverMapper.M_UNKNOWN) instruction = descVal else road = descVal
            }
        }

        var distManeuver = titleVal?.let { parseDistance(it) } ?: 0
        var distTotal = totalVal?.let { parseDistance(it) } ?: 0
        var arrival = arrivalByName ?: ""
        var remaining = remainingByName ?: 0

        // --- 2. Светофоры ---
        val trafficLightImgs = images.filter { "traffic_light" in it.name }
        val trafficLightColor = trafficLightImgs.firstNotNullOfOrNull { detectDominantColor(it.drawable) } ?: ""
        val trafficLightSeconds = texts.firstOrNull { "traffic_light" in it.name }
            ?.value?.filter { it.isDigit() }?.toIntOrNull() ?: 0

        // --- 3. Камера (primaryicon — без tinted) ---
        val cameraAlert = when {
            images.any { it.name == "primaryicon" } -> "camera"
            else -> ""
        }

        // --- 4. Fallback по эвристике (свёрнутые уведомления без resource id) ---
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

        // Подстраховка: служебные строки не должны быть дорогой
        if (ACTION_BUTTON_TEXTS.any { it in road.lowercase() } ||
            road == "Навигатор запущен") road = ""

        // --- 5. Картинка строго по имени (точное совпадение), иначе самая квадратная (без блок-листа) ---
        val byIconName = images.firstOrNull { im -> MANEUVER_ICON_NAMES.contains(im.name) }
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

    // Определяем доминирующий цвет маленькой иконки (traffic light dot 24x24)
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
            g > 150 && b < 130 -> "green"
            r > 180 && g > 150 && b < 100 -> "yellow"
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
        val bmp = if (d is BitmapDrawable) d.bitmap
        else {
            val w = d.intrinsicWidth.coerceAtLeast(1)
            val h = d.intrinsicHeight.coerceAtLeast(1)
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
                val c = Canvas(b); d.setBounds(0, 0, w, h); d.draw(c)
            }
        }
        ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out); out.toByteArray()
        }
    } catch (_: Throwable) { null }
}
