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
    val maneuverPng: ByteArray? = null
)

object RemoteViewsParser {
    private const val TAG = "RvParser"
    private const val TIMEOUT_MS = 3000L

    private val DIST_KM = Regex("""(\d+(?:[.,]\d+)?)\s*(км|km)\b""", RegexOption.IGNORE_CASE)
    private val DIST_M = Regex("""(\d+)\s*(м|m)\b""", RegexOption.IGNORE_CASE)
    private val TIME_HHMM = Regex("""\b([01]?\d|2[0-3]):([0-5]\d)\b""")
    private val DUR = Regex("""(?:(\d+)\s*ч)?\s*(?:(\d+)\s*мин)?""")

    fun parse(ctx: Context, n: Notification, pkg: String, probe: Boolean): RvNaviInfo? {
        val rv = n.bigContentView ?: n.contentView ?: n.headsUpContentView
            ?: run { Logger.w(TAG, "no RemoteViews"); return null }

        return if (Looper.myLooper() == Looper.getMainLooper()) {
            // Уже на main-потоке (колбэк листенера) — работаем напрямую
            parseOnMain(ctx, rv, pkg, probe)
        } else {
            // Фон — переключаемся на main
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
        val view = rv.apply(ctx, parent)  // ← инфлейтнутое дерево, а НЕ parent
        val texts = mutableListOf<Pair<Int, String>>()
        val images = mutableListOf<Pair<Int, Drawable>>()
        walk(view, texts, images)
        if (probe) dump(ctx, pkg, texts, images)
        classify(texts, images)
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
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                walk(v.getChildAt(i), texts, images)
            }
        }
    }

    private fun dump(ctx: Context, pkg: String, texts: List<Pair<Int, String>>, images: List<Pair<Int, Drawable>>) {
        val res = try { ctx.createPackageContext(pkg, 0).resources } catch (_: Throwable) { null }
        fun name(id: Int): String {
            if (id == View.NO_ID || res == null) return "NO_ID"
            return try { res.getResourceEntryName(id) } catch (_: Throwable) { "id_$id" }
        }
        Logger.i(TAG, "=== RV DUMP ($pkg) ===")
        for ((id, t) in texts) Logger.i(TAG, "TEXT [${name(id)}] = \"$t\"")
        for ((id, d) in images) Logger.i(TAG, "IMAGE [${name(id)}] ${d.intrinsicWidth}x${d.intrinsicHeight}")
        Logger.i(TAG, "=== END DUMP ===")
    }

    // Строки-действия кнопок, которые не должны попасть в instruction/road
    private val ACTION_BUTTON_TEXTS = setOf(
        "завершить маршрут", "отмена", "отменить", "закрыть"
    )

    private fun classify(texts: List<Pair<Int, String>>, images: List<Pair<Int, Drawable>>): RvNaviInfo {
        val values = texts.map { it.second }

        val dists = values.mapNotNull { parseDistance(it) }
        val distManeuver = dists.getOrElse(0) { 0 }
        val distTotal = dists.getOrElse(1) { 0 }

        val arrival = values.firstNotNullOfOrNull { TIME_HHMM.find(it)?.value } ?: ""
        val remaining = values.firstNotNullOfOrNull { v ->
            if (v.contains("мин") || v.contains("ч")) parseDuration(v).takeIf { it > 0 } else null
        } ?: 0

        // Ищем текст манёвра: любой текст, совпадающий с известными фразами
        val maneuverText = values.firstNotNullOfOrNull { v ->
            val m = ManeuverMapper.fromRussianText(v)
            if (m != ManeuverMapper.M_UNKNOWN) v else null
        }

        // Текстовые строки без цифр/дистанций — сортируем по длине
        // Исключаем тексты кнопок (actionButton и т.п.)
        val textual = values.filter { s ->
            s.any { it.isLetter() }
                && parseDistance(s) == null
                && !TIME_HHMM.matches(s)
                && ACTION_BUTTON_TEXTS.none { it in s.lowercase() }
        }.sortedByDescending { it.length }

        // Если найден текст манёвра — он instruction, иначе самая длинная строка
        val instruction = maneuverText ?: textual.getOrElse(0) { "" }
        val road = if (maneuverText != null) {
            // Если манёвр нашли — дорога это следующая по длине текстовая строка
            textual.filter { it != maneuverText }.getOrElse(0) { "" }
        } else {
            textual.getOrElse(1) { instruction }
        }

        // Иконка манёвра: выбираем самую КВАДРАТНУЮ (aspect ratio ~1), не самую большую
        val png = images.minByOrNull { (_, d) ->
            val w = d.intrinsicWidth; val h = d.intrinsicHeight
            if (w <= 0 || h <= 0) Double.MAX_VALUE
            else kotlin.math.abs(w.toDouble() / h.toDouble() - 1.0)
        }?.second?.let { toPng(it) }

        return RvNaviInfo(instruction, road, distManeuver, distTotal, arrival, remaining, png)
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
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    } catch (_: Throwable) { null }
}
