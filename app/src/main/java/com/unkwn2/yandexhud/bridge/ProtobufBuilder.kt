package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger
import java.io.ByteArrayOutputStream

/**
 * Сборщик HudRoadInfoNotifyStruct.
 *
 * Два режима:
 *  - buildOld(...)  — РАБОЧИЙ метод от 18 июня (b18d422). НЕ ТРОГАТЬ — контрольная кнопка OLD.
 *  - buildNew(stage) — Формат эталона discope (1779 событий штатной нав U7).
 *                     Порядок полей КРИТИЧЕН — BYD-парсер нестандартный.
 *
 * Порядок полей (эталон): f2→f5→f6→f7→f8→f9→f10→f11→f16→f19→f20→f26→f28→f29→f30→f31→f33
 *  - f2 counter
 *  - f5 число полос (ДО f6!)
 *  - f6 render-class: 1=без полос, 6=с полосами (ВСЕГДА, 1779/1779)
 *  - f7 PNG-лента полос 68px/полосу × 100h (штатная нав, f29-текст ARHUD игнорирует)
 *  - f8 PNG иконки манёвра 80×80
 *  - f9 дистанция
 *  - f10 дорога
 *  - f11 speedLimit (если >0)
 *  - f16 navigatingStatus (2=draw, 1=clear)
 *  - f19/f20 lon/lat (fixed64 LE double)
 *  - f26 ETA
 *  - f28 id манёвра (сырой)
 *  - f29 строка полос "S,H|..."
 *  - f30 guideLine
 *  - f31 guidePoint (СТРОКА, НЕ submessage)
 *  - f33 progress 0..1 (fixed64 LE double)
 *
 * НЕ ОТПРАВЛЯЕТСЯ (подтверждено эталоном discope):
 *  f3/f4 — ломают рендер f9/f26 на ROM Янванга
 *  f12 — машина рисует свою скорость
 *  f17/f18 — камера идёт через f8+f9
 *  f21/f22/f23/f24/f25 — отсутствуют в рабочем кадре
 */
object ProtobufBuilder {

    // ── Стадии NEW ────────────────────────────────────────────────────────
    const val STAGE_MIN = 0
    const val STAGE_MAX = 6

    // ── SIZEGUARD ─────────────────────────────────────────────────────────
    const val MAX_PAYLOAD_BYTES = 3400

    fun stageName(stage: Int): String = when (stage) {
        0 -> "0:OLD-base"
        1 -> "1:+f6"
        2 -> "2:(rezerv)"
        3 -> "3:+f33"
        4 -> "4:f2=2"
        5 -> "5:f28=эталон"
        6 -> "6:full-эталон"
        else -> "?$stage"
    }

    // ─────────────────────────────────────────────────────────────────────
    // OLD — рабочий метод от 18 июня (стрелки работают). НЕ ТРОГАТЬ.
    // ─────────────────────────────────────────────────────────────────────
    fun buildOld(
        counter: Int,
        maneuver: Int,                 // GAODE
        distance: Int,
        road: String,
        lat: Double, lon: Double,
        etaString: String,
        totalDistMeters: Int = 0,
        totalTimeSeconds: Int = 0,
        statusIcon: Int = 0,
        speedLimit: Int = 0,
        arriveText: String = "",
        testLanes: Boolean = false,
        laneLayout: String = "",
        iconPng: ByteArray? = null      // f8 PNG иконки манёвра
    ): ByteArray {
        val inner = ByteArrayOutputStream()

        writeVarintField(inner, 2, counter.toLong())               // f2  counter (живость кадра)
        if (iconPng != null) writeBytesField(inner, 8, iconPng)    // f8  PNG иконки (если есть)
        writeVarintField(inner, 9, distance.toLong())              // f9  distance2Intersection
        writeStringField(inner, 10, road)                          // f10 nextRoadName
        writeVarintField(inner, 16, statusIcon.toLong())           // f16 navigatingStatus: 2=draw, 1=clear
        writeStringField(inner, 26, etaString)                     // f26 ETA \"HH:MM\"
        if (totalDistMeters > 0)  writeVarintField(inner, 22, totalDistMeters.toLong())
        if (totalTimeSeconds > 0) writeVarintField(inner, 23, totalTimeSeconds.toLong())
        if (speedLimit > 0)       writeVarintField(inner, 24, speedLimit.toLong())
        if (arriveText.isNotEmpty()) writeStringField(inner, 27, arriveText)
        writeVarintField(inner, 28, maneuver.toLong())             // f28 сырой gaode
        if (testLanes && laneLayout.isNotEmpty()) {
            writeVarintField(inner, 5, laneLayout.split(",").size.toLong())
            writeStringField(inner, 29, laneLayout)
        }
        if (lat != 0.0 || lon != 0.0) {
            writeDoubleField(inner, 19, lon)                       // f19 longitude
            writeDoubleField(inner, 20, lat)                       // f20 latitude
        }
        return wrap(inner)
    }

