package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DtcScannerTest {

    private class FakeClock : DtcClock {
        var t = 0L
        val sleeps = mutableListOf<Long>()
        override fun nowMs() = t
        override fun sleep(ms: Long) { sleeps += ms; t += ms }
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
        /** Frames that arrive later: (absolute fake time, frame); delivered by poll when the wait reaches them. */
        val late = mutableListOf<Pair<Long, ByteArray>>()

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
            val next = late.minByOrNull { it.first }
            if (next != null && next.first <= clock.t + timeoutMs) {
                late.remove(next)
                clock.t = maxOf(clock.t, next.first)
                return next.second
            }
            clock.t += timeoutMs
            return null
        }
        override fun drain() = queue.clear()

        fun addr(i: Int) = ((sent[i][1].toInt() and 0xFF) shl 8) or (sent[i][2].toInt() and 0xFF)

        /** Every request's bytes after the `0A hi lo len` header, per address, in send order. */
        fun allRequests(addr: Int): List<String> =
            sent.indices.filter { addr(it) == addr }.map { DtcFixtures.hex(sent[it].copyOfRange(4, sent[it].size)) }

        /** As [allRequests] without the log-only snapshot requests (`19 04`), which never change a result. */
        fun requests(addr: Int): List<String> = allRequests(addr).filterNot { it.startsWith("1904") }
    }

    private class World {
        val clock = FakeClock()
        val device = FakeDevice(clock)
        var guard: () -> String? = { DtcWire.GUARD_BASELINE }
        var gear: () -> Int? = { 1 }
        var speed: () -> Int? = { 0 }
        val tasks = mutableListOf<Runnable>()
        var inline = true

        val scanner by lazy {
            DtcScanner(device, clock, { guard() }, runner = { if (inline) it.run() else tasks += it },
                readSpeed = { speed() })
        }

        fun run(): DtcScanStatus { scanner.start { gear() }; return scanner.status() }

        fun erase(vararg addrs: Int): DtcScanStatus {
            scanner.startErase(addrs) { gear() }
            return scanner.status()
        }
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
    private fun isSnapshot(p: ByteArray) = (p[4].toInt() and 0xFF) == 0x19 && (p[5].toInt() and 0xFF) == 0x04
    private fun isClear(p: ByteArray) = (p[4].toInt() and 0xFF) == 0x14
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

    @Test fun `a visible row asks for the identity and the name after the list, mask 09 when no pending bit`() {
        val w = world()
        w.run()
        // count-712 availability is 0x09: no 0x04 bit, so the block filters with 0x09
        assertEquals(listOf("1901FF", "190209", "22F194", "22F197"), w.device.requests(0x712))
    }

    @Test fun `availability with the pending bit asks for mask 0D`() {
        val w = world { p ->
            if (addrOf(p) == 0x712 && isCount(p)) {
                listOf(DtcFixtures.frame(0x712, hex("5901 0D 01 0001")))
            } else recorded(p)
        }
        w.run()
        assertEquals(listOf("1901FF", "19020D", "22F194", "22F197"), w.device.requests(0x712))
    }

    @Test fun `the fixture availability with the pending bit picks mask 0D on 0x782`() {
        val w = world()
        w.run()
        // count-782 availability is 0xFF
        assertEquals(listOf("1901FF", "19020D", "22F194", "22F197"), w.device.requests(0x782))
    }

    @Test fun `a masked list answered with a negative code is retried once with FF`() {
        val w = world { p ->
            if (addrOf(p) == 0x712 && isList(p) && p[6] != 0xFF.toByte()) {
                listOf(DtcFixtures.frame(0x712, hex("7F 19 31")))
            } else recorded(p)
        }
        val s = w.run()
        assertEquals(listOf("1901FF", "190209", "1902FF", "22F194", "22F197"), w.device.requests(0x712))
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

    private fun isName(p: ByteArray) = (p[4].toInt() and 0xFF) == 0x22 && (p[6].toInt() and 0xFF) == 0x97

    @Test fun `the names are asked only after every address was read`() {
        val w = world()
        w.run()
        val firstName = w.device.sent.indexOfFirst(::isName)
        val lastCount = w.device.sent.indexOfLast(::isCount)
        assertTrue("$firstName after $lastCount", firstName > lastCount)
    }

    @Test fun `a rejected name request keeps the scan DONE with every address and asks no more names`() {
        val w = world()
        w.device.respond = { p ->
            if (isName(p)) w.device.rc = 1
            recorded(p)
        }
        val s = w.run()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(18, s.done)
        assertEquals(DtcWire.OUTCOME_OK, s.of(0x712).outcome)
        assertEquals(1, w.device.sent.count(::isName))
        assertEquals(1, w.device.unregisterCalls)
    }

    @Test fun `an exception on a name request keeps the scan DONE and asks no more names`() {
        val w = world()
        w.device.respond = { p ->
            if (isName(p)) error("vendor send failed")
            recorded(p)
        }
        val s = w.run()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(18, s.done)
        assertEquals(DtcWire.OUTCOME_OK, s.of(0x712).outcome)
        assertEquals(1, w.device.sent.count(::isName))
        assertEquals(1, w.device.unregisterCalls)
    }

    @Test fun `the budget running out during the names keeps the scan DONE`() {
        val w = world()
        w.device.respond = { p ->
            if (isName(p)) w.clock.t += 91_000
            recorded(p)
        }
        val s = w.run()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(18, s.done)
        assertEquals(1, w.device.sent.count(::isName))
    }

    @Test fun `the gear leaving P during the names sends nothing more and keeps the scan DONE`() {
        val w = world()
        w.gear = { if (w.device.sent.any(::isName)) 4 else 1 }
        val s = w.run()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(1, w.device.sent.count(::isName))
    }

    @Test fun `only the six fixed request shapes are ever sent`() {
        val w = world()
        w.run()
        val allowed = setOf("1901FF", "19020D", "190209", "1902FF", "22F194", "22F197")
        for (p in w.device.sent) {
            if (isSnapshot(p)) {
                // the snapshot shape: 19 04 <dtc b2 b1 b0> FF
                assertEquals(10, p.size)
                assertEquals(0xFF.toByte(), p[9])
            } else {
                assertEquals(7, p.size)
                assertTrue(DtcFixtures.hex(p), DtcFixtures.hex(p.copyOfRange(4, 7)) in allowed)
            }
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

    // ---------------------------------------------------------------- erase runs

    private fun ack(addr: Int) = DtcFixtures.frame(addr, hex("54"))
    private fun busy(addr: Int) = DtcFixtures.frame(addr, ByteArray(0), result = 5)
    private fun mgrTimeout(addr: Int) = DtcFixtures.frame(addr, ByteArray(0), result = 4)
    private fun gwTimeout(addr: Int) = DtcFixtures.frame(addr, ByteArray(0), result = 3)
    private fun zeroCount(addr: Int) = DtcFixtures.frame(addr, hex("590109010000"))

    private fun World.clears(addr: Int) = device.sent.count { isClear(it) && addrOf(it) == addr }
    private fun World.counts() = device.sent.count(::isCount)
    private fun World.sendShapes() = device.sent.map { addrOf(it) to hex(it.copyOfRange(4, it.size)) }
    private fun hex(b: ByteArray) = DtcFixtures.hex(b)

    /** A world whose clears are answered by [onClear] and whose re-reads by [onRead] (null = a zero count). */
    private fun eraseWorld(
        onRead: (World, ByteArray) -> List<ByteArray>? = { _, _ -> null },
        onClear: (World, ByteArray) -> List<ByteArray> = { _, p -> listOf(ack(addrOf(p))) },
    ): World {
        val w = World()
        w.device.respond = { p ->
            when {
                isClear(p) -> onClear(w, p)
                else -> onRead(w, p) ?: if (isCount(p)) listOf(zeroCount(addrOf(p))) else emptyList()
            }
        }
        return w
    }

    private fun DtcScanStatus.entry(addr: Int): DtcEraseResult = erase.first { it.addr == addr }

    @Test fun `E1 a plain erase of two blocks confirms both and re-reads them after the clears`() {
        val w = eraseWorld()
        val s = w.erase(0x712, 0x7E7)
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(DtcWire.MODE_ERASE, s.mode)
        assertEquals(2, s.erase.size)
        assertTrue(s.erase.all { it.confirm == DtcWire.CONFIRM_CONFIRMED })
        assertEquals(
            listOf(0x712 to "14FFFFFF", 0x7E7 to "14FFFFFF", 0x712 to "1901FF", 0x7E7 to "1901FF"),
            w.sendShapes(),
        )
        val allowed = setOf("1901FF", "19020D", "190209", "1902FF", "22F194", "22F197")
        for (p in w.device.sent) {
            val ok = p.contentEquals(DtcWire.clearRequest(addrOf(p))) ||
                (p.size == 7 && hex(p.copyOfRange(4, 7)) in allowed)
            assertTrue(hex(p), ok)
        }
    }

    /** Song Plus EV, 2026-11-02: 0x726 answered `54 FF FF FF`; the run stopped there and left 5 blocks unerased. */
    @Test fun `E1b a clear answered with the echoed group is confirmed and the run goes on`() {
        val echo = DtcFixtures.frame(0x726, DtcFixtures.hex("54 FF FF FF"))
        val w = eraseWorld(onClear = { _, p -> if (addrOf(p) == 0x726) listOf(echo, echo) else listOf(ack(addrOf(p))) })
        val s = w.erase(0x712, 0x720, 0x726, 0x782)
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertTrue(s.erase.all { it.confirm == DtcWire.CONFIRM_CONFIRMED })
        assertEquals(4, w.device.sent.count { isClear(it) })
    }

    @Test fun `E2 a gear other than P stops the erase before any send`() {
        val w = eraseWorld()
        w.gear = { 4 }
        val s = w.erase(0x712, 0x7E7)
        assertEquals(0, w.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park"))
        assertEquals(2, s.erase.size)
        assertTrue(s.erase.all { it.confirm == DtcWire.CONFIRM_NOT_RUN })
    }

    @Test fun `E3 an unknown or non-zero speed stops the erase before any send`() {
        for (speed in listOf<Int?>(null, 3)) {
            val w = eraseWorld()
            w.speed = { speed }
            val s = w.erase(0x712, 0x7E7)
            assertEquals(0, w.device.sent.size)
            assertEquals(DtcWire.STATE_ABORTED, s.state)
            assertTrue(s.reason, s.reason.startsWith("speed"))
            assertTrue(s.erase.all { it.confirm == DtcWire.CONFIRM_NOT_RUN })
        }
    }

    @Test fun `E4 the guard property gates the erase like the scan`() {
        val absent = eraseWorld()
        absent.guard = { null }
        val a = absent.erase(0x712)
        assertEquals(0, absent.device.sent.size)
        assertEquals(DtcWire.STATE_UNAVAILABLE, a.state)
        assertEquals("guard_absent", a.reason)

        val busy = eraseWorld()
        busy.guard = { "r@1_0&w@-1_0" }
        val b = busy.erase(0x712)
        assertEquals(0, busy.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, b.state)
        assertTrue(b.reason, b.reason.startsWith("diag_busy"))
    }

    @Test fun `E5 traffic in the passive window stops the erase without sending`() {
        val w = eraseWorld()
        w.device.queue += DtcFixtures.raw("count-712.txt")
        val s = w.erase(0x712)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("passive_traffic"))
        assertEquals(0, w.device.sent.size)
    }

    @Test fun `E6 busy twice then ok resends with a growing backoff and confirms`() {
        var n = 0
        val w = eraseWorld(onClear = { _, p -> listOf(if (++n <= 2) busy(addrOf(p)) else ack(addrOf(p))) })
        val s = w.erase(0x712)
        assertEquals(3, w.clears(0x712))
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x712).confirm)
        assertEquals(listOf(1000L, 2000L), w.clock.sleeps.filter { it == 1000L || it == 2000L }.take(2))
    }

    @Test fun `E7 busy on every try gives up after the retries and the run goes on`() {
        val w = eraseWorld(onClear = { _, p -> listOf(if (addrOf(p) == 0x712) busy(0x712) else ack(addrOf(p))) })
        val s = w.erase(0x712, 0x7E7)
        assertEquals(1 + DtcScanner.BUSY_RETRIES, w.clears(0x712))
        assertEquals(DtcWire.CONFIRM_UNCONFIRMED, s.entry(0x712).confirm)
        assertEquals("busy", s.entry(0x712).reason)
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x7E7).confirm)
    }

    @Test fun `E8 the gates are checked again before every resend`() {
        for (variant in listOf("gear", "speed")) {
            val w = eraseWorld(onClear = { _, p -> listOf(busy(addrOf(p))) })
            if (variant == "gear") w.gear = { if (w.device.sent.isEmpty()) 1 else 4 }
            else w.speed = { if (w.device.sent.isEmpty()) 0 else 5 }
            val s = w.erase(0x712, 0x7E7)
            assertEquals(variant, 1, w.clears(0x712))
            assertEquals(variant, DtcWire.STATE_ABORTED, s.state)
            assertTrue(s.reason, s.reason.startsWith(if (variant == "gear") "not_park" else "speed"))
            assertEquals(variant, DtcWire.CONFIRM_UNCONFIRMED, s.entry(0x712).confirm)
            assertEquals(variant, DtcWire.CONFIRM_NOT_RUN, s.entry(0x7E7).confirm)
            assertEquals(variant, 0, w.clears(0x7E7))
        }
    }

    @Test fun `E9 a lost answer is never resent and the block is still re-read`() {
        val cases = listOf<Pair<(Int) -> List<ByteArray>, String>>(
            { a: Int -> listOf(mgrTimeout(a)) } to "manager_timeout",
            { a: Int -> listOf(gwTimeout(a)) } to "gw_timeout",
            { _: Int -> emptyList<ByteArray>() } to "no_answer",
        )
        for ((answer, reason) in cases) {
            val w = eraseWorld(onClear = { _, p -> answer(addrOf(p)) })
            val s = w.erase(0x712)
            assertEquals(reason, 1, w.clears(0x712))
            assertEquals(reason, DtcWire.CONFIRM_UNCONFIRMED, s.entry(0x712).confirm)
            assertEquals(reason, reason, s.entry(0x712).reason)
            val lastClear = w.device.sent.indexOfLast(::isClear)
            assertTrue(reason, w.device.sent.drop(lastClear + 1).any { isCount(it) && addrOf(it) == 0x712 })
        }
    }

    @Test fun `E10 an answer 5500 ms after the send still counts`() {
        val w = eraseWorld(onClear = { w, p -> w.device.late += (w.clock.t + 5_500L) to ack(addrOf(p)); emptyList() })
        val s = w.erase(0x712)
        assertEquals(1, w.clears(0x712))
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x712).confirm)
    }

    @Test fun `E11 a refusal is reported with its code, not resent, and the next block is still erased`() {
        val w = eraseWorld(onClear = { _, p ->
            listOf(if (addrOf(p) == 0x712) DtcFixtures.frame(0x712, hex("7F 14 22"), result = 2) else ack(addrOf(p)))
        })
        val s = w.erase(0x712, 0x7E7)
        assertEquals(1, w.clears(0x712))
        assertEquals(DtcWire.CONFIRM_REFUSED, s.entry(0x712).confirm)
        assertEquals(0x22, s.entry(0x712).nrc)
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x7E7).confirm)
    }

    @Test fun `E12 a foreign frame stops the run but the duplicate of our own answer does not`() {
        val foreign = eraseWorld(onClear = { _, p ->
            if (addrOf(p) == 0x712) listOf(DtcFixtures.frame(0x720, hex("54")), ack(0x712)) else listOf(ack(addrOf(p)))
        })
        val s = foreign.erase(0x712, 0x7E7)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("foreign"))
        assertEquals(DtcWire.CONFIRM_UNCONFIRMED, s.entry(0x712).confirm)
        assertEquals(DtcWire.CONFIRM_NOT_RUN, s.entry(0x7E7).confirm)
        assertEquals(1, foreign.device.sent.size)

        val dup = eraseWorld(onClear = { _, p -> listOf(ack(addrOf(p)), ack(addrOf(p))) })
        val d = dup.erase(0x712, 0x7E7)
        assertEquals(DtcWire.STATE_DONE, d.state)
        assertEquals(DtcWire.CONFIRM_CONFIRMED, d.entry(0x712).confirm)
        assertEquals(DtcWire.CONFIRM_CONFIRMED, d.entry(0x7E7).confirm)
    }

    @Test fun `E13 the gap between blocks and the settle before the re-reads are kept`() {
        val w = eraseWorld()
        w.erase(0x712, 0x7E7)
        val first = w.device.sent.indexOfFirst(::isClear)
        val last = w.device.sent.indexOfLast(::isClear)
        val firstRead = w.device.sent.indexOfFirst(::isCount)
        assertTrue(w.device.sentAt[last] - w.device.sentAt[first] >= DtcScanner.ERASE_GAP_MS)
        assertTrue(w.device.sentAt[firstRead] - w.device.sentAt[last] >= DtcScanner.ERASE_SETTLE_MS)
    }

    @Test fun `E14 the re-read result is attached and does not change the confirmation`() {
        val w = eraseWorld(onRead = { _, p ->
            when {
                addrOf(p) == 0x712 && isCount(p) -> listOf(DtcFixtures.frame(0x712, hex("590109010001")))
                addrOf(p) == 0x712 && isList(p) -> listOf(DtcFixtures.frame(0x712, hex("590209 D16687 09")))
                else -> null
            }
        })
        val s = w.erase(0x712)
        val e = s.entry(0x712)
        assertEquals(DtcWire.CONFIRM_CONFIRMED, e.confirm)
        assertNotNull(e.reread)
        assertEquals("590209D1668709", hex(e.reread!!.listUds!!))
    }

    @Test fun `E15 the gear leaving P after the last clear sends no re-read and keeps the confirmations`() {
        val w = eraseWorld()
        w.gear = { if (w.device.sent.size >= 2) 4 else 1 }
        val s = w.erase(0x712, 0x7E7)
        assertEquals(0, w.counts())
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park"))
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x712).confirm)
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x7E7).confirm)
    }

    @Test fun `E16 a scan and an erase never run at the same time`() {
        val scanFirst = eraseWorld()
        scanFirst.inline = false
        assertEquals(DtcScanner.START_OK, scanFirst.scanner.start { 1 })
        assertEquals(DtcWire.STATE_RUNNING, scanFirst.scanner.status().state)
        assertEquals(DtcScanner.START_BUSY, scanFirst.scanner.startErase(intArrayOf(0x712)) { 1 })

        val eraseFirst = eraseWorld()
        eraseFirst.inline = false
        assertEquals(DtcScanner.START_OK, eraseFirst.scanner.startErase(intArrayOf(0x712)) { 1 })
        assertEquals(DtcWire.STATE_RUNNING, eraseFirst.scanner.status().state)
        assertEquals(DtcScanner.START_BUSY, eraseFirst.scanner.start { 1 })
        assertEquals(1, eraseFirst.tasks.size)
    }

    @Test fun `E17 an empty or outside-the-whitelist address list is refused and nothing is sent`() {
        for (addrs in listOf(intArrayOf(), intArrayOf(0x700), intArrayOf(0x712, 0x700))) {
            val w = eraseWorld()
            assertEquals(DtcScanner.START_REFUSED, w.scanner.startErase(addrs) { 1 })
            assertEquals(0, w.device.sent.size)
            assertTrue(w.scanner.status().state != DtcWire.STATE_RUNNING)
        }
    }

    @Test fun `E18 past the erase budget the run aborts and the next block is not run`() {
        val w = eraseWorld(onClear = { w, p ->
            if (addrOf(p) == 0x712) w.clock.t += DtcScanner.ERASE_BUDGET_MS + 1_000
            listOf(ack(addrOf(p)))
        })
        val s = w.erase(0x712, 0x7E7)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("budget"))
        assertEquals(DtcWire.CONFIRM_CONFIRMED, s.entry(0x712).confirm)
        assertEquals(DtcWire.CONFIRM_NOT_RUN, s.entry(0x7E7).confirm)
        assertEquals(0, w.clears(0x7E7))
    }

    // ---------------------------------------------------------------- scan robustness

    @Test fun `S1 busy twice on a read is retried with a growing backoff`() {
        var n = 0
        val w = world { p ->
            if (addrOf(p) == 0x712 && isCount(p) && ++n <= 2) listOf(busy(0x712)) else recorded(p)
        }
        val s = w.run()
        assertEquals(3, w.device.requests(0x712).count { it == "1901FF" })
        assertEquals(DtcWire.OUTCOME_OK, s.of(0x712).outcome)
        assertEquals(listOf(1000L, 2000L), w.clock.sleeps.filter { it == 1000L || it == 2000L }.take(2))
    }

    @Test fun `S2 busy on every try of a read ends as busy and the scan goes on`() {
        val w = world { p -> if (addrOf(p) == 0x712 && isCount(p)) listOf(busy(0x712)) else recorded(p) }
        val s = w.run()
        assertEquals(1 + DtcScanner.BUSY_RETRIES, w.device.requests(0x712).count { it == "1901FF" })
        assertEquals(DtcWire.OUTCOME_BUSY, s.of(0x712).outcome)
        assertEquals(DtcWire.OUTCOME_GW_TIMEOUT, s.of(0x722).outcome)
        assertEquals(DtcWire.STATE_DONE, s.state)
    }

    @Test fun `S3 result 4 on a read is a manager timeout without waiting the full window`() {
        val w = world { p -> if (addrOf(p) == 0x712 && isCount(p)) listOf(mgrTimeout(0x712)) else recorded(p) }
        val s = w.run()
        assertEquals(1, w.device.requests(0x712).size)
        assertEquals(DtcWire.OUTCOME_MANAGER_TIMEOUT, s.of(0x712).outcome)
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertTrue(w.device.sentAt[1] - w.device.sentAt[0] < DtcScanner.WINDOW_MS)
    }

    @Test fun `S4 the gear leaving P during a busy backoff stops the scan before the resend`() {
        val w = world { p -> if (isCount(p) && addrOf(p) == 0x712) listOf(busy(0x712)) else recorded(p) }
        w.gear = { if (w.device.sent.isEmpty()) 1 else 4 }
        val s = w.run()
        assertEquals(1, w.device.sent.size)
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park"))
    }

    /** 0x712 with [codes] visible codes: count, list and (when [snapshot] is set) its answer to the snapshot request. */
    private fun codesWorld(codes: List<Int>, snapshot: (ByteArray) -> List<ByteArray>): World {
        val count = hex("590109 01 %04X".format(codes.size))
        val list = hex("590209" + codes.joinToString("") { "%06X09".format(it) })
        return world { p ->
            when {
                addrOf(p) == 0x712 && isCount(p) -> listOf(DtcFixtures.frame(0x712, count))
                addrOf(p) == 0x712 && isList(p) -> listOf(DtcFixtures.frame(0x712, list))
                isSnapshot(p) -> snapshot(p)
                else -> recorded(p)
            }
        }
    }

    @Test fun `S5 every shown code gets one snapshot request and the answer changes nothing`() {
        val codes = listOf(0xD16687, 0xD16688)
        val answers = listOf<(ByteArray) -> List<ByteArray>>(
            { p -> listOf(DtcFixtures.frame(addrOf(p), hex("5904D1668709"))) },
            { p -> listOf(DtcFixtures.frame(addrOf(p), hex("7F 19 31"), result = 2)) },
            { emptyList() },
        )
        val results = answers.map { answer ->
            val w = codesWorld(codes, answer)
            val s = w.run()
            val snaps = w.device.allRequests(0x712).filter { it.startsWith("1904") }
            assertEquals(listOf("1904D16687FF", "1904D16688FF"), snaps)
            assertEquals(2, w.device.sent.count { isSnapshot(it) && addrOf(it) == 0x712 })
            assertTrue(w.device.allRequests(0x712).indexOf("190209") < w.device.allRequests(0x712).indexOf(snaps[0]))
            assertEquals(DtcWire.STATE_DONE, s.state)
            s.of(0x712)
        }
        for (r in results.drop(1)) {
            assertEquals(results[0].outcome, r.outcome)
            assertEquals(hex(results[0].countUds!!), hex(r.countUds!!))
            assertEquals(hex(results[0].listUds!!), hex(r.listUds!!))
        }
    }

    @Test fun `S6 at most the snapshot cap of requests is sent in a whole scan`() {
        val codes = (0 until 20).map { 0xD16600 + it }
        val count = hex("590109 01 %04X".format(codes.size))
        val w = world { p ->
            val a = addrOf(p)
            when {
                (a == 0x712 || a == 0x720) && isCount(p) -> listOf(DtcFixtures.frame(a, count))
                (a == 0x712 || a == 0x720) && isList(p) ->
                    listOf(DtcFixtures.frame(a, hex("590209" + codes.joinToString("") { "%06X09".format(it) })))
                // Answered (negative), so the test measures the cap, not the scan budget.
                isSnapshot(p) -> listOf(DtcFixtures.frame(a, hex("7F1931"), result = 2))
                else -> recorded(p)
            }
        }
        w.run()
        assertEquals(DtcScanner.SNAPSHOT_CAP, w.device.sent.count(::isSnapshot))
        assertEquals(30, DtcScanner.SNAPSHOT_CAP)
    }
}
