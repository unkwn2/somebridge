package com.unkwn2.yandexhud.bridge

import java.io.ByteArrayOutputStream

object ProtobufBuilder {
    fun build(
        counter: Int,
        maneuver: Int,
        distance: Int,
        road: String,
        lat: Double, lon: Double,
        eta: Int,
        iconPng: ByteArray = ByteArray(0)
    ): ByteArray {
        val inner = ByteArrayOutputStream()
        writeVarintField(inner, 2, counter.toLong())
        if (iconPng.isNotEmpty()) writeBytesField(inner, 8, iconPng)
        writeVarintField(inner, 9, distance.toLong())
        writeStringField(inner, 10, road)
        writeVarintField(inner, 16, 2L)
        writeFixed64Field(inner, 19, java.lang.Double.doubleToLongBits(lon))
        writeFixed64Field(inner, 20, java.lang.Double.doubleToLongBits(lat))
        writeVarintField(inner, 26, eta.toLong())
        writeVarintField(inner, 28, maneuver.toLong())
        writeVarintField(inner, 30, 1L)
        writeVarintField(inner, 31, 0L)
        val innerBytes = inner.toByteArray()

        val outer = ByteArrayOutputStream()
        writeBytesField(outer, 1, innerBytes)
        return outer.toByteArray()
    }

    private fun writeVarintField(o: ByteArrayOutputStream, tag: Int, v: Long) {
        writeVarint(o, (tag shl 3).toLong()); writeVarint(o, v)
    }

    private fun writeBytesField(o: ByteArrayOutputStream, tag: Int, b: ByteArray) {
        writeVarint(o, ((tag shl 3) or 2).toLong()); writeVarint(o, b.size.toLong()); o.write(b)
    }

    private fun writeStringField(o: ByteArrayOutputStream, tag: Int, s: String) =
        writeBytesField(o, tag, s.toByteArray(Charsets.UTF_8))

    private fun writeFixed64Field(o: ByteArrayOutputStream, tag: Int, v: Long) {
        writeVarint(o, ((tag shl 3) or 1).toLong())
        for (k in 0..7) o.write(((v ushr (k * 8)) and 0xff).toInt())
    }

    private fun writeVarint(o: ByteArrayOutputStream, v: Long) {
        var x = v
        while (x and 0x7FL.inv() != 0L) {
            o.write(((x and 0x7F) or 0x80).toInt())
            x = x ushr 7
        }
        o.write(x.toInt())
    }
}
