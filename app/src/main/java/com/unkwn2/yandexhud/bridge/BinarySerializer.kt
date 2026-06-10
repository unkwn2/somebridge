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
        // field at offset 0x24: be_i32 blob1_len at obj+0x38 → 0 (no blob1)
        writeBe32(os, 0)
        // field at offset 0x28: be_i32 blob2_len at obj+0x48 → 0 (no blob2)
        writeBe32(os, 0)

        // field at offset 0x2c: be_i32 distances_2_intersection at obj+0x58
        writeBe32(os, distanceMeters)

        // field at offset 0x30: string next_road_name at obj+0x60
        writeBeString(os, road)

        // Fixed 31-byte (0x1f) block starting after road name
        os.write(0)                            // +0x00 u8 current_max_speed_limit → obj+0x80
        os.write(0)                            // +0x01 u8 current_speed → obj+0x81
        writeBe16(os, 0)                       // +0x02 be_u16 dist_to_speed_zone → obj+0x82
        writeBe16(os, 0)                       // +0x04 be_u16 length_of_speed_limit → obj+0x84
        os.write(0)                            // +0x06 u8 speed_limit → obj+0x86
        os.write(statusIcon.coerceIn(0, 255))  // +0x07 u8 navigating_status → obj+0x87 (ЗНАЧОК)
        os.write(0)                            // +0x08 u8 camera_ahead_status → obj+0x88
        writeBe16(os, 0)                       // +0x09 be_u16 dist_to_camera → obj+0x8a
        writeBeDouble(os, lon)                 // +0x0b be_f64 longitude → obj+0x90
        writeBeDouble(os, lat)                 // +0x13 be_f64 latitude → obj+0x98
        os.write(0)                            // +0x1b u8 vehicle_speed → obj+0xa0
        writeBe16(os, 0)                       // +0x1c be_u16 altitude → obj+0xa2
        os.write(0)                            // +0x1e u8 danger_signs → obj+0xa4

        // tail strings — strict order
        writeBeString(os, "")  // #1 obj+0xa8  POI_information
        writeBeString(os, "")  // #2 obj+0xc8  (sscanf "%lf,%lf" → obj+0xe8,0xf0)
        writeBeString(os, "")  // #3 obj+0x100 reach_the_destination
        writeBeString(os, "")  // #4 obj+0x120
        writeBe16(os, 0)       //    obj+0x140
        writeBeString(os, "")  // #5 obj+0x148
        writeBeString(os, "")  // #6 obj+0x168 (_M_replace)
        writeBeString(os, "")  // #7 obj+0x188 (sscanf "%lf,%lf,%lf" → obj+0x1c0,0x1c8,0x1d0)

        // STOP. remaining ≤ 15 → early return 0 at 0x4e2c04.
        // Do NOT add trailing doubles — that pushes remaining > 15 and takes tail branch.

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
