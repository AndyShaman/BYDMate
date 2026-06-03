package com.bydmate.app.data.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Native light channels on the dev=1023 carve-out (recovered from
 * BYDAutoSettingDevice). Graduated to validated after a write+readback snap on
 * Leopard 3 2026-05-29. Only the carved-out (dev,fid) pairs survive the blanket
 * dev=1023 ban; the rest of dev=1023 stays banned.
 */
class WriteAllowlistCandidateTest {

    private val production = WriteAllowlist.loadProduction { "{}" }

    @Test fun `interior light on candidate survives the dev=1023 ban via carve-out`() {
        val e = production.find("interior_light_on")
        assertNotNull("interior_light_on must be present via carve-out", e)
        assertEquals(1023, e!!.dev)
        assertEquals(1330643002, e.writeFid)
        assertEquals(2, e.valueMin)
        assertEquals(2, e.valueMax)
        assertTrue("interior light is live-validated", e.validated)
    }

    @Test fun `interior light off candidate maps to value 1`() {
        val e = production.find("interior_light_off")
        assertNotNull(e)
        assertEquals(1330643002, e!!.writeFid)
        assertEquals(1, e.valueMin)
    }

    @Test fun `ambient light on candidate survives via carve-out`() {
        val e = production.find("ambient_light_on")
        assertNotNull(e)
        assertEquals(1023, e!!.dev)
        assertEquals(1069547536, e.writeFid)
        assertTrue(e.validated)
    }

    @Test fun `a non-carved dev=1023 fid is still dropped`() {
        val fixture = """
            { "rogue_1023": { "featureId": 1234567, "deviceType": 1023, "value": 1 } }
        """.trimIndent()
        val allowlist = WriteAllowlist.loadProduction { fixture }
        assertNull("non-carved dev=1023 entry must stay banned", allowlist.find("rogue_1023"))
    }

    @Test fun `isBanned exempts carved-out light fids but bans the rest of dev=1023`() {
        assertFalse(WriteAllowlist.isBanned(1023, 1330643002)) // interior light — carved out
        assertFalse(WriteAllowlist.isBanned(1023, 1069547536)) // ambient — carved out
        assertTrue(WriteAllowlist.isBanned(1023, 999999))      // other dev=1023 fid — still banned
        assertTrue(WriteAllowlist.isBanned(1004, 1330643002))  // same fid on banned dev=1004 — banned
        assertFalse(WriteAllowlist.isBanned(1001, 1276219408)) // safe dev — never banned
    }
}