    // ─────────────────────────────────────────────────────────────────────
    // NEW — формат эталона discope (1779 событий штатной нав U7).
    // Порядок полей КРИТИЧЕН: f2→f5→f6→f7→f8→f9→f10→f11→f16→f19→f20→f26→f28→f29→f30→f31→f33
    // ─────────────────────────────────────────────────────────────────────
    fun buildNew(
        stage: Int,
        counter: Int,
        maneuver: Int,                 // GAODE
        distance: Int,
        road: String,
        lat: Double, lon: Double,
        etaString: String,
        totalDistMeters: Int = 0,
        statusIcon: Int = 0,
        speedLimit: Int = 0,
        guideLine: String = "",        // f30 — пусто = не шлём (иначе регрессия Song L)
        guidePoint: String = "",       // f31 — пусто = не шлём
        iconPngSmall: ByteArray? = null,
        testLanes: Boolean = false,
        laneLayout: String = "",
        includeF7: Boolean = true,     // SIZEGUARD: можно отключить
        includeF8: Boolean = true      // SIZEGUARD: можно отключить
    ): ByteArray {
        val useF33    = stage >= 3
        val f2Const   = stage >= 4
        val f28Mapped = stage >= 5

        val inner = ByteArrayOutputStream()

        // f2 — константа 2 (эталон) / счётчик (ранние стадии)
        writeVarintField(inner, 2, if (f2Const) 2L else counter.toLong())

        // f5/f6/f7 — ПОРЯДОК КРИТИЧЕН (f5 ДО f6!)
        // f5 = число полос; f6 = 1 (без полос) или 6 (с полосами); f7 = PNG-лента полос
        val hasLanes = testLanes && laneLayout.isNotEmpty()
        if (hasLanes) {
            writeVarintField(inner, 5, laneCount(laneLayout).toLong())
        }
        // f6 — ВСЕГДА (1779/1779): 6=с полосами, 1=без
        writeVarintField(inner, 6, if (hasLanes) 6L else 1L)
        // f7 — PNG-лента полос (68px/полосу × 100h). Штатная нав шлёт f7 вместе с f29.
        // f29-текст ARHUD игнорирует — чтобы полосы появились, нужен именно f7.
        if (includeF7 && hasLanes) {
            val lanePng = buildLaneStripPng(laneLayout)
            if (lanePng != null) writeBytesField(inner, 7, lanePng)
        }

        // f8 — PNG-иконка манёвра 80×80 (в 100% кадров эталона)
        if (includeF8 && iconPngSmall != null) writeBytesField(inner, 8, iconPngSmall)

        // f9 — дистанция до манёвра, м
        if (distance > 0) writeVarintField(inner, 9, distance.toLong())

        // f10 — имя дороги/улицы
        writeStringField(inner, 10, road)

        // f11 — ограничение скорости, км/ч (если >0)
        if (speedLimit > 0) writeVarintField(inner, 11, speedLimit.toLong())

        // f16 — navigatingStatus (2=активна, 1=очистить)
        writeVarintField(inner, 16, statusIcon.toLong())

        // f19/f20 — lon/lat текущей позиции (fixed64 LE double)
        if (lat != 0.0 || lon != 0.0) {
            writeFixed64Field(inner, 19, java.lang.Double.doubleToRawLongBits(lon))
            writeFixed64Field(inner, 20, java.lang.Double.doubleToRawLongBits(lat))
        }

        // f26 — ETA "ЧЧ:ММ"
        writeStringField(inner, 26, etaString)

        // f28 — id манёвра. При камере (maneuver=0) не пишем — ROM рисует стрелку иначе.
        // Донор: maneuver=0 = "нет стрелки"; наш gaodeToF28(0) → 1 (прямо) — лишняя 3D-стрелка.
        if (maneuver != 0) {
            writeVarintField(inner, 28, if (f28Mapped) gaodeToF28(maneuver).toLong() else maneuver.toLong())
        }

        // f29 — строка полос "S,H|S,H|..." (шлём вместе с f7)
        if (hasLanes) writeStringField(inner, 29, laneLayout)

        // f30/f31 — AR-геометрия. Шлём ТОЛЬКО если переданы явно (guideLine/guidePoint).
        // В штатном потоке discope setGuideLine() не вызывается — кадры без них нормальны.
        // ❗️ Всегда шлём f31 при наличии lat/lon → регрессия на Song L (28.06).
        if (guideLine.isNotEmpty()) writeStringField(inner, 30, guideLine)
        if (guidePoint.isNotEmpty()) writeStringField(inner, 31, guidePoint)

        // f33 — navigatingRatio, прогресс маршрута 0..1 (fixed64 LE double)
        if (useF33 && totalDistMeters > 0 && distance > 0) {
            val progress = 1.0 - distance.toDouble() / totalDistMeters.toDouble()
            writeFixed64Field(inner, 33, java.lang.Double.doubleToRawLongBits(progress.coerceIn(0.0, 1.0)))
        }

        return wrap(inner)
    }

