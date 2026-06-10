package com.unkwn2.yandexhud.bridge

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

object BinarySerializer {

    /**
     * Build CSlHudRoadInfo big-endian binary payload with "bina" prefix.
     *
     * Per firmware fcn.004e27f8 + dispatcher fcn.004e32b0:
     *   "bina" (4B) → parser called with buf+4
     *   parser then skips 0x14 bytes → actual fields start at offset 0x18 from "bina" start
     *
     * We zero-fill the preamble (20 zero bytes after "bina") so that:
     *   - internal fields (obj+0x20 thru obj+0x38) = 0
     *   - blob_len = 0 → no dynamic blob
     *
     * Essential HUD fields for compact block display:
     *   offset 0x2c: BE32 distances_2_intersection (obj+0x58)
     *   offset 0x30: BE32-framed string next_road_name (obj+0x60)
     *   offset +7 of fix-block: u8 navigating_status = icon index (obj+0x87)
     *   offset +6 of fix-block: u8 speed_limit (obj+0x86)
     *
     * The 0x1e (30) byte fixed block starts right after the road name string.
     */
    fun build(
        distanceMeters: Int,
        road: String,
        statusIcon: Int,
        maneuver: Int,
        lat: Double,
        lon: Double
    ): ByteArray {
        val os = ByteArrayOutputStream()

        // "bina" prefix (4 bytes)
        os.write(0x62) // 'b'
        os.write(0x69) // 'i'
        os.write(0x6E) // 'n'
        os.write(0x61) // 'a'

        // 0x14 (20) zero padding → parser skips these
        writePadding(os, 0x14)

        // field at offset 0x14 (from buf+4): internal be_i32 at obj+0x20 → 0
        writeBe32(os, 0)
        // field at offset 0x18: internal be_u16 at obj+0x24 → 0
        writeBe16(os, 0)
        // field at offset 0x1a: internal be_i32 at obj+0x28 → 0
        writeBe32(os, 0)
        // field at offset 0x1e: be_i32 time_to_dest at obj+0x2c → 0
        writeBe32(os, 0)
        // field at offset 0x22: u8 Num_of_lanes at obj+0x30 → 0
        os.write(0)
        // field at offset 0x23: u8 Current_road_level at obj+0x31 → 0
        os.write(0)
        // field at offset 0x24: be_i32 blob_len at obj+0x38 → 0 (no blob)
        writeBe32(os, 0)

        // field at current: be_i32 distances_2_intersection at obj+0x58
        writeBe32(os, distanceMeters)

        // field at current: string next_road_name at obj+0x60
        writeBeString(os, road)

        // Fixed 0x1e (30) byte block starting after road name
        // offset 0x00: u8 current_max_speed_limit at obj+0x80 → 0
        os.write(0)
        // offset 0x01: u8 current_speed at obj+0x81 → 0
        os.write(0)
        // offset 0x02: be_u16 Distance_2_speed_limit_zone at obj+0x82 → 0
        writeBe16(os, 0)
        // offset 0x04: be_u16 length_of_speed_limit at obj+0x84 → 0
        writeBe16(os, 0)
        // offset 0x06: u8 speed_limit at obj+0x86 → 0
        os.write(0)
        // offset 0x07: u8 navigating_status at obj+0x87 → statusIcon
        os.write(statusIcon.coerceIn(0, 255))
        // offset 0x08: u8 camera_ahead_status at obj+0x88 → 0
        os.write(0)
        // offset 0x09: be_u16 the_distance_2_camera at obj+0x8a → 0
        writeBe16(os, 0)
        // offset 0x0b: be_f64 vehicle_coordinates_longitude at obj+0x90
        writeBeDouble(os, lon)
        // offset 0x13: be_f64 vehicle_coordinates_latitude at obj+0x98
        writeBeDouble(os, lat)
        // offset 0x1b: u8 vehicle_speed at obj+0xa0 → 0
        os.write(0)
        // offset 0x1c: be_u16 vehicle_altitude at obj+0xa2 → 0
        writeBe16(os, 0)
        // offset 0x1e: u8 Danger_signs at obj+0xa4 → 0
        os.write(0)

        // String: POI_information at obj+0xa8 → empty
        writeBeString(os, "")
        // String: at obj+0xc8 (parsed sscanf "%lf,%lf" → obj+0xe8, obj+0xf0) → empty
        writeBeString(os, "")
        // String: reach_the_destination at obj+0x100 → empty
        writeBeString(os, "")
        // String: at obj+0x120 → empty
        writeBeString(os, "")
        // be_u16 at obj+0x140 → 0
        writeBe16(os, 0)
        // String: at obj+0x148 → empty
        writeBeString(os, "")
        // String: at obj+0x168 (AJOTP, sscanf "%lf,%lf,%lf" → obj+0x1c0,0x1c8,0x1d0)
        // Provide direction vector as "lon,lat,0" to match guideLine format
        writeBeString(os, "$lon,$lat,0")
        // String: at obj+0x188 (also sscanf "%lf,%lf,%lf" → duplicates AJOTP)
        writeBeString(os, "")

        // Remaining: if ≥ 16 bytes → read be_f64 at obj+0x1d8 and obj+0x1e0
        // We already consumed the data, but we can add two final doubles.
        // Actually these are conditionally read based on remaining bytes.
        // We'll add them for completeness.
        writeBeDouble(os, 0.0)
        writeBeDouble(os, 0.0)

        return os.toByteArray()
    }

    private fun writePadding(os: ByteArrayOutputStream, n: Int) {
        for (i in 0 until n) os.write(0)
    }

    private fun writeBe32(os: ByteArrayOutputStream, v: Int) {
        val buf = ByteBuffer.allocate(4)
        buf.putInt(v)
        for (b in buf.array()) os.write(b.toInt() and 0xff)
    }

    private fun writeBe16(os: ByteArrayOutputStream, v: Int) {
        val buf = ByteBuffer.allocate(2)
        buf.putShort(v.toShort())
        for (b in buf.array()) os.write(b.toInt() and 0xff)
    }

    private fun writeBeDouble(os: ByteArrayOutputStream, v: Double) {
        val buf = ByteBuffer.allocate(8)
        buf.putDouble(v)
        for (b in buf.array()) os.write(b.toInt() and 0xff)
    }

    /**
     * Write a big-endian framed string as expected by fcn.004dacd0:
     *   BE32(len+3)  +  3 bytes padding (0x00)  +  raw UTF-8 data
     *
     * For empty string: BE32(0) only (4 bytes).
     * For non-empty: BE32(data.length + 3) + 3 null bytes + data.
     */
    private fun writeBeString(os: ByteArrayOutputStream, s: String) {
        if (s.isEmpty()) {
            writeBe32(os, 0)
            return
        }
        val data = s.toByteArray(Charsets.UTF_8)
        val frameLen = data.size + 3
        writeBe32(os, frameLen)
        os.write(0); os.write(0); os.write(0) // 3 bytes padding
        os.write(data)
    }
}
