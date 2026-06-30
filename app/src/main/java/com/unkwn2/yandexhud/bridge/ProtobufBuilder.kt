package com.unkwn2.yandexhud.bridge

import com.unkwn2.yandexhud.notif.ManeuverMapper
import com.unkwn2.yandexhud.util.Logger
import java.io.ByteArrayOutputStream

/**
 * Сборщик HudRoadInfoNotifyStruct.
 *
 * Два режима:
 *  - buildOld(...)  — РАБОЧИЙ метод от 18 июня (b18d422). НЕ ТРОГАТЬ — контрольная кнопка OLD.
 *  - buildNew(stage) — НОВЫЙ метод. Формат эталона (нативный AMap/GAODE-поток на topic
 *                     0x4010a00018001) теперь ВЫВЕРЕН по логу 23.06 + фото. Стадии оставлены
 *                     для совместимости, но значения полей приведены к эталону.
 *                     LoopRunner шлёт стабильно stage=STAGE_MAX (полный корректный кадр).
 *
 * Ключевые выверенные факты (лог 3268 кадров + фото с таймингом):
 *  - f28 (манёвр): 3=ЛЕВО, 2=ПРАВО, 1=ПРЯМО, 5=ШОССЕ/съезд, 9=РАЗВОРОТ.
 *    (НЕ generic-GAODE! У GAODE лево=1 — это и был баг: левые повороты уезжали как «прямо».)
 *  - f29 (полосы): "S,H|" на полосу, слева направо; f5 = число полос (= число записей).
 *    S=фигура (0 прямо,1/2 влево,3/4 вправо); H=255 полоса погашена, иначе направление (0/1/3).
 *  - f19/f20/f33 — double (fixed64, wire type 1), НЕ varint.
 *  - f22=50, f24="[]", f25=submessage-константа — присутствуют в 100% кадров эталона.
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
            writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver, 6))  // f30 guideLine %.6f
            writeStringField(inner, 31, "$lon,$lat,0")             // f31 guidePoint строка
            writeDoubleField(inner, 19, lon)                       // f19 longitude
            writeDoubleField(inner, 20, lat)                       // f20 latitude
        }
        return wrap(inner)
    }

    // ─────────────────────────────────────────────────────────────────────
    // NEW — выверенный по эталону кадр. stage 0 ≈ OLD, stage 6 = полный эталон.
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
        curSpeed: Int = 45,            // f12 — текущая скорость (км/ч), deriveF12 default=45
        cameraDistance: Int = 0,       // дистанция до камеры/POI (f18), независима от f9
        dangerSign: Int = 0,           // f23 — знак опасности
        arriveText: String = "",
        iconPngLarge: ByteArray? = null,
        iconPngSmall: ByteArray? = null,
        testLanes: Boolean = false,
        laneLayout: String = "",
        includeF7: Boolean = true,     // SIZEGUARD: можно отключить
        includeF8: Boolean = true      // SIZEGUARD: можно отключить
    ): ByteArray {
        val useF6     = stage >= 1
        val useF33    = stage >= 3
        val f2Const   = stage >= 4
        val f28Mapped = stage >= 5
        val full      = stage >= 6

        val inner = ByteArrayOutputStream()

        // f2 — константа 2 (эталон) / счётчик (ранние стадии)
        writeVarintField(inner, 2, if (f2Const) 2L else counter.toLong())

        // f3/f4 — остаток до цели: дистанция (м) и время (с)
        if (full) {
            if (totalDistMeters > 0)  writeVarintField(inner, 3, totalDistMeters.toLong())
            if (totalTimeSeconds > 0) writeVarintField(inner, 4, totalTimeSeconds.toLong())
        }

        // f5 — число полос (= число записей \"S,H|\" в f29)
        if (testLanes && laneLayout.isNotEmpty())
            writeVarintField(inner, 5, laneCount(laneLayout).toLong())

        // f6 — render-class манёвра; f7 — большая схема перекрёстка (только если реально есть картинка)
        val f6val = gaodeToF6(maneuver)
        if (useF6) {
            writeVarintField(inner, 6, f6val.toLong())
            if (includeF7 && iconPngLarge != null && f6val == 7) writeBytesField(inner, 7, iconPngLarge)
        }

        // f8 — PNG-стрелка манёвра (как в OLD; в 100% кадров эталона)
        if (includeF8 && iconPngSmall != null) writeBytesField(inner, 8, iconPngSmall)

        if (distance > 0) writeVarintField(inner, 9, distance.toLong())
        writeStringField(inner, 10, road)

        // f11 — speedLimit, f12 — текущая скорость (deriveF12, default=45)
        if (speedLimit > 0) writeVarintField(inner, 11, speedLimit.toLong())
        writeVarintField(inner, 12, curSpeed.coerceIn(0, 200).toLong())

        writeVarintField(inner, 16, statusIcon.toLong())

        // f17 — флаг блока камеры/POI (=1), f18 — дистанция до камеры в метрах.
        // ПОДТВЕРЖДЕНО логом: f18 НЕЗАВИСИМА от f9 (свой отсчёт, 2015/3268 кадров).
        // Шлём ТОЛЬКО при реальных данных от Яндекса (cameraDistance > 0) — иначе будет фантомная камера.
        if (cameraDistance > 0) {
            writeVarintField(inner, 17, 1L)
            writeVarintField(inner, 18, cameraDistance.toLong())
        }

        if (lat != 0.0 || lon != 0.0) {
            writeDoubleField(inner, 19, lon)   // double, НЕ varint
            writeDoubleField(inner, 20, lat)   // double, НЕ varint
        }

        // f21 — deriveF21(), default=41
        writeVarintField(inner, 21, 41L)

        // f22=50, f23=dangerSign, f24 — константы эталона (порядок как в v88)
        if (full) {
            writeVarintField(inner, 22, 50L)
            if (dangerSign > 0) writeVarintField(inner, 23, dangerSign.toLong())
            writeStringField(inner, 24, "[]")
        } else {
            if (dangerSign > 0) writeVarintField(inner, 23, dangerSign.toLong())
            if (speedLimit > 0) writeVarintField(inner, 24, speedLimit.toLong())
        }

        // f25 — строка (в v88 это строка, НЕ submessage)
        if (full) writeStringField(inner, 25, "[]")

        writeStringField(inner, 26, etaString)

        if (!full && arriveText.isNotEmpty()) writeStringField(inner, 27, arriveText)

        // f28 — манёвр в КОДАХ ЭТАЛОНА (3=лево,2=право,1=прямо,9=разворот,5=шоссе)
        writeVarintField(inner, 28, if (f28Mapped) gaodeToF28(maneuver).toLong() else maneuver.toLong())

        if (testLanes && laneLayout.isNotEmpty()) writeStringField(inner, 29, laneLayout)

        if (lat != 0.0 || lon != 0.0) {
            writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver, if (full) 7 else 6))
            // f31 — submessage в full (эталон app-debug88), строка-точка в non-full
            if (full) writeF31Const(inner) else writeStringField(inner, 31, "$lon,$lat,0")
        }

        // f33 — прогресс маршрута (double)
        if (useF33 && totalDistMeters > 0 && distance > 0) {
            val progress = 1.0 - distance.toDouble() / totalDistMeters.toDouble()
            writeDoubleField(inner, 33, progress.coerceIn(0.0, 1.0))
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

    /**
     * SIZEGUARD: собирает NEW-кадр и гарантирует payload <= MAX_PAYLOAD_BYTES.
     * Если кадр слишком большой — пересобирает без f7, потом без f8.
     * Логирует каждое срабатывание.
     */
    fun buildNewSafe(
        stage: Int, counter: Int, maneuver: Int, distance: Int, road: String,
        lat: Double, lon: Double, etaString: String,
        totalDistMeters: Int = 0, totalTimeSeconds: Int = 0,
        statusIcon: Int = 0, speedLimit: Int = 0, curSpeed: Int = 45,
        cameraDistance: Int = 0, dangerSign: Int = 0,
        arriveText: String = "",
        iconPngLarge: ByteArray? = null, iconPngSmall: ByteArray? = null,
        testLanes: Boolean = false, laneLayout: String = ""
    ): ByteArray {
        // Попытка 1: полный кадр
        var payload = buildNew(stage, counter, maneuver, distance, road, lat, lon, etaString,
            totalDistMeters, totalTimeSeconds, statusIcon, speedLimit, curSpeed, cameraDistance,
            dangerSign, arriveText, iconPngLarge, iconPngSmall, testLanes, laneLayout)
        if (payload.size <= MAX_PAYLOAD_BYTES) return payload
        Logger.w("SIZEGUARD", "payload ${payload.size}B > ${MAX_PAYLOAD_BYTES}B, dropping f7")

        // Попытка 2: без f7 (большая схема перекрёстка)
        payload = buildNew(stage, counter, maneuver, distance, road, lat, lon, etaString,
            totalDistMeters, totalTimeSeconds, statusIcon, speedLimit, curSpeed, cameraDistance,
            dangerSign, arriveText, iconPngLarge, iconPngSmall, testLanes, laneLayout, includeF7 = false)
        if (payload.size <= MAX_PAYLOAD_BYTES) return payload
        Logger.w("SIZEGUARD", "payload ${payload.size}B > ${MAX_PAYLOAD_BYTES}B, dropping f8")

        // Попытка 3: без f7 и без f8
        payload = buildNew(stage, counter, maneuver, distance, road, lat, lon, etaString,
            totalDistMeters, totalTimeSeconds, statusIcon, speedLimit, curSpeed, cameraDistance,
            dangerSign, arriveText, null, null, testLanes, laneLayout, includeF7 = false, includeF8 = false)
        Logger.w("SIZEGUARD", "final payload ${payload.size}B (no f7, no f8)")
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

    /** GAODE-код -> f6 render-class эталона (через f28): 6=шоссе, 7=поворот+схема(лево), 8=право/прямо, 9=разворот. */
    private fun gaodeToF6(gaode: Int): Int = f28ToF6(gaodeToF28(gaode))

    private fun f28ToF6(f28: Int): Int = when (f28) {
        3 -> 7   // лево -> поворот со схемой перекрёстка
        5 -> 6   // шоссе/съезд
        9 -> 9   // разворот
        else -> 8 // право / прямо / обычный
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

    /** f31 — submessage-константа из рабочей v88 (classes6.dex): ASCII-хардкод "16.41399"+"94444445". */
    private fun writeF31Const(o: ByteArrayOutputStream) {
        val buf = ByteArrayOutputStream()
        writeVarintField(buf, 6, 4123383220256257585L)   // = "16.41399" LE
        writeVarintField(buf, 6, 3833746581617914937L)   // = "94444445" LE
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