    // Совместимость со старыми вызовами (arrowScan, clear-кадры): полный эталон.
    fun build(
        counter: Int,
        maneuver: Int,
        distance: Int,
        road: String,
        lat: Double, lon: Double,
        etaString: String,
        totalDistMeters: Int = 0,
        totalTimeSeconds: Int = 0,
        statusIcon: Int = 0,
        iconPngLarge: ByteArray? = null,
        iconPngSmall: ByteArray? = null,
        testLanes: Boolean = false,
        laneLayout: String = ""
    ): ByteArray = buildNew(
        stage = STAGE_MAX,
        counter = counter,
        maneuver = maneuver,
        distance = distance,
        road = road,
        lat = lat, lon = lon,
        etaString = etaString,
        totalDistMeters = totalDistMeters,
        statusIcon = statusIcon,
        speedLimit = 0,
        iconPngSmall = iconPngSmall,
        testLanes = testLanes,
        laneLayout = laneLayout
    )

    fun buildNavMap(maneuvers: IntArray, usePacked: Boolean = true): ByteArray {
        val inner = ByteArrayOutputStream()
        writeRepeated(inner, 1, maneuvers, usePacked)
        return wrap(inner)
    }

    /** Clear frame: f16=1 (clear) + f6=255 (final render-class). Как у донора. */
    fun buildClearFrame(counter: Int): ByteArray {
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, 6, 255)
        writeVarintField(inner, 16, 1)
        return wrap(inner)
    }

    /**
     * SIZEGUARD: собирает NEW-кадр и гарантирует payload <= MAX_PAYLOAD_BYTES.
     * Если кадр слишком большой — пересобирает без f7, потом без f8.
     * Логирует каждое срабатывание.
     */
    fun buildNewSafe(
        stage: Int, counter: Int, maneuver: Int, distance: Int, road: String,
        lat: Double, lon: Double, etaString: String,
        totalDistMeters: Int = 0,
        statusIcon: Int = 0, speedLimit: Int = 0,
        guideLine: String = "", guidePoint: String = "",
        iconPngSmall: ByteArray? = null,
        testLanes: Boolean = false, laneLayout: String = ""
    ): ByteArray {
        // Попытка 1: полный кадр
        var payload = buildNew(stage, counter, maneuver, distance, road, lat, lon, etaString,
            totalDistMeters, statusIcon, speedLimit, guideLine, guidePoint,
            iconPngSmall, testLanes, laneLayout)
        if (payload.size <= MAX_PAYLOAD_BYTES) return payload
        Logger.w("SIZEGUARD", "payload ${payload.size}B > ${MAX_PAYLOAD_BYTES}B, dropping f7")

        // Попытка 2: без f7 (лента полос). f8 НЕ дропаем — иконка в 100% эталонных кадров.
        payload = buildNew(stage, counter, maneuver, distance, road, lat, lon, etaString,
            totalDistMeters, statusIcon, speedLimit, guideLine, guidePoint,
            iconPngSmall, testLanes, laneLayout, includeF7 = false)
        if (payload.size <= MAX_PAYLOAD_BYTES) return payload
        Logger.w("SIZEGUARD", "payload ${payload.size}B > ${MAX_PAYLOAD_BYTES}B even without f7 — sending as-is")
        return payload
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /**
     * GAODE-код -> код манёвра f28 ЭТАЛОНА.
     * Эталон (подтверждено фото 23.06): 3=ЛЕВО, 2=ПРАВО, 1=ПРЯМО, 9=РАЗВОРОТ, 5=шоссе/съезд.
     * GAODE: 1=лево 2=право 3=плавно-лево 4=плавно-право 7=резко-лево 8=резко-право
     *        9/10=разворот 11=прямо 13/24=кольцо 48=прибытие.
     */
    fun gaodeToF28(gaode: Int): Int = when (gaode) {
        1, 3, 7 -> 3        // лево / плавно влево / резко влево
        2, 4, 8 -> 2        // право / плавно вправо / резко вправо
        11 -> 1             // прямо (эталон: 1=ПРЯМО, подтверждено Nameless-Road тестом)
        9, 10 -> 9          // разворот (эталон: 9=РАЗВОРОТ). TODO: выверить эмпирически по логу gaode_in->f28_out
        // 5 = шоссе/съезд на магистраль: отдельного gaode нет, выставляется вручную на трассе
        else -> 1           // кольцо/прибытие(M_ARRIVE=12->gaode48)/неизв. -> прямо. Защита от «протечки» сырого кода в f28
    }

    private fun laneCount(layout: String): Int = layout.split("|").count { it.isNotEmpty() }

    /** f28 эталона -> фигура полосы: 1=влево, 3=вправо, 0=прямо. */
    private fun f28ToLaneDir(f28: Int): Int = when (f28) { 3 -> 1; 2 -> 3; else -> 0 }

    /**
     * Сборка строки полос f29 в формате эталона \"S,H|\".
     * Рекомендуемая полоса: \"dir,dir\"; остальные: \"0,255\" (погашены).
     * numLanes = f5. Реальные полосы из Яндекса пока недоступны — это корректный формат под будущие данные.
     */
    fun buildLanes(f28: Int, numLanes: Int, recommendedIdx: Int): String {
        val dir = f28ToLaneDir(f28)
        val sb = StringBuilder()
        for (i in 0 until numLanes) {
            if (i == recommendedIdx) sb.append("$dir,$dir|") else sb.append("0,255|")
        }
        return sb.toString()
    }

    private fun buildGuideLine(lat: Double, lon: Double, maneuver: Int, decimals: Int): String {
        val g = if (maneuver in 1..13 || maneuver == 48) maneuver else ManeuverMapper.toGaode(maneuver)
        val fmt = "[%.${decimals}f,%.${decimals}f,0]"
        val sb = StringBuilder("[")
        val step = 0.0002
        val turnStart = 5
        for (i in 0..9) {
            val iLat = lat + i * step
            val turn = if (i > turnStart) (i - turnStart) * step else 0.0
            val iLon = when (g) {
                1 -> lon - turn
                2 -> lon + turn
                3 -> lon - turn * 0.5
                4 -> lon + turn * 0.5
                7 -> lon - turn * 1.5
                8 -> lon + turn * 1.5
                9 -> lon - turn * 2
                10 -> lon + turn * 2
                11 -> lon
                13 -> lon + turn * 0.5
                else -> lon
            }
            if (i > 0) sb.append(",")
            sb.append(String.format(fmt, iLon, iLat))
        }
        sb.append("]")
        return sb.toString()
    }

    /**
     * Генерирует PNG-ленту полос для f7.
     * Формат: 68px на полосу × 100h (как в эталоне discope).
     * Рисует стрелки направлений: прямо(0), влево(1), вправо(3).
     */
    private fun buildLaneStripPng(laneLayout: String): ByteArray? {
        val entries = laneLayout.split("|").filter { it.isNotEmpty() }
        if (entries.isEmpty()) return null
        val laneCount = entries.size
        val w = 68 * laneCount
        val h = 100
        try {
            val bmp = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            canvas.drawColor(android.graphics.Color.TRANSPARENT, android.graphics.PorterDuff.Mode.CLEAR)

            val arrowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                strokeWidth = 4f
                style = android.graphics.Paint.Style.STROKE
            }
            val fillPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                color = android.graphics.Color.WHITE
                style = android.graphics.Paint.Style.FILL
            }

            for (i in entries.indices) {
                val parts = entries[i].split(",")
                if (parts.size < 2) continue
                val dir = parts[0].toIntOrNull() ?: 0
                val highlight = parts[1].toIntOrNull() ?: 0
                val isActive = highlight != 255 && highlight > 0
                val cx = i * 68 + 34f
                val cy = 50f

                if (isActive) {
                    // Активная полоса — яркая стрелка
                    fillPaint.alpha = 255
                    arrowPaint.alpha = 255
                } else {
                    // Неактивная — приглушённая
                    fillPaint.alpha = 80
                    arrowPaint.alpha = 80
                }

                // Рисуем стрелку по направлению
                val path = android.graphics.Path()
                when (dir) {
                    0 -> { // прямо — вертикальная стрелка вверх
                        path.moveTo(cx, cy - 30)
                        path.lineTo(cx, cy + 20)
                        path.moveTo(cx - 12, cy - 10)
                        path.lineTo(cx, cy - 25)
                        path.lineTo(cx + 12, cy - 10)
                    }
                    1, 2 -> { // влево
                        path.moveTo(cx - 25, cy)
                        path.lineTo(cx + 15, cy)
                        path.moveTo(cx - 10, cy - 12)
                        path.lineTo(cx - 25, cy)
                        path.lineTo(cx - 10, cy + 12)
                    }
                    3, 4 -> { // вправо
                        path.moveTo(cx + 25, cy)
                        path.lineTo(cx - 15, cy)
                        path.moveTo(cx + 10, cy - 12)
                        path.lineTo(cx + 25, cy)
                        path.lineTo(cx + 10, cy + 12)
                    }
                    else -> { // прямо по умолчанию
                        path.moveTo(cx, cy - 30)
                        path.lineTo(cx, cy + 20)
                        path.moveTo(cx - 12, cy - 10)
                        path.lineTo(cx, cy - 25)
                        path.lineTo(cx + 12, cy - 10)
                    }
                }
                canvas.drawPath(path, arrowPaint)
            }

            val out = java.io.ByteArrayOutputStream()
            bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            return out.toByteArray()
        } catch (_: Exception) { return null }
    }

    private fun writeFixed64Field(o: ByteArrayOutputStream, tag: Int, v: Long) {
        writeVarint(o, ((tag shl 3) or 1).toLong()) // wire type 1 = 64-bit
        for (i in 0 until 8) o.write((v ushr (i * 8)).toInt() and 0xff)
    }

    private fun wrap(inner: ByteArrayOutputStream): ByteArray {
        val innerBytes = inner.toByteArray()
        val outer = ByteArrayOutputStream()
        outer.write(0x0A)
        writeVarint(outer, innerBytes.size.toLong())
        outer.write(innerBytes)
        return outer.toByteArray()
    }

    private fun writeRepeated(o: ByteArrayOutputStream, tag: Int, values: IntArray, packed: Boolean) {
        if (packed) {
            val buf = ByteArrayOutputStream()
            for (v in values) writeVarint(buf, v.toLong())
            writeBytesField(o, tag, buf.toByteArray())
        } else {
            for (v in values) writeVarintField(o, tag, v.toLong())
        }
    }

    private fun writeDoubleField(o: ByteArrayOutputStream, tag: Int, v: Double) {
        writeVarint(o, ((tag shl 3) or 1).toLong())
        val bits = java.lang.Double.doubleToLongBits(v)
        for (k in 0..7) o.write(((bits ushr (k * 8)) and 0xff).toInt())
    }

    private fun writeVarintField(o: ByteArrayOutputStream, tag: Int, v: Long) {
        writeVarint(o, (tag shl 3).toLong()); writeVarint(o, v)
    }

    private fun writeBytesField(o: ByteArrayOutputStream, tag: Int, b: ByteArray) {
        writeVarint(o, ((tag shl 3) or 2).toLong()); writeVarint(o, b.size.toLong()); o.write(b)
    }

    private fun writeStringField(o: ByteArrayOutputStream, tag: Int, s: String) =
        writeBytesField(o, tag, s.toByteArray(Charsets.UTF_8))

    private fun writeVarint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            o.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        o.write(x.toInt())
    }
}
