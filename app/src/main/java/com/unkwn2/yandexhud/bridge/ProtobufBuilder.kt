package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import java.io.ByteArrayOutputStream

/**
 * Сборщик HudRoadInfoNotifyStruct.
 *
 * Два режима:
 *  - buildOld(...)  — РАБОЧИЙ метод от 18 июня (b18d422). Стрелки на стекле работают.
 *                     Используется кнопкой OLD как контрольный эталон.
 *  - buildNew(stage) — НОВЫЙ метод с ПОСТАДИЙНЫМ перебором. Стартует с рабочего
 *                     базиса (stage 0 ≈ OLD) и на каждой стадии добавляет одну
 *                     группу "новых" полей, которые мы увидели из эталонных кадров amap.
 *                     LoopRunner переключает стадию каждые 5 секунд. Когда стрелка
 *                     пропадает на стекле — поле этой стадии и есть блокер.
 */
object ProtobufBuilder {

    // ── Стадии перебора NEW ───────────────────────────────────────────────
    // Порядок — по убыванию подозрительности (из разбора эталона vs июньского кода).
    const val STAGE_MIN = 0
    const val STAGE_MAX = 6

    fun stageName(stage: Int): String = when (stage) {
        0 -> "0:OLD-base"        // рабочий базис (как 18 июня)
        1 -> "1:+f6cat"          // + f6 категория манёвра (+f7 большая PNG)
        2 -> "2:f31submsg+f25"   // f31 строка-точка -> submessage-константы, точка уходит в f25
        3 -> "3:+f33progress"    // + f33 прогресс маршрута
        4 -> "4:f2const"         // f2 живой счётчик -> константа 2
        5 -> "5:f28mapped"       // f28 сырой gaode -> маппинг в категорию
        6 -> "6:full-NEW"        // остальное: f3/f4,f11,f12,f17,f18,f21,f22=50,f23=17,f24=[],f30 %.7f
        else -> "?$stage"
    }

