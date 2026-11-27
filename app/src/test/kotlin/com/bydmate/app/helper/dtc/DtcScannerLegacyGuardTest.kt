package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Verifies that read-only diagnostic jobs (scan, firmware check, tyre read) accept
 * [DtcWire.GUARD_LEGACY_IDLE] and log `guard=legacy:diagnosticsrv` on start, while
 * [LinkPolicy.legacyGuardOk] defaults to false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DtcScannerLegacyGuardTest {

    private class FakeClock : DtcClock {
        var t = 0L
        override fun nowMs() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private class FakeDevice(private val clock: FakeClock) : DtcDevice {
        val queue = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        var respond: (ByteArray) -> List<ByteArray> = { emptyList() }

        override fun register(): String? = null
        override fun unregister() { queue.clear() }
        override fun send(payload: ByteArray): Int {
            sent += payload
            queue += respond(payload)
            return 0
        }
        override fun poll(timeoutMs: Long): ByteArray? {
            if (queue.isNotEmpty()) return queue.removeFirst()
            clock.t += timeoutMs
            return null
        }
        override fun drain() = queue.clear()
    }

    @Before fun clearLogs() {
        ShadowLog.clear()
    }

    private fun startLogs(): List<String> =
        ShadowLog.getLogsForTag("DtcScan").map { it.msg }

    private fun scanRespond(payload: ByteArray): List<ByteArray> {
        val addr = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val h = DtcFixtures.hexAddr(addr)
        val service = payload[4].toInt() and 0xFF
        val sub = payload[5].toInt() and 0xFF
        val frame: ByteArray? = when {
            service == 0x19 && sub == 0x01 -> DtcFixtures.rawOrNull("count-$h.txt")
            service == 0x19 && sub == 0x02 -> DtcFixtures.rawOrNull("list-$h.txt")
            service == 0x22 -> DtcFixtures.idUds(addr)?.let { DtcFixtures.frame(addr, it) }
            else -> null
        }
        return if (frame == null) emptyList() else listOf(frame, frame)
    }

    private fun firmwareRespond(payload: ByteArray): List<ByteArray> {
        val addr = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        val req = DtcFixtures.hex(payload.copyOfRange(4, payload.size))
        val uds = when {
            addr == 0x720 && req == "22F194" -> "62F1944845001201F00056E0"
            addr == 0x720 && req == "22F195" -> "62F1959C4C1A090A01"
            req == "22F194" -> "62F1944B5100120283000001"
            req == "22F195" -> "62F1959E201A051300"
            else -> return emptyList()
        }
        val f = DtcFixtures.frame(addr, hex(uds))
        return listOf(f, f)
    }

    private fun tpmsRespond(payload: ByteArray): List<ByteArray> {
        val req = DtcFixtures.hex(payload.copyOfRange(4, payload.size))
        val uds = when (req) {
            "22F194" -> hex("62F1944845001201F00056E0")
            "1902FF" -> hex("5902FF50000328")
            "223305" -> hex("6233059A4B4AC0000001011C0200")
            "223306" -> hex("6233069A367593000001011C0200")
            "223307" -> hex("6233079A7BB4C2000001011C0200")
            "223308" -> hex("6233089A1561AF000001011C0200")
            else -> return emptyList()
        }
        val f = DtcFixtures.frame(TpmsWire.ADDR, uds)
        return listOf(f, f)
    }

    @Test fun `LinkPolicy legacyGuardOk defaults to false`() {
        assertFalse(LinkPolicy(1_000L, speedGate = false, strictTraffic = false).legacyGuardOk)
        assertTrue(LinkPolicy(1_000L, speedGate = false, strictTraffic = false, legacyGuardOk = true).legacyGuardOk)
    }

    @Test fun `scan with legacy diagnosticsrv guard matches baseline and logs legacy guard`() {
        val baseDevice = FakeDevice(FakeClock()).apply { respond = ::scanRespond }
        val baseScanner = DtcScanner(baseDevice, FakeClock(), { DtcWire.GUARD_BASELINE }, runner = { it.run() })
        baseScanner.start { 1 }
        val baseStatus = baseScanner.status()

        ShadowLog.clear()
        val legacyClock = FakeClock()
        val legacyDevice = FakeDevice(legacyClock).apply { respond = ::scanRespond }
        val legacyScanner = DtcScanner(legacyDevice, legacyClock, { DtcWire.GUARD_LEGACY_IDLE }, runner = { it.run() })
        legacyScanner.start { 1 }
        val legacyStatus = legacyScanner.status()

        assertEquals(DtcWire.STATE_DONE, legacyStatus.state)
        assertEquals(baseStatus.done, legacyStatus.done)
        assertEquals(baseStatus.total, legacyStatus.total)
        assertEquals(baseStatus.reason, legacyStatus.reason)
        assertEquals(baseDevice.sent.map { DtcFixtures.hex(it) }, legacyDevice.sent.map { DtcFixtures.hex(it) })
        assertEquals(baseStatus.results.size, legacyStatus.results.size)
        baseStatus.results.zip(legacyStatus.results).forEach { (b, l) ->
            assertEquals(b.addr, l.addr)
            assertEquals(b.outcome, l.outcome)
            assertArrayEquals(b.countUds, l.countUds)
            assertArrayEquals(b.listUds, l.listUds)
            assertArrayEquals(b.idUds, l.idUds)
        }
        assertTrue(
            startLogs().any { it.startsWith("scan start") && it.contains("guard=legacy:diagnosticsrv") },
        )
    }

    @Test fun `firmware read with legacy diagnosticsrv guard matches baseline and logs legacy guard`() {
        val baseDevice = FakeDevice(FakeClock()).apply { respond = ::firmwareRespond }
        val baseScanner = DtcScanner(baseDevice, FakeClock(), { DtcWire.GUARD_BASELINE }, runner = { it.run() })
        baseScanner.startFirmware { 1 }
        val baseStatus = baseScanner.firmwareStatus()

        ShadowLog.clear()
        val legacyClock = FakeClock()
        val legacyDevice = FakeDevice(legacyClock).apply { respond = ::firmwareRespond }
        val legacyScanner = DtcScanner(legacyDevice, legacyClock, { DtcWire.GUARD_LEGACY_IDLE }, runner = { it.run() })
        legacyScanner.startFirmware { 1 }
        val legacyStatus = legacyScanner.firmwareStatus()

        assertEquals(DtcWire.STATE_DONE, legacyStatus.state)
        assertEquals(baseStatus.done, legacyStatus.done)
        assertEquals(baseStatus.total, legacyStatus.total)
        assertEquals(baseStatus.reason, legacyStatus.reason)
        assertEquals(baseDevice.sent.map { DtcFixtures.hex(it) }, legacyDevice.sent.map { DtcFixtures.hex(it) })
        assertEquals(baseStatus.results.size, legacyStatus.results.size)
        baseStatus.results.zip(legacyStatus.results).forEach { (b, l) ->
            assertEquals(b.addr, l.addr)
            assertEquals(b.outcome, l.outcome)
            assertArrayEquals(b.f194Uds, l.f194Uds)
            assertArrayEquals(b.f195Uds, l.f195Uds)
        }
        assertTrue(
            startLogs().any { it.startsWith("firmware start") && it.contains("guard=legacy:diagnosticsrv") },
        )
    }

    @Test fun `tpms read with legacy diagnosticsrv guard matches baseline and logs legacy guard`() {
        val baseDevice = FakeDevice(FakeClock()).apply { respond = ::tpmsRespond }
        val baseScanner = DtcScanner(baseDevice, FakeClock(), { DtcWire.GUARD_BASELINE }, runner = { it.run() })
        baseScanner.startTpmsRead { 1 }
        val baseStatus = baseScanner.tpmsStatus()

        ShadowLog.clear()
        val legacyClock = FakeClock()
        val legacyDevice = FakeDevice(legacyClock).apply { respond = ::tpmsRespond }
        val legacyScanner = DtcScanner(legacyDevice, legacyClock, { DtcWire.GUARD_LEGACY_IDLE }, runner = { it.run() })
        legacyScanner.startTpmsRead { 1 }
        val legacyStatus = legacyScanner.tpmsStatus()

        assertEquals(DtcWire.STATE_DONE, legacyStatus.state)
        assertEquals(baseStatus.mode, legacyStatus.mode)
        assertEquals(baseStatus.reason, legacyStatus.reason)
        assertArrayEquals(baseStatus.idUds, legacyStatus.idUds)
        assertArrayEquals(baseStatus.dtcBefore, legacyStatus.dtcBefore)
        assertEquals(baseDevice.sent.map { DtcFixtures.hex(it) }, legacyDevice.sent.map { DtcFixtures.hex(it) })
        baseStatus.slots.zip(legacyStatus.slots).forEach { (b, l) ->
            assertEquals(b.did, l.did)
            assertArrayEquals(b.beforeUds, l.beforeUds)
        }
        assertTrue(
            startLogs().any { it.startsWith("tpms read start") && it.contains("guard=legacy:diagnosticsrv") },
        )
    }
}
