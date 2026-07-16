package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HudProtobufBuilderTest {

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    /** Minimal protobuf wire reader for assertions (varint/length-delimited/fixed64). */
    private class ProtoReader(private val bytes: ByteArray) {
        var pos = 0
        val fields = mutableMapOf<Int, MutableList<Any>>()

        fun parse(): Map<Int, List<Any>> {
            while (pos < bytes.size) {
                val tag = readVarint().toInt()
                val fieldNo = tag ushr 3
                when (tag and 7) {
                    0 -> fields.getOrPut(fieldNo) { mutableListOf() }.add(readVarint())
                    1 -> fields.getOrPut(fieldNo) { mutableListOf() }.add(readFixed64())
                    2 -> {
                        val len = readVarint().toInt()
                        fields.getOrPut(fieldNo) { mutableListOf() }
                            .add(bytes.copyOfRange(pos, pos + len))
                        pos += len
                    }
                    else -> error("unsupported wire type")
                }
            }
            return fields
        }

        private fun readVarint(): Long {
            var result = 0L; var shift = 0
            while (true) {
                val b = bytes[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return result
                shift += 7
            }
        }

        private fun readFixed64(): Long {
            var v = 0L
            repeat(8) { i -> v = v or ((bytes[pos + i].toLong() and 0xFF) shl (8 * i)) }
            pos += 8
            return v
        }
    }

    private fun unwrap(payload: ByteArray): Map<Int, List<Any>> {
        assertEquals(0x0A, payload[0].toInt())
        var pos = 1; var len = 0L; var shift = 0
        while (true) {
            val b = payload[pos++].toInt() and 0xFF
            len = len or ((b and 0x7F).toLong() shl shift)
            if (b < 0x80) break
            shift += 7
        }
        assertEquals(len.toInt(), payload.size - pos)
        return ProtoReader(payload.copyOfRange(pos, payload.size)).parse()
    }

    @Test fun `golden frame matches reference byte layout`() {
        val payload = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 250, road = "A",
            etaString = "10:10", totalDistMeters = 1000, speedLimit = 60,
            maneuverIconPng = byteArrayOf(0x01), speedSignPng = null,
        )
        assertEquals(
            "0a271002300142010148fa01520141583c800102d2010531303a3130e001028902000000000000e83f",
            payload.hex(),
        )
    }

    @Test fun `golden clear frame bytes`() {
        assertEquals("0a08102a30ff01800101", HudProtobufBuilder.buildClearFrame(42).hex())
    }

    @Test fun `frame fields and order`() {
        val icon = byteArrayOf(1, 2, 3)
        val sign = byteArrayOf(9, 8)
        val payload = HudProtobufBuilder.buildFrame(
            maneuverGaode = 1, distanceMeters = 250, road = "ул. Ленина",
            etaString = "18:40", totalDistMeters = 5000, speedLimit = 60,
            maneuverIconPng = icon, speedSignPng = sign,
        )
        val f = unwrap(payload)
        assertEquals(2L, (f[2]!![0] as Long))
        assertEquals(6L, (f[6]!![0] as Long))           // with speed sign -> render class 6
        assertTrue((f[7]!![0] as ByteArray).contentEquals(sign))
        assertTrue((f[8]!![0] as ByteArray).contentEquals(icon))
        assertEquals(250L, (f[9]!![0] as Long))
        assertEquals("ул. Ленина", String(f[10]!![0] as ByteArray, Charsets.UTF_8))
        assertEquals(60L, (f[11]!![0] as Long))
        assertEquals(2L, (f[16]!![0] as Long))
        assertEquals("18:40", String(f[26]!![0] as ByteArray, Charsets.UTF_8))
        assertEquals(3L, (f[28]!![0] as Long))          // left -> 3
        val progressBits = f[33]!![0] as Long
        val progress = Double.fromBits(progressBits)
        assertEquals(1.0 - 250.0 / 5000.0, progress, 1e-9)
        assertNull(f[3]); assertNull(f[4]); assertNull(f[12]); assertNull(f[17])
    }

    @Test fun `no speed sign means render class 1 and no f7`() {
        val f = unwrap(HudProtobufBuilder.buildFrame(
            maneuverGaode = 2, distanceMeters = 100, road = "",
            etaString = null, totalDistMeters = 0, speedLimit = 0,
            maneuverIconPng = byteArrayOf(1), speedSignPng = null,
        ))
        assertEquals(1L, (f[6]!![0] as Long))
        assertNull(f[7])
        assertNull(f[10])   // empty road omitted
        assertNull(f[11])   // zero speed limit omitted
        assertNull(f[26])   // null eta omitted
    }

    @Test fun `clear frame has render class 255 and f16=1`() {
        val f = unwrap(HudProtobufBuilder.buildClearFrame(3))
        assertEquals(3L, (f[2]!![0] as Long))
        assertEquals(255L, (f[6]!![0] as Long))
        assertEquals(1L, (f[16]!![0] as Long))
        assertNull(f[8]); assertNull(f[9])
    }

    @Test fun `gaode to f28 reference maneuver`() {
        assertEquals(3, HudProtobufBuilder.gaodeToF28(1))    // left
        assertEquals(3, HudProtobufBuilder.gaodeToF28(3))
        assertEquals(3, HudProtobufBuilder.gaodeToF28(7))
        assertEquals(2, HudProtobufBuilder.gaodeToF28(2))    // right
        assertEquals(2, HudProtobufBuilder.gaodeToF28(4))
        assertEquals(2, HudProtobufBuilder.gaodeToF28(8))
        assertEquals(9, HudProtobufBuilder.gaodeToF28(9))    // uturn
        assertEquals(9, HudProtobufBuilder.gaodeToF28(10))
        assertEquals(1, HudProtobufBuilder.gaodeToF28(11))   // straight & everything else
        assertEquals(1, HudProtobufBuilder.gaodeToF28(13))
        assertEquals(1, HudProtobufBuilder.gaodeToF28(0))
    }

    @Test fun `oversize payload drops speed sign but never maneuver icon`() {
        val bigIcon = ByteArray(40_000) { 1 }
        val bigSign = ByteArray(40_000) { 2 }
        val payload = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 100, road = "x",
            etaString = null, totalDistMeters = 0, speedLimit = 60,
            maneuverIconPng = bigIcon, speedSignPng = bigSign,
        )
        assertTrue(payload.size <= HudProtobufBuilder.MAX_PAYLOAD_BYTES)
        val f = unwrap(payload)
        assertNull(f[7])
        assertTrue((f[8]!![0] as ByteArray).contentEquals(bigIcon))
        assertEquals(1L, (f[6]!![0] as Long))   // sign dropped -> render class back to 1
    }

    @Test fun `oversized road text cannot overflow the payload limit`() {
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = 2, distanceMeters = 500, road = "х".repeat(100_000),
            etaString = "12:34", totalDistMeters = 10_000, speedLimit = 60,
            maneuverIconPng = null, speedSignPng = null)
        assertTrue(frame.size <= HudProtobufBuilder.MAX_PAYLOAD_BYTES)
    }

    @Test fun `progress clamped to 0-1`() {
        val f = unwrap(HudProtobufBuilder.buildFrame(
            maneuverGaode = 2, distanceMeters = 9000, road = "",
            etaString = null, totalDistMeters = 5000, speedLimit = 0,
            maneuverIconPng = null, speedSignPng = null,
        ))
        val progress = Double.fromBits(f[33]!![0] as Long)
        assertEquals(0.0, progress, 1e-9)
    }
}
