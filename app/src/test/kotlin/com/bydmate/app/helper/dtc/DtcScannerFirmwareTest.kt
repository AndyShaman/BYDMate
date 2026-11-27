package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The firmware job (mode 4), scripted with the answers recorded in `ecu-scan-2026-11-20/scan-all.txt`. */
class DtcScannerFirmwareTest {

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
        override fun send(payload: ByteArray): Int { sent += payload; queue += respond(payload); return 0 }
        override fun poll(timeoutMs: Long): ByteArray? {
            if (queue.isNotEmpty()) return queue.removeFirst()
            clock.t += timeoutMs
            return null
        }
        override fun drain() = queue.clear()
    }

    private class World {
        val clock = FakeClock()
        val device = FakeDevice(clock)
        var gear: () -> Int? = { 1 }
        val tasks = mutableListOf<Runnable>()
        var inline = true
        val scanner by lazy {
            DtcScanner(device, clock, { DtcWire.GUARD_BASELINE }, runner = { if (inline) it.run() else tasks += it }, readSpeed = { 0 })
        }
        fun requests() = device.sent.map { DtcFixtures.hex(it.copyOfRange(4, it.size)) }
        fun requestsTo(addr: Int) =
            device.sent.filter { ((it[1].toInt() and 0xFF) shl 8 or (it[2].toInt() and 0xFF)) == addr }
                .map { DtcFixtures.hex(it.copyOfRange(4, it.size)) }
    }

    /** Real answers: (F194 uds, F195 uds) per address, from the recorded scan of the car. */
    private val real = mapOf(
        0x720 to ("62F1944845001201F00056E0" to "62F1959C4C1A090A01"),
        0x754 to ("62F19448490011AD0C000004" to "62F1959C4118011401"),
        0x703 to ("62F194584E0011975100000B" to "62F1959C471A051500"),
    )
    private val silentGateway = 0x722   // result byte 3 on every request
    private val silentNothing = 0x762   // no frame at all
    private val refused = 0x7E4         // 7F 22 31 on F194

    /** The F194 answer every other block gives; the job must not care which. */
    private val generic = "62F1944B51001202830000" + "01"
    private val genericF195 = "62F1959E201A051300"

    private fun twice(addr: Int, uds: String, result: Int = 1): List<ByteArray> =
        DtcFixtures.frame(addr, hex(uds), result).let { listOf(it, it) }

    private fun world(): World {
        val w = World()
        w.device.respond = { p ->
            val addr = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
            val req = DtcFixtures.hex(p.copyOfRange(4, p.size))
            when {
                addr == silentGateway -> twice(addr, req, result = 3)
                addr == silentNothing -> emptyList()
                addr == refused && req == "22F194" -> twice(addr, "7F2231")
                req == "22F194" -> twice(addr, real[addr]?.first ?: generic)
                req == "22F195" -> twice(addr, real[addr]?.second ?: genericF195)
                else -> emptyList()
            }
        }
        return w
    }

    private fun DtcScanner.runFirmware(w: World): FirmwareStatus { startFirmware { w.gear() }; return firmwareStatus() }

    @Test fun `the job sends 22 F1 94 then 22 F1 95 per answering block and nothing else`() {
        val w = world()
        assertEquals(0, w.scanner.startFirmware { w.gear() })
        assertEquals(listOf("22F194", "22F195"), w.requestsTo(0x720))
        assertEquals(listOf("22F194", "22F195"), w.requestsTo(0x754))
        assertTrue(w.requests().all { it == "22F194" || it == "22F195" })
        assertTrue(w.device.sent.isNotEmpty())
        // Every block of the list was asked, and no other address.
        val asked = w.device.sent.map { ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) }.toSet()
        assertEquals(DtcWire.ADDRESSES.toSet(), asked)
    }

    @Test fun `a block silent on F194 gets no F195`() {
        val w = world()
        w.scanner.startFirmware { w.gear() }
        for (addr in listOf(silentGateway, silentNothing)) {
            val sent = w.requestsTo(addr)
            assertTrue("0x${"%X".format(addr)} $sent", sent.isNotEmpty() && sent.all { it == "22F194" })
        }
    }

    @Test fun `C13 a block that answered F194 with a negative code still gets F195`() {
        val w = world()
        w.scanner.startFirmware { w.gear() }
        assertEquals(listOf("22F194", "22F195"), w.requestsTo(refused))
    }

    @Test fun `C13 a gateway timeout, a busy gateway or a manager timeout on F194 gets no F195`() {
        for (result in listOf(3, 4, 5)) {
            val w = world()
            w.device.respond = { p ->
                val addr = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
                if (addr == 0x720) twice(addr, "", result = result) else emptyList()
            }
            w.scanner.startFirmware { w.gear() }
            assertTrue("result=$result ${w.requestsTo(0x720)}", w.requestsTo(0x720).all { it == "22F194" })
        }
    }

    @Test fun `done lists every block with its raw answers, silent ones with none`() {
        val w = world()
        val s = w.scanner.runFirmware(w)
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(38, s.total)
        assertEquals(38, s.done)
        assertEquals(DtcWire.ADDRESSES.toList(), s.results.map { it.addr })
        val bms = s.results.first { it.addr == 0x720 }
        assertEquals(DtcWire.OUTCOME_OK, bms.outcome)
        assertArrayEquals(hex(real.getValue(0x720).first), bms.f194Uds)
        assertArrayEquals(hex(real.getValue(0x720).second), bms.f195Uds)
        val gw = s.results.first { it.addr == silentGateway }
        assertNull(gw.f194Uds)
        assertNull(gw.f195Uds)
        assertNotEquals(DtcWire.OUTCOME_OK, gw.outcome)
        val none = s.results.first { it.addr == silentNothing }
        assertNull(none.f194Uds)
        assertNull(none.f195Uds)
        assertNotEquals(DtcWire.OUTCOME_OK, none.outcome)
    }

    @Test fun `a gear other than P before the start sends nothing and aborts as not_park`() {
        val w = world()
        w.gear = { 4 }
        val s = w.scanner.runFirmware(w)
        assertEquals(0, w.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park"))
    }

    @Test fun `a start while a scan runs answers busy and sends nothing`() {
        val w = world()
        w.inline = false
        assertEquals(0, w.scanner.start { w.gear() })
        assertEquals(1, w.scanner.startFirmware { w.gear() })
        assertEquals(0, w.device.sent.size)
    }

    @Test fun `absent or busy guard stops firmware check before any send`() {
        val absentClock = FakeClock()
        val absentDevice = FakeDevice(absentClock)
        val absentScanner = DtcScanner(absentDevice, absentClock, { null }, runner = { it.run() }, readSpeed = { 0 })
        absentScanner.startFirmware { 1 }
        val absent = absentScanner.firmwareStatus()
        assertEquals(0, absentDevice.sent.size)
        assertEquals(DtcWire.STATE_UNAVAILABLE, absent.state)
        assertEquals("guard_absent", absent.reason)

        val busyClock = FakeClock()
        val busyDevice = FakeDevice(busyClock)
        val busyScanner = DtcScanner(busyDevice, busyClock, { "r@1_0&w@-1_0" }, runner = { it.run() }, readSpeed = { 0 })
        busyScanner.startFirmware { 1 }
        val busy = busyScanner.firmwareStatus()
        assertEquals(0, busyDevice.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, busy.state)
        assertTrue(busy.reason, busy.reason.startsWith("diag_busy"))
    }
}
