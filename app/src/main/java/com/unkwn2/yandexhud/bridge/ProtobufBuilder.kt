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
        etaString: String
    ): ByteArray {
        val mTag = MANEUVER_TAGS[maneuverTagIdx]
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        writeVarintField(inner, mTag, maneuver.toLong())
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
        val sb = StringBuilder("[")
        val step = 0.0002
        val turnStart = 5
        for (i in 0..9) {
            val iLat = lat + i * step
            val turn = if (i > turnStart) (i - turnStart) * step else 0.0
            val iLon = when (maneuver) {
                2 -> lon - turn
                3 -> lon + turn
                else -> lon
            }
            if (i > 0) sb.append(",")
            sb.append(String.format("[%.6f,%.6f,0]", iLon, iLat))
        }
        sb.append("]")
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
