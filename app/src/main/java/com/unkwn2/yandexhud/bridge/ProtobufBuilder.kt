package com.unkwn2.yandexhud.bridge

import java.io.ByteArrayOutputStream

object ProtobufBuilder {

    private const val MANEUVER_FIELD = 28

    fun build(
        counter: Int,
        maneuver: Int,
        distance: Int,
        road: String,
        lat: Double, lon: Double,
        etaString: String
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, 9, distance.toLong())
        writeStringField(inner, 10, road)
        writeVarintField(inner, 16, 2L)
        writeDoubleField(inner, 19, lon)
        writeDoubleField(inner, 20, lat)
        writeStringField(inner, 26, etaString)
        writeVarintField(inner, MANEUVER_FIELD, maneuver.toLong())
        writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver))
        writeStringField(inner, 31, "$lon,$lat,0")
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x0A)
        writeVarint(outer, innerBytes.size.toLong())
        outer.write(innerBytes)
        return outer.toByteArray()
    }

    fun buildAlt(
        counter: Int,
        maneuver: Int,
        distance: Int,
        road: String,
        lat: Double, lon: Double,
        etaString: String
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, 5, maneuver.toLong())
        writeVarintField(inner, 9, distance.toLong())
        writeStringField(inner, 10, road)
        writeVarintField(inner, 16, 2L)
        writeDoubleField(inner, 19, lon)
        writeDoubleField(inner, 20, lat)
        writeStringField(inner, 26, etaString)
        writeStringField(inner, 30, buildGuideLine(lat, lon, maneuver))
        writeStringField(inner, 31, "$lon,$lat,0")
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        outer.write(0x0A)
        writeVarint(outer, innerBytes.size.toLong())
        outer.write(innerBytes)
        return outer.toByteArray()
    }

    private fun buildGuideLine(lat: Double, lon: Double, maneuver: Int): String {
        val sb = StringBuilder()
        val k = 3
        var d = 0.0
        val step = 0.0005
        val turn = when (maneuver) {
            2 -> -0.002
            3 -> 0.002
            else -> 0.0
        }
        for (i in 0..k) {
            if (i > 0) sb.append(",")
            sb.append(String.format("%.6f,%.6f,0", lat + d * step, lon + d * step + turn * d / k))
            d += 1.0
        }
        return sb.toString()
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
