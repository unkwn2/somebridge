package com.unkwn2.yandexhud.bridge

import java.io.ByteArrayOutputStream

object ProtobufBuilder {

    private val MANEUVER_TAGS = intArrayOf(28, 5, 6)

    fun build(
        counter: Int,
        maneuver: Int,
        maneuverTagIdx: Int,
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
        usePacked: Boolean = true
    ): ByteArray {
        val mTag = MANEUVER_TAGS[maneuverTagIdx]
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, 3, totalDistMeters.toLong())
        writeVarintField(inner, 4, distance.toLong())       // distances_2_intersection (meters + unlocks icon)
        writeStringField(inner, 5, road)                     // next_road_name (text under meters)
        if (testLanes) {
            writeRepeated(inner, 7, intArrayOf(1, 2, 2, 1), usePacked)
        }
        writeVarintField(inner, mTag, maneuver.toLong())
        if (statusIcon > 0) writeVarintField(inner, 11, statusIcon.toLong())
        writeVarintField(inner, 16, 2L)
        writeDoubleField(inner, 19, lon)
        writeDoubleField(inner, 20, lat)
        if (arriveText.isNotEmpty()) writeStringField(inner, 25, arriveText)
        writeStringField(inner, 26, etaString)
        writeStringField(inner, 27, "${totalTimeSeconds / 60} мин")
        writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver))
        writeStringField(inner, 31, "$lon,$lat,0")
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x0A)
        writeVarint(outer, innerBytes.size.toLong())
        outer.write(innerBytes)
        return outer.toByteArray()
    }

    fun buildNavMap(maneuvers: IntArray, usePacked: Boolean = true): ByteArray {
        val inner = ByteArrayOutputStream()
        writeRepeated(inner, 1, maneuvers, usePacked)
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x0A)
        writeVarint(outer, innerBytes.size.toLong())
        outer.write(innerBytes)
        return outer.toByteArray()
    }

    private fun buildGuideLine(lat: Double, lon: Double, maneuver: Int): String {
        val sb = StringBuilder("[")
        val step = 0.0002
        val turnStart = 5
        for (i in 0..9) {
            val iLat = lat + i * step
            val turn = if (i > turnStart) (i - turnStart) * step else 0.0
            val iLon = when (maneuver) {
                1 -> lon - turn
                2 -> lon + turn
                7 -> lon - turn * 1.5
                8 -> lon + turn * 1.5
                9 -> lon - turn * 2
                10 -> lon + turn * 2
                13 -> lon + turn * 0.5
                else -> lon
            }
            if (i > 0) sb.append(",")
            sb.append(String.format("[%.6f,%.6f,0]", iLon, iLat))
        }
        sb.append("]")
        return sb.toString()
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
