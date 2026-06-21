package com.unkwn2.yandexhud.bridge

import java.io.ByteArrayOutputStream

object ProtobufBuilder {

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
    ): ByteArray {
        val inner = ByteArrayOutputStream()

        writeVarintField(inner, 2, 2L)
        if (totalDistMeters > 0) writeVarintField(inner, 3, totalDistMeters.toLong())
        if (totalTimeSeconds > 0) writeVarintField(inner, 4, totalTimeSeconds.toLong())
        if (testLanes && laneLayout.isNotEmpty()) {
            writeVarintField(inner, 5, laneLayout.split(",").size.toLong())
        }
        writeVarintField(inner, 6, maneuverToF6(maneuver).toLong())
        if (iconPngLarge != null) writeBytesField(inner, 7, iconPngLarge)
        if (iconPngSmall != null) writeBytesField(inner, 8, iconPngSmall)
        if (distance > 0) writeVarintField(inner, 9, distance.toLong())
        writeStringField(inner, 10, road)
        writeVarintField(inner, 11, 50L)
        // TODO f12: validate against sniffer on real turns
        val f12Val = deriveF12(distance, counter)
        if (f12Val > 0) writeVarintField(inner, 12, f12Val.toLong())
        writeVarintField(inner, 16, statusIcon.toLong())
        writeVarintField(inner, 17, 1L)
        if (distance > 0) writeVarintField(inner, 18, distance.toLong())
        if (lat != 0.0 || lon != 0.0) {
            writeDoubleField(inner, 19, lon)
            writeDoubleField(inner, 20, lat)
        }
        // TODO f21: validate against sniffer on real turns
        val f21Val = deriveF21(distance, counter)
        if (f21Val > 0) writeVarintField(inner, 21, f21Val.toLong())
        writeVarintField(inner, 22, 50L)
        writeVarintField(inner, 23, 17L)
        writeStringField(inner, 24, "[]")
        if (lat != 0.0 || lon != 0.0) writeF25Const(inner)
        writeStringField(inner, 26, etaString)
        writeVarintField(inner, 28, maneuver.toLong())
        if (testLanes && laneLayout.isNotEmpty()) writeStringField(inner, 29, laneLayout)
        if (lat != 0.0 || lon != 0.0) {
            writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver))
            writeF31Const(inner)
        }
        // if (totalDistMeters > 0 && distance > 0) {
        //     val progress = 1.0 - distance.toDouble() / totalDistMeters.toDouble()
        //     writeDoubleField(inner, 33, progress.coerceIn(0.0, 1.0))
        // }

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

    // TODO f6: validate against sniffer on real LEFT/RIGHT/roundabout turns
    private fun maneuverToF6(m: Int): Int = when (m) {
        1, 2 -> 3
        3, 4 -> 4
        7, 8 -> 5
        9, 10 -> 6
        11 -> 7
        13 -> 8
        24 -> 10
        45 -> 11
        46 -> 12
        47 -> 13
        48 -> 14
        49 -> 15
        else -> 7
    }

    // TODO f12: validate against sniffer on real turns
    private fun deriveF12(distance: Int, counter: Int): Int {
        if (distance <= 0) return 45
        val base = (distance / 10).coerceIn(5, 200)
        return base + (counter % 3)
    }

    // TODO f21: validate against sniffer on real turns
    private fun deriveF21(distance: Int, counter: Int): Int {
        if (distance <= 0) return 41
        val base = (distance / 12).coerceIn(5, 200)
        return base + (counter % 3)
    }

    private fun buildGuideLine(lat: Double, lon: Double, maneuver: Int): String {
        val g = maneuver  // already GAODE
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
            sb.append(String.format("[%.7f,%.7f,0]", iLon, iLat))
        }
        sb.append("]")
        return sb.toString()
    }

    private fun writeF25Const(o: ByteArrayOutputStream) {
        val buf = ByteArrayOutputStream()
        writeFixed64Field(buf, 6, 4122825789335811633L)
        writeFixed64Field(buf, 7, 3689911756189480243L)
        writeBytesField(o, 25, buf.toByteArray())
    }

    private fun writeF31Const(o: ByteArrayOutputStream) {
        val buf = ByteArrayOutputStream()
        writeFixed64Field(buf, 6, 4123383220256257585L)
        writeFixed64Field(buf, 7, 3833746581617914937L)
        writeBytesField(o, 31, buf.toByteArray())
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

    private fun writeFixed64Field(o: ByteArrayOutputStream, tag: Int, v: Long) {
        writeVarint(o, ((tag shl 3) or 1).toLong())
        for (k in 0..7) o.write(((v ushr (k * 8)) and 0xff).toInt())
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
