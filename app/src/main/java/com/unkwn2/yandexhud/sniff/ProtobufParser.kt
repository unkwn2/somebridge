package com.unkwn2.yandexhud.sniff

import java.io.ByteArrayOutputStream

object ProtobufParser {
    private val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    private val JPEG_MAGIC = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())

    data class Field(val tag: Int, val wire: Int, val value: Any) {
        override fun toString(): String = when (value) {
            is Long -> when (wire) {
                1 -> "f$tag(double)=${java.lang.Double.longBitsToDouble(value)}"     // fixed64 -> double (f19/f20/f33)
                5 -> "f$tag(float)=${java.lang.Float.intBitsToFloat(value.toInt())}" // fixed32 -> float
                else -> "f$tag(varint)=$value"
            }
            is ByteArray -> {
                when {
                    isPng(value) -> "f$tag(PNG icon, ${value.size}B)"
                    isJpeg(value) -> "f$tag(JPEG, ${value.size}B)"
                    looksLikeUtf8(value) -> "f$tag(str)=\"${String(value, Charsets.UTF_8)}\""
                    else -> "f$tag(bytes, ${value.size}B): ${value.take(16).joinToString("") { "%02x".format(it) }}…"
                }
            }
            is List<*> -> "f$tag(msg) { ${value.joinToString(" ")} }"
            else -> "f$tag=$value"
        }
    }

    fun parse(data: ByteArray): List<Field> {
        val out = mutableListOf<Field>()
        var p = 0
        while (p < data.size) {
            val (tagWire, np1) = readVarint(data, p) ?: return out
            p = np1
            val tag = (tagWire shr 3).toInt()
            val wire = (tagWire and 0x7).toInt()
            when (wire) {
                0 -> {
                    val (v, np2) = readVarint(data, p) ?: return out
                    out += Field(tag, wire, v); p = np2
                }
                2 -> {
                    val (len, np2) = readVarint(data, p) ?: return out
                    p = np2
                    if (p + len > data.size) return out
                    val bytes = data.copyOfRange(p, (p + len).toInt())
                    p = (p + len).toInt()
                    val value: Any = if (isPng(bytes) || isJpeg(bytes)) bytes
                                     else tryParseNested(bytes) ?: bytes
                    out += Field(tag, wire, value)
                }
                5 -> {
                    if (p + 4 > data.size) return out
                    val v = readInt32LE(data, p); p += 4
                    out += Field(tag, wire, v.toLong())
                }
                1 -> {
                    if (p + 8 > data.size) return out
                    val v = readInt64LE(data, p); p += 8
                    out += Field(tag, wire, v)
                }
                else -> return out
            }
        }
        return out
    }

    fun format(data: ByteArray): String {
        return parse(data).joinToString("\n  ") { it.toString() }
    }

    private fun tryParseNested(bytes: ByteArray): List<Field>? = try {
        val parsed = parse(bytes)
        if (parsed.size >= 2 && parsed.all { it.tag in 1..200 }) parsed else null
    } catch (_: Throwable) { null }

    private fun readVarint(data: ByteArray, offset: Int): Pair<Long, Int>? {
        var result = 0L
        var shift = 0
        var i = offset
        while (i < data.size) {
            val b = data[i].toLong() and 0xff
            result = result or ((b and 0x7f) shl shift)
            i++
            if ((b and 0x80) == 0L) break
            shift += 7
            if (shift >= 70) return null
        }
        return Pair(result, i)
    }

    private fun readInt32LE(d: ByteArray, p: Int): Int =
        (d[p].toInt() and 0xff) or ((d[p + 1].toInt() and 0xff) shl 8) or
        ((d[p + 2].toInt() and 0xff) shl 16) or ((d[p + 3].toInt() and 0xff) shl 24)

    private fun readInt64LE(d: ByteArray, p: Int): Long =
        (d[p].toLong() and 0xff) or ((d[p + 1].toLong() and 0xff) shl 8) or
        ((d[p + 2].toLong() and 0xff) shl 16) or ((d[p + 3].toLong() and 0xff) shl 24) or
        ((d[p + 4].toLong() and 0xff) shl 32) or ((d[p + 5].toLong() and 0xff) shl 40) or
        ((d[p + 6].toLong() and 0xff) shl 48) or ((d[p + 7].toLong() and 0xff) shl 56)

    private fun isPng(b: ByteArray) = b.size >= 8 && b.sliceArray(0..7).contentEquals(PNG_MAGIC)
    private fun isJpeg(b: ByteArray) = b.size >= 3 && b.sliceArray(0..2).contentEquals(JPEG_MAGIC)
    private fun looksLikeUtf8(b: ByteArray): Boolean {
        if (b.size > 200) return false
        return b.all { it.toInt() >= 0x20 || it.toInt() == 0x0A || it.toInt() == 0x09 || it.toInt() == 0x00 }
    }
}
