package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcScannerTest {

    private class FakeClock : DtcClock {
        var t = 0L
        override fun nowMs() = t
        override fun sleep(ms: Long) { t += ms }
    }

    /** Records every send, delivers what [respond] says for it, and counts register/unregister. */
    private class FakeDevice(private val clock: FakeClock) : DtcDevice {
        val queue = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        val sentAt = mutableListOf<Long>()
        var registerCalls = 0
        var unregisterCalls = 0
        var rc = 0
        var respond: (ByteArray) -> List<ByteArray> = { emptyList() }

        override fun register(): String? { registerCalls++; return null }
        override fun unregister() { unregisterCalls++; queue.clear() }
        override fun send(payload: ByteArray): Int {
            sent += payload
            sentAt += clock.t
            if (rc == 0) queue += respond(payload)
            return rc
        }
        override fun poll(timeoutMs: Long): ByteArray? {
            if (queue.isNotEmpty()) return queue.removeFirst()
            clock.t += timeoutMs
            return null
        }
        override fun drain() = queue.clear()

        fun addr(i: Int) = ((sent[i][1].toInt() and 0xFF) shl 8) or (sent[i][2].toInt() and 0xFF)

        /** The request bytes after the `0A hi lo 03` header, per address, in send order. */
        fun requests(addr: Int): List<String> =
            sent.indices.filter { addr(it) == addr }.map { DtcFixtures.hex(sent[it].copyOfRange(4, sent[it].size)) }
    }

    private class World {
        val clock = FakeClock()
        val device = FakeDevice(clock)
        var guard: () -> String? = { DtcWire.GUARD_BASELINE }
        var gear: () -> Int? = { 1 }
        val tasks = mutableListOf<Runnable>()
        var inline = true

        val scanner by lazy {
            DtcScanner(device, clock, { guard() }, runner = { if (inline) it.run() else tasks += it })
        }

        fun run(): DtcScanStatus { scanner.start { gear() }; return scanner.status() }
    }

    /** Frames the car gave on L3 for a request, each twice like the real answer. */
    private fun recorded(payload: ByteArray): List<ByteArray> {
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

    private fun world(respond: (ByteArray) -> List<ByteArray> = ::recorded) =
        World().also { it.device.respond = respond }

    private fun isList(p: ByteArray) = (p[4].toInt() and 0xFF) == 0x19 && (p[5].toInt() and 0xFF) == 0x02
    private fun isCount(p: ByteArray) = (p[4].toInt() and 0xFF) == 0x19 && (p[5].toInt() and 0xFF) == 0x01
    private fun addrOf(p: ByteArray) = ((p[1].toInt() and 0xFF) shl 8) or (p[2].toInt() and 0xFF)
    private fun DtcScanStatus.of(addr: Int) = results.first { it.addr == addr }

    @Test fun `a full L3 scan ends DONE with the outcome of every address`() {
        val w = world()
        val s = w.run()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(18, s.total)
        assertEquals(18, s.done)
        assertEquals(18, s.results.size)
        assertEquals(DtcWire.OUTCOME_OK, s.of(0x712).outcome)
        assertEquals(DtcWire.OUTCOME_GW_TIMEOUT, s.of(0x722).outcome)
        assertEquals(DtcWire.OUTCOME_NRC, s.of(0x7E4).outcome)
        assertEquals(DtcWire.OUTCOME_NO_ANSWER, s.of(0x720).outcome)
        assertEquals(1, w.device.registerCalls)
        assertEquals(1, w.device.unregisterCalls)
    }

    @Test fun `the gear leaving P stops the scan before the next request`() {
        val w = world()
        w.gear = { if (w.device.sent.size < 3) 1 else 4 }
        val s = w.run()
        assertEquals(3, w.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park") && s.reason.endsWith("4"))
        assertEquals(w.device.registerCalls, w.device.unregisterCalls)
        assertEquals(1, w.device.unregisterCalls)
    }

    @Test fun `an unreadable gear stops the scan too`() {
        val w = world()
        w.gear = { null }
        val s = w.run()
        assertEquals(0, w.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park"))
    }

    @Test fun `an absent guard property is unavailable and nothing is sent`() {
        val w = world()
        w.guard = { null }
        val s = w.run()
        assertEquals(DtcWire.STATE_UNAVAILABLE, s.state)
        assertEquals("guard_absent", s.reason)
        assertEquals(0, w.device.sent.size)
        assertEquals(w.device.registerCalls, w.device.unregisterCalls)
    }

    @Test fun `a guard property that changes mid-scan aborts as diagnostics busy`() {
        val w = world()
        w.guard = { if (w.device.sent.size < 2) DtcWire.GUARD_BASELINE else "r@1_0&w@-1_0" }
        val s = w.run()
        assertEquals(2, w.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("diag_busy"))
        assertEquals(w.device.registerCalls, w.device.unregisterCalls)
    }

    @Test fun `traffic in the passive window aborts without sending`() {
        val w = world()
        w.device.queue += DtcFixtures.raw("count-712.txt")
        val s = w.run()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("passive_traffic"))
        assertEquals(0, w.device.sent.size)
        assertEquals(w.device.registerCalls, w.device.unregisterCalls)
    }

    @Test fun `a rejected first send is unavailable after exactly one attempt`() {
        val w = world()
        w.device.rc = -2147482648
        val s = w.run()
        assertEquals(DtcWire.STATE_UNAVAILABLE, s.state)
        assertTrue(s.reason, s.reason.startsWith("transport_rejected"))
        assertEquals(1, w.device.sent.size)
        assertEquals(w.device.registerCalls, w.device.unregisterCalls)
    }

    @Test fun `a block that stays silent costs one window and the scan goes on`() {
        val w = world { p -> if (addrOf(p) == 0x7E1) emptyList() else recorded(p) }
        val s = w.run()
        assertEquals(DtcWire.OUTCOME_NO_ANSWER, s.of(0x7E1).outcome)
        val i = w.device.sent.indices.first { addrOf(w.device.sent[it]) == 0x7E1 }
        assertTrue(w.device.sentAt[i + 1] - w.device.sentAt[i] >= 2500)
        assertEquals(0x7E2, w.device.addr(i + 1))
        assertEquals(DtcWire.STATE_DONE, s.state)
    }

    @Test fun `a zero count asks for no list`() {
        val w = world()
        w.run()
        assertEquals(listOf("1901FF"), w.device.requests(0x726))
    }

    @Test fun `a visible row asks for the identity after the list, mask 09 when no pending bit`() {
        val w = world()
        w.run()
        // count-712 availability is 0x09: no 0x04 bit, so the block filters with 0x09
        assertEquals(listOf("1901FF", "190209", "22F194"), w.device.requests(0x712))
    }

    @Test fun `availability with the pending bit asks for mask 0D`() {
        val w = world { p ->
            if (addrOf(p) == 0x712 && isCount(p)) {
                listOf(DtcFixtures.frame(0x712, hex("5901 0D 01 0001")))
            } else recorded(p)
        }
        w.run()
        assertEquals(listOf("1901FF", "19020D", "22F194"), w.device.requests(0x712))
    }

    @Test fun `the fixture availability with the pending bit picks mask 0D on 0x782`() {
        val w = world()
        w.run()
        // count-782 availability is 0xFF
        assertEquals(listOf("1901FF", "19020D", "22F194"), w.device.requests(0x782))
    }

    @Test fun `a masked list answered with a negative code is retried once with FF`() {
        val w = world { p ->
            if (addrOf(p) == 0x712 && isList(p) && p[6] != 0xFF.toByte()) {
                listOf(DtcFixtures.frame(0x712, hex("7F 19 31")))
            } else recorded(p)
        }
        val s = w.run()
        assertEquals(listOf("1901FF", "190209", "1902FF", "22F194"), w.device.requests(0x712))
        assertEquals(DtcWire.OUTCOME_OK, s.of(0x712).outcome)
        assertNotNull(s.of(0x712).listUds)
    }

    @Test fun `a negative code on the FF retry too ends that block as nrc`() {
        val w = world { p ->
            if (addrOf(p) == 0x712 && isList(p)) listOf(DtcFixtures.frame(0x712, hex("7F 19 31"))) else recorded(p)
        }
        val s = w.run()
        assertEquals(listOf("1901FF", "190209", "1902FF"), w.device.requests(0x712))
        assertEquals(DtcWire.OUTCOME_NRC, s.of(0x712).outcome)
        assertEquals(DtcWire.STATE_DONE, s.state)
    }

    @Test fun `only the five fixed request shapes are ever sent`() {
        val w = world()
        w.run()
        val allowed = setOf("1901FF", "19020D", "190209", "1902FF", "22F194")
        for (p in w.device.sent) {
            assertEquals(7, p.size)
            assertTrue(DtcFixtures.hex(p), DtcFixtures.hex(p.copyOfRange(4, 7)) in allowed)
            assertTrue(addrOf(p) in DtcWire.ADDRESSES.toList())
        }
    }

    @Test fun `a duplicate of an earlier answer is ignored`() {
        val w = world { p ->
            when {
                // the list of the same block arrives late, ahead of the real count answer
                addrOf(p) == 0x712 && isCount(p) ->
                    listOf(DtcFixtures.raw("list-712.txt"), DtcFixtures.raw("count-712.txt"))
                // a frame of another block arrives ahead of this block's answer
                addrOf(p) == 0x7E4 && isCount(p) ->
                    listOf(DtcFixtures.raw("count-712.txt"), DtcFixtures.raw("count-7E4.txt"))
                else -> recorded(p)
            }
        }
        val s = w.run()
        assertEquals("590109010001", DtcFixtures.hex(s.of(0x712).countUds!!))
        assertEquals(DtcWire.OUTCOME_NRC, s.of(0x7E4).outcome)
    }

    @Test fun `start while running answers busy and starts nothing twice`() {
        val w = world()
        w.inline = false
        assertEquals(0, w.scanner.start { 1 })
        assertEquals(DtcWire.STATE_RUNNING, w.scanner.status().state)
        assertEquals(1, w.scanner.start { 1 })
        assertEquals(1, w.tasks.size)
    }

    @Test fun `past ninety seconds the scan aborts on the budget`() {
        val w = world()
        w.device.respond = { p ->
            if (w.device.sent.size == 3) w.clock.t += 91_000
            recorded(p)
        }
        val s = w.run()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertEquals("budget", s.reason)
        assertEquals(3, w.device.sent.size)
        assertEquals(1, w.device.unregisterCalls)
    }

    @Test fun `no frame at all in a full scan is unavailable`() {
        val w = world { emptyList() }
        val s = w.run()
        assertEquals(DtcWire.STATE_UNAVAILABLE, s.state)
        assertEquals("no_frames", s.reason)
        assertTrue(s.results.all { it.outcome == DtcWire.OUTCOME_NO_ANSWER })
        assertEquals(1, w.device.unregisterCalls)
    }

    @Test fun `a block that cannot register the listener is unavailable`() {
        val clock = FakeClock()
        val device = object : DtcDevice {
            var sends = 0
            override fun register(): String = "class_missing"
            override fun unregister() = Unit
            override fun send(payload: ByteArray): Int { sends++; return 0 }
            override fun poll(timeoutMs: Long): ByteArray? = null
            override fun drain() = Unit
        }
        val scanner = DtcScanner(device, clock, { DtcWire.GUARD_BASELINE }, runner = { it.run() })
        scanner.start { 1 }
        assertEquals(DtcWire.STATE_UNAVAILABLE, scanner.status().state)
        assertEquals("class_missing", scanner.status().reason)
        assertEquals(0, device.sends)
    }
}