    // ─────────────────────────────────────────────────────────────────────
    // OLD — рабочий метод от 18 июня (стрелки работают). Не трогать.
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
        writeStringField(inner, 26, etaString)                     // f26 ETA "HH:MM"
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
            writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver, 6))  // f30 guideLine %.6f
            writeStringField(inner, 31, "$lon,$lat,0")             // f31 guidePoint строка
            writeDoubleField(inner, 19, lon)                       // f19 longitude
            writeDoubleField(inner, 20, lat)                       // f20 latitude
        }
        return wrap(inner)
    }

    // ─────────────────────────────────────────────────────────────────────
    // NEW — постадийный перебор. stage 0 ≈ OLD, stage 6 = полный текущий NEW.
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
        totalTimeSeconds: Int = 0,
        statusIcon: Int = 0,
        speedLimit: Int = 0,
        arriveText: String = "",
        iconPngLarge: ByteArray? = null,
        iconPngSmall: ByteArray? = null,
        testLanes: Boolean = false,
        laneLayout: String = ""
    ): ByteArray {
        val useF6     = stage >= 1
        val f31Submsg = stage >= 2
        val useF33    = stage >= 3
        val f2Const   = stage >= 4
        val f28Mapped = stage >= 5
        val full      = stage >= 6

        val inner = ByteArrayOutputStream()

        // f2 — счётчик живости (OLD) или константа 2 (NEW)
        writeVarintField(inner, 2, if (f2Const) 2L else counter.toLong())

        // f3/f4 — тотал переехал сюда из f22/f23 (только полный NEW)
        if (full) {
            if (totalDistMeters > 0)  writeVarintField(inner, 3, totalDistMeters.toLong())
            if (totalTimeSeconds > 0) writeVarintField(inner, 4, totalTimeSeconds.toLong())
        }

        // f5 — счётчик полос
        if (testLanes && laneLayout.isNotEmpty())
            writeVarintField(inner, 5, laneLayout.split(",").size.toLong())

        // f6 — категория манёвра (NEW); f7 — большая PNG когда f6==7
        val f6val = maneuverToF6(maneuver)
        if (useF6) {
            writeVarintField(inner, 6, f6val.toLong())
            if (iconPngLarge != null && f6val == 7) writeBytesField(inner, 7, iconPngLarge)
        }

        // f8 — маленькая PNG-стрелка (есть и в OLD)
        if (iconPngSmall != null) writeBytesField(inner, 8, iconPngSmall)

        if (distance > 0) writeVarintField(inner, 9, distance.toLong())
        writeStringField(inner, 10, road)

        if (full) writeVarintField(inner, 11, 30L)
        if (full) { val v = deriveF12(distance, counter); if (v > 0) writeVarintField(inner, 12, v.toLong()) }

        writeVarintField(inner, 16, statusIcon.toLong())

        if (full) writeVarintField(inner, 17, 1L)
        if (full && distance > 0) writeVarintField(inner, 18, distance.toLong())

        if (lat != 0.0 || lon != 0.0) {
            writeDoubleField(inner, 19, lon)
            writeDoubleField(inner, 20, lat)
        }

        if (full) { val v = deriveF21(distance, counter); if (v > 0) writeVarintField(inner, 21, v.toLong()) }

        // f22/f23 — OLD: тотал; полный NEW: константы 50/17
        if (full) {
            writeVarintField(inner, 22, 50L)
            writeVarintField(inner, 23, 17L)
        } else {
            if (totalDistMeters > 0)  writeVarintField(inner, 22, totalDistMeters.toLong())
            if (totalTimeSeconds > 0) writeVarintField(inner, 23, totalTimeSeconds.toLong())
        }

        // f24 — OLD: speedLimit varint; полный NEW: "[]" строка
        if (full) writeStringField(inner, 24, "[]")
        else if (speedLimit > 0) writeVarintField(inner, 24, speedLimit.toLong())

        // f25 — точка-строка появляется, когда f31 стал submessage (точка переехала из f31)
        if (f31Submsg && (lat != 0.0 || lon != 0.0)) writeStringField(inner, 25, "$lon,$lat")

        writeStringField(inner, 26, etaString)

        // f27 — arriveText, пока не полный NEW (в NEW его убрали)
        if (!full && arriveText.isNotEmpty()) writeStringField(inner, 27, arriveText)

        // f28 — OLD: сырой gaode; NEW: маппинг в категорию
        writeVarintField(inner, 28, if (f28Mapped) maneuverToF28(maneuver).toLong() else maneuver.toLong())

        if (testLanes && laneLayout.isNotEmpty()) writeStringField(inner, 29, laneLayout)

        if (lat != 0.0 || lon != 0.0) {
            // f30 — точность %.6f (OLD) или %.7f (полный NEW)
            writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver, if (full) 7 else 6))
            // f31 — OLD строка-точка ИЛИ submessage-константы (NEW)
            if (f31Submsg) writeF31Const(inner) else writeStringField(inner, 31, "$lon,$lat,0")
        }

        // f33 — прогресс маршрута
        if (useF33 && totalDistMeters > 0 && distance > 0) {
            val progress = 1.0 - distance.toDouble() / totalDistMeters.toDouble()
            val bits = java.lang.Double.doubleToRawLongBits(progress.coerceIn(0.0, 1.0))
            writeVarintField(inner, 33, bits)
        }

        return wrap(inner)
    }

    // Совместимость со старыми вызовами (arrowScan, clear-кадры): полный NEW.
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
        totalTimeSeconds = totalTimeSeconds,
        statusIcon = statusIcon,
        speedLimit = 0,
        arriveText = "",
        iconPngLarge = iconPngLarge,
        iconPngSmall = iconPngSmall,
        testLanes = testLanes,
        laneLayout = laneLayout
    )

    fun buildNavMap(maneuvers: IntArray, usePacked: Boolean = true): ByteArray {
        val inner = ByteArrayOutputStream()
        writeRepeated(inner, 1, maneuvers, usePacked)
        return wrap(inner)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun maneuverToF6(gaode: Int): Int = when (gaode) {
        0 -> 255
        11 -> 7
        48 -> 8
        else -> 9
    }

    private fun maneuverToF28(gaode: Int): Int = when (gaode) {
        0, 11 -> 1
        1, 2, 3, 4, 7, 8, 9, 10 -> 9
        13, 24 -> 20
        48 -> 48
        else -> 1
    }

    private fun deriveF12(distance: Int, counter: Int): Int {
        if (distance <= 0) return 45
        val base = (distance / 10).coerceIn(5, 200)
        return base + (counter % 3)
    }

    private fun deriveF21(distance: Int, counter: Int): Int {
        if (distance <= 0) return 41
        val base = (distance / 12).coerceIn(5, 200)
        return base + (counter % 3)
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

    private fun writeF31Const(o: ByteArrayOutputStream) {
        val buf = ByteArrayOutputStream()
        writeVarintField(buf, 6, 4123383220256257585L)
        writeVarintField(buf, 6, 3833746581617914937L)
        writeBytesField(o, 31, buf.toByteArray())
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
