package com.bydmate.app.helper.dtc

import com.bydmate.app.helper.dtc.DtcFixtures.hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@Suppress("LargeClass") // one fake car scripts every write scenario; splitting would copy it
class DtcScannerTpmsTest {

    private class FakeClock : DtcClock {
        var t = 0L
        override fun nowMs() = t
        override fun sleep(ms: Long) { t += ms }
    }

    private class FakeDevice(private val clock: FakeClock) : DtcDevice {
        val queue = ArrayDeque<ByteArray>()
        val sent = mutableListOf<ByteArray>()
        var respond: (ByteArray) -> List<ByteArray> = { emptyList() }
        /** The vendor return code per request; non-zero = rejected (the frame is still counted as sent). */
        var rc: (ByteArray) -> Int = { 0 }

        override fun register(): String? = null
        override fun unregister() { queue.clear() }
        override fun send(payload: ByteArray): Int {
            sent += payload
            val code = rc(payload)
            if (code == 0) queue += respond(payload)
            return code
        }
        /** Frames that arrive [later]: (ready at, frame). */
        private val delayed = mutableListOf<Pair<Long, ByteArray>>()
        fun later(ms: Long, frames: List<ByteArray>) { frames.forEach { delayed += clock.t + ms to it } }

        override fun poll(timeoutMs: Long): ByteArray? {
            delayed.filter { it.first <= clock.t }.sortedBy { it.first }.forEach { queue += it.second; delayed.remove(it) }
            if (queue.isNotEmpty()) return queue.removeFirst()
            val next = delayed.minByOrNull { it.first }
            if (next != null && next.first <= clock.t + timeoutMs) {
                clock.t = maxOf(clock.t, next.first)
                delayed.remove(next)
                return next.second
            }
            clock.t += timeoutMs
            return null
        }
        override fun drain() = queue.clear()
    }

    private fun id(s: String) = s.toLong(16).toInt()
    private fun idHex(v: Int) = "%08X".format(v)

    private val f194 = hex("62F1944845001201F00056E0")
    private val dtc = hex("5902FF5000032850010328500203285003032850150028")
    private val seed = "12345678"
    private val key = "B8FEFFA3" // key of [seed]
    private val dids = listOf(0x3305, 0x3306, 0x3307, 0x3308)
    private val oldIds = listOf("9A4B4AC0", "9A367593", "9A7BB4C2", "9A1561AF").map(::id)
    private val newIds = listOf("9A7BB4C2", "9A1561AF", "9A4B4AC0", "9A367593").map(::id)
    private val reads = listOf("223305", "223306", "223307", "223308")

    private fun plan(n: Int = 4) = (0 until n).map { TpmsPlanEntry(dids[it], oldIds[it], newIds[it]) }

    /** Every answer the car gives comes twice, like the real device. */
    private fun twice(uds: ByteArray, result: Int = 1): List<ByteArray> =
        DtcFixtures.frame(TpmsWire.ADDR, uds, result).let { listOf(it, it) }

    /** The block of an L3 car, answering as recorded in the winter write. */
    private class Car(val seed: String, val key: String) {
        val ids = mutableMapOf<Int, Int>()
        var session = false
        /** Returns the answer for a request (hex of the UDS) or null for the default behaviour. */
        var override: (String) -> List<ByteArray>? = { null }
    }

    private fun slotUds(did: Int, id: Int, heard: Boolean = true) =
        hex("62%04X%08X%02X0001011C0200".format(did, id, if (heard) 0 else 1))

    private class World {
        val clock = FakeClock()
        val device = FakeDevice(clock)
        var guard: () -> String? = { DtcWire.GUARD_BASELINE }
        var gear: () -> Int? = { 1 }
        var speed: () -> Int? = { 0 }
        val tasks = mutableListOf<Runnable>()
        var inline = true
        val scanner by lazy {
            DtcScanner(device, clock, { guard() }, runner = { if (inline) it.run() else tasks += it }, readSpeed = { speed() })
        }
        fun requests() = device.sent.map { DtcFixtures.hex(it.copyOfRange(4, it.size)) }
        fun count(prefix: String) = requests().count { it.startsWith(prefix) }
    }

    private fun world(car: Car = Car(seed, key).also { c -> dids.forEachIndexed { i, d -> c.ids[d] = oldIds[i] } },
                      setup: (World, Car) -> Unit = { _, _ -> }): Pair<World, Car> {
        val w = World()
        w.device.respond = { p ->
            val h = DtcFixtures.hex(p.copyOfRange(4, p.size))
            car.override(h) ?: when {
                h == "22F194" -> twice(f194)
                h == "1902FF" -> twice(dtc)
                h.startsWith("2233") -> {
                    val did = h.substring(2).toInt(16)
                    twice(slotUds(did, car.ids.getValue(did)))
                }
                h == "1003" -> { car.session = true; twice(hex("5003003201F4")) }
                h == "1001" -> { car.session = false; twice(hex("5001003201F4")) }
                h == "2701" -> twice(hex("6701$seed"))
                h.startsWith("2702") -> if (h.substring(4) == key) twice(hex("6702")) else twice(hex("7F2735"))
                h.startsWith("2E") -> {
                    val did = h.substring(2, 6).toInt(16)
                    car.ids[did] = id(h.substring(6))
                    twice(hex("6E%04X".format(did)))
                }
                else -> emptyList()
            }
        }
        setup(w, car)
        return w to car
    }

    private fun write(w: World, p: List<TpmsPlanEntry> = plan()) = w.scanner.startTpmsWrite(p) { w.gear() }
    private fun slot(s: TpmsStatus, did: Int) = s.slots.first { it.did == did }

    /** D.6 50: every frame the device received goes to 0x720. */
    private fun assertAddr720(w: World) {
        for (p in w.device.sent) {
            assertEquals(0x0A, p[0].toInt())
            assertEquals(0x07, p[1].toInt())
            assertEquals(0x20, p[2].toInt())
            assertEquals(p.size - 4, p[3].toInt() and 0xFF)
        }
    }

    private fun happyRequests() = listOf("22F194", "1902FF") + reads + listOf("1003", "2701", "2702$key") +
        dids.indices.map { "2E%04X%s".format(dids[it], idHex(newIds[it])) } + listOf("1001") + reads + listOf("1902FF")

    // Read job

    @Test fun `tpms read sends exactly the six reads to 0x720 and returns the raw slots`() {
        val (w, car) = world()
        assertEquals(0, w.scanner.startTpmsRead { w.gear() })
        assertEquals(listOf("22F194", "1902FF") + reads, w.requests())
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(DtcWire.MODE_TPMS_READ, s.mode)
        assertEquals(dids.map { DtcFixtures.hex(slotUds(it, car.ids.getValue(it))) }, dids.map { DtcFixtures.hex(slot(s, it).beforeUds!!) })
        assertAddr720(w)
    }

    @Test fun `tpms read never sends 10, 27 or 2E`() {
        val (w, _) = world()
        w.scanner.startTpmsRead { w.gear() }
        assertEquals(DtcWire.STATE_DONE, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("10") + w.count("27") + w.count("2E"))
    }

    // Start refusals

    @Test fun `a tpms write while a scan runs is busy and sends nothing`() {
        val (w, _) = world()
        w.inline = false
        assertEquals(0, w.scanner.start { w.gear() })
        assertEquals(1, write(w))
        assertEquals(0, w.device.sent.size)
    }

    @Test fun `a plan that fails validation returns -1 and sends nothing`() {
        val (w, _) = world()
        assertEquals(-1, write(w, emptyList()))
        assertEquals(-1, write(w, listOf(TpmsPlanEntry(0x3305, 5, 5))))
        assertEquals(0, w.device.sent.size)
    }

    // D.2 20, 21 at job level

    @Test fun `cas 20 - a slot that changed since the window ends as car_changed without a session`() {
        val (w, car) = world()
        car.ids[0x3305] = id("11111111")
        assertEquals(0, write(w))
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("car_changed"))
        assertEquals(0, w.count("1003") + w.count("2E"))
        assertAddr720(w)
    }

    @Test fun `cas 21 - every slot already new is done with nothing to write and no session`() {
        val (w, car) = world()
        dids.forEachIndexed { i, d -> car.ids[d] = newIds[i] }
        assertEquals(0, write(w))
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertTrue(s.reason, s.reason.startsWith("nothing_to_write"))
        assertEquals(0, w.count("1003") + w.count("2E"))
        assertAddr720(w)
    }

    // D.4 34

    @Test fun `session 34 - 50 03 00 32 01 F4 continues to the security seed`() {
        val (w, _) = world()
        write(w)
        assertEquals(1, w.count("2701"))
        assertAddr720(w)
    }

    @Test fun `session 34 - another session timing aborts before 27 with one 10 01 as the exit`() {
        val (w, car) = world()
        car.override = { h -> if (h == "1003") twice(hex("5003001901F4")) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("27") + w.count("2E"))
        assertEquals(1, w.count("1001"))
        assertEquals("1001", w.requests().last())
        assertAddr720(w)
    }

    // D.5 36, 37

    @Test fun `security 36 - seed 00000000 means unlocked and no 27 02 is sent`() {
        val (w, car) = world()
        car.override = { h -> if (h == "2701") twice(hex("670100000000")) else null }
        write(w)
        assertEquals(0, w.count("2702"))
        assertEquals(4, w.count("2E"))
        assertAddr720(w)
    }

    @Test fun `security 37 - a seed of 2 or 5 bytes sends no 27 02 and no 2E`() {
        for (bad in listOf("6701AABB", "6701AABBCCDDEE")) {
            val (w, car) = world()
            car.override = { h -> if (h == "2701") twice(hex(bad)) else null }
            write(w)
            assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
            assertEquals(0, w.count("2702"))
            assertEquals(0, w.count("2E"))
            assertAddr720(w)
        }
    }

    // D.6

    @Test fun `write 38 - happy path sends exactly the recorded sequence and every entry is written`() {
        val (w, car) = world()
        assertEquals(0, write(w))
        assertEquals(happyRequests(), w.requests())
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(DtcWire.MODE_TPMS_WRITE, s.mode)
        assertEquals(List(4) { TpmsVerdict.WRITTEN }, dids.map { slot(s, it).verdict })
        assertEquals(newIds, dids.map { car.ids.getValue(it) })
        assertAddr720(w)
    }

    @Test fun `write 39 - a rejected key sends no 2E, exits the session, pauses, and a start within 60 s is refused`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2702")) twice(hex("7F2735")) else null }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("security_rejected"))
        assertEquals(0, w.count("2E"))
        val i = w.requests().indexOf("1001")
        assertTrue(i > 0)
        assertEquals(reads, w.requests().drop(i + 1).take(4))
        assertAddr720(w)
        assertPauseThenProceed(w)
    }

    @Test fun `write 40 - a refused first 2E stops further 2E and leaves entry 1 not written`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) twice(hex("7F2E33")) else null }
        write(w)
        val r = w.requests()
        assertEquals(1, w.count("2E"))
        assertTrue(r.indexOf("1001") > r.indexOfFirst { it.startsWith("2E") })
        val s = w.scanner.tpmsStatus()
        assertEquals(TpmsVerdict.NOT_WRITTEN, slot(s, 0x3305).verdict)
        assertEquals(List(3) { TpmsVerdict.NOT_RUN }, dids.drop(1).map { slot(s, it).verdict })
        assertAddr720(w)
    }

    @Test fun `write 41 - a silent 2E is not resent and the re-read decides, new means written`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) { car.ids[0x3305] = newIds[0]; emptyList() } else null }
        write(w)
        assertEquals(1, w.count("2E"))
        val s = w.scanner.tpmsStatus()
        assertEquals(TpmsVerdict.WRITTEN, slot(s, 0x3305).verdict)
        assertEquals(List(3) { TpmsVerdict.NOT_RUN }, dids.drop(1).map { slot(s, it).verdict })
        assertAddr720(w)
    }

    @Test fun `write 41 - a silent 2E is not resent and the re-read decides, old means not written`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) emptyList() else null }
        write(w)
        assertEquals(1, w.count("2E"))
        assertEquals(TpmsVerdict.NOT_WRITTEN, slot(w.scanner.tpmsStatus(), 0x3305).verdict)
        assertAddr720(w)
    }

    @Test fun `write 42 - a pending answer then the positive one counts as written`() {
        val (w, car) = world()
        val fresh = id("9A0000A1")
        car.override = { h ->
            if (h.startsWith("2E")) {
                car.ids[0x3305] = fresh
                twice(hex("7F2E78")) + twice(hex("6E3305"))
            } else null
        }
        // A new sensor ID that no other wheel holds (newIds[0] already sits in 3307: a duplicate).
        write(w, listOf(TpmsPlanEntry(0x3305, oldIds[0], fresh)))
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(TpmsVerdict.WRITTEN, slot(s, 0x3305).verdict)
        assertEquals(1, w.count("2E"))
        assertAddr720(w)
    }

    private fun assertStoppedInSession(w: World, reasonPrefix: String, closes: Boolean) {
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith(reasonPrefix))
        assertEquals(1, w.count("2E"))
        if (closes) {
            assertEquals("1001", w.requests().last())
            assertEquals(1, w.count("1001"))
        } else {
            assertTrue(w.requests().last().startsWith("2E"))
            assertEquals(0, w.count("1001"))
        }
        assertEquals(TpmsVerdict.UNVERIFIED, slot(s, 0x3305).verdict)
        assertEquals(List(3) { TpmsVerdict.NOT_RUN }, dids.drop(1).map { slot(s, it).verdict })
        assertAddr720(w)
    }

    @Test fun `write 43 - leaving park before the second 2E sends one 10 01 and nothing after`() {
        val (w, _) = world()
        w.gear = { if (w.count("2E") >= 1) 4 else 1 }
        write(w)
        assertStoppedInSession(w, "not_park", closes = true)
    }

    @Test fun `write 44 - speed above zero before the second 2E sends one 10 01 and nothing after`() {
        val (w, _) = world()
        w.speed = { if (w.count("2E") >= 1) 1 else 0 }
        write(w)
        assertStoppedInSession(w, "speed", closes = true)
    }

    @Test fun `write 44 - a non-baseline guard before the second 2E sends nothing after the last 2E`() {
        val (w, _) = world()
        w.guard = { if (w.count("2E") >= 1) "r@1_0&w@-1_0" else DtcWire.GUARD_BASELINE }
        write(w)
        assertStoppedInSession(w, "diag_busy", closes = false)
    }

    @Test fun `write 45 - a frame from 0x7E8 during the session aborts and nothing more is sent`() {
        val (w, car) = world()
        car.override = { h ->
            if (h == "1003") twice(hex("5003003201F4")) + DtcFixtures.frame(0x7E0, hex("620000"))
            else null
        }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals("1003", w.requests().last())
        assertAddr720(w)
    }

    @Test fun `write 46 - traffic in the first two seconds sends nothing`() {
        val (w, _) = world()
        w.device.queue += DtcFixtures.frame(TpmsWire.ADDR, f194)
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.device.sent.size)
    }

    @Test fun `write 47 - a busy result on a 2E is not resent, entry not sent, session closed`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) twice(ByteArray(0), result = 5) else null }
        write(w)
        assertEquals(1, w.count("2E"))
        val r = w.requests()
        assertTrue(r.indexOf("1001") > r.indexOfFirst { it.startsWith("2E") })
        val s = w.scanner.tpmsStatus()
        assertEquals(TpmsVerdict.NOT_SENT, slot(s, 0x3305).verdict)
        assertEquals(List(3) { TpmsVerdict.NOT_RUN }, dids.drop(1).map { slot(s, it).verdict })
        assertAddr720(w)
    }

    @Test fun `write 48 - running over the budget inside the session aborts with one 10 01 and no 2E`() {
        val (w, car) = world()
        car.override = { h -> if (h == "1003") { w.clock.t += 60_000; twice(hex("5003003201F4")) } else null }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("budget"))
        assertEquals(0, w.count("2E"))
        assertEquals("1001", w.requests().last())
        assertEquals(1, w.count("1001"))
        assertAddr720(w)
    }

    @Test fun `write 49 - duplicated answers are not counted as foreign traffic`() {
        val (w, _) = world()
        write(w)
        assertEquals(DtcWire.STATE_DONE, w.scanner.tpmsStatus().state)
        assertEquals(happyRequests().size, w.device.sent.size)
        assertFalse(w.scanner.tpmsStatus().reason.startsWith("foreign"))
    }

    // Review round: exact answers (C3)

    private fun key(w: World) = w.requests().indexOfFirst { it.startsWith("2702") }

    @Test fun `C3 a key answer longer than 67 02 is a security failure - latched, exit, no 2E`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2702")) twice(hex("670200")) else null }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("security_rejected"))
        assertEquals(0, w.count("2E"))
        assertTrue(w.requests().indexOf("1001") > key(w))
        assertPauseThenProceed(w)
    }

    @Test fun `C3 a key answer whose frame result byte is not 1 is not an unlock`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2702")) twice(hex("6702"), result = 2) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("2E"))
    }

    @Test fun `C3 a write answer longer than 6E 33 0x leaves the entry unknown, sends no more 2E and exits`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) twice(hex("6E330500")) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(1, w.count("2E"))
        val r = w.requests()
        assertTrue(r.indexOf("1001") > r.indexOfFirst { it.startsWith("2E") })
        assertEquals(List(3) { TpmsVerdict.NOT_RUN }, dids.drop(1).map { slot(w.scanner.tpmsStatus(), it).verdict })
    }

    @Test fun `C3 a write answer whose frame result byte is not 1 is not positive and ends the writes`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) twice(hex("6E3305"), result = 2) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(1, w.count("2E"))
    }

    // C4: the gateway answers that are not a plain answer, inside the extended session

    private fun multiframe(uds: ByteArray): ByteArray = DtcFixtures.frame(TpmsWire.ADDR, uds).also { it[4] = 2 }

    /** A frame whose length byte promises more than it carries. */
    private fun truncated(): ByteArray = DtcFixtures.frame(TpmsWire.ADDR, hex("6E3305")).copyOf(8).also { it[3] = 0x20 }

    @Test fun `C4 a multiframe answer to the seed request stops the run with nothing sent after it`() {
        val (w, car) = world()
        car.override = { h -> if (h == "2701") listOf(multiframe(hex("6701$seed"))) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals("2701", w.requests().last())
        assertEquals(0, w.count("1001") + w.count("2702") + w.count("2E"))
    }

    @Test fun `C4 a multiframe answer to a 2E stops the run with no further 2E and nothing after it`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) listOf(multiframe(hex("6E3305"))) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(1, w.count("2E"))
        assertTrue(w.requests().last().startsWith("2E"))
        assertEquals(0, w.count("1001"))
    }

    @Test fun `C4 a truncated answer to a 2E stops the run with no further 2E and nothing after it`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2E")) listOf(truncated()) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(1, w.count("2E"))
        assertTrue(w.requests().last().startsWith("2E"))
        assertEquals(0, w.count("1001"))
    }

    @Test fun `C4 a multiframe answer to the session request itself sends nothing after 10 03`() {
        val (w, car) = world()
        car.override = { h -> if (h == "1003") listOf(multiframe(hex("5003003201F4"))) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals("1003", w.requests().last())
    }

    @Test fun `C4 before 10 03 a multiframe answer keeps its old effect - not_l3 and no session`() {
        val (w, car) = world()
        car.override = { h -> if (h == "22F194") listOf(multiframe(f194)) else null }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_l3"))
        assertEquals(0, w.count("10"))
    }

    // C5: the security attempt counts when the key is sent (D11 replaced the daemon-lifetime latch)

    /** D11: 30 s after a failed key a start is refused with nothing sent; 61 s after it a start sends 27 02 again. */
    private fun assertPauseThenProceed(w: World) {
        val sentBefore = w.device.sent.size
        val keysBefore = w.count("2702")
        w.clock.t += 30_000
        assertEquals(-1, write(w))
        assertEquals(sentBefore, w.device.sent.size)
        w.clock.t += 31_000
        assertEquals(0, write(w))
        assertEquals(keysBefore + 1, w.count("2702"))
    }

    @Test fun `C5a a refused key followed by a foreign frame before the job reacts still latches`() {
        val (w, car) = world()
        car.override = { h ->
            if (h.startsWith("2702")) twice(hex("7F2735")) + DtcFixtures.frame(0x7E0, hex("620000")) else null
        }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("2E"))
        assertPauseThenProceed(w)
    }

    @Test fun `C5b a transport error on the 27 02 send latches`() {
        val (w, _) = world()
        w.device.rc = { p -> if (DtcFixtures.hex(p.copyOfRange(4, p.size)).startsWith("2702")) 1 else 0 }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("2E"))
        assertPauseThenProceed(w)
    }

    @Test fun `C5c a write started before a failed key was recorded never sends 27 02 once it is recorded`() {
        val (w, _) = world()
        w.inline = false
        assertEquals(0, write(w))
        w.scanner.recordSecurityFailure(w.clock.t)
        w.tasks.single().run()
        assertEquals(0, w.count("2702"))
        assertEquals(0, w.count("2E"))
    }

    // C6: leaving the extended session

    @Test fun `C6a 10 03 sent and not answered sends one 10 01 and nothing after it`() {
        val (w, car) = world()
        car.override = { h -> if (h == "1003") emptyList() else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        val r = w.requests()
        assertEquals("1001", r.last())
        assertEquals(1, w.count("1001"))
        assertTrue(r.indexOf("1001") > r.indexOf("1003"))
        assertEquals(0, w.count("27") + w.count("2E"))
    }

    @Test fun `C6b a wrong session answer sends one 10 01, no 27 and no 2E`() {
        val (w, car) = world()
        car.override = { h -> if (h == "1003") twice(hex("5003001901F4")) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(1, w.count("1001"))
        assertEquals("1001", w.requests().last())
        assertEquals(0, w.count("27") + w.count("2E"))
    }

    @Test fun `C6c a gear read that throws inside the session sends one 10 01 and nothing after it`() {
        val (w, _) = world()
        w.gear = { if (w.count("2701") >= 1) error("gear") else 1 }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals("1001", w.requests().last())
        assertEquals(1, w.count("1001"))
        assertEquals(0, w.count("2702") + w.count("2E"))
    }

    @Test fun `C6c a speed read that throws inside the session sends one 10 01 and nothing after it`() {
        val (w, _) = world()
        w.speed = { if (w.count("2701") >= 1) error("speed") else 0 }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals("1001", w.requests().last())
        assertEquals(1, w.count("1001"))
        assertEquals(0, w.count("2702") + w.count("2E"))
    }

    @Test fun `C6d the exit 10 01 does not read the gear again`() {
        val (w, _) = world()
        val reads = mutableListOf<Int>()
        w.gear = { reads += w.device.sent.size; if (w.count("2E") >= 1) 4 else 1 }
        write(w)
        val exitAt = w.requests().indexOf("1001")
        assertTrue(exitAt > 0)
        // One read decided the stop for the next 2E; the exit adds none.
        assertEquals(1, reads.count { it == exitAt })
        assertEquals(1, w.count("1001"))
    }

    @Test fun `C6d the exit 10 01 does not read the speed again`() {
        val (w, _) = world()
        val reads = mutableListOf<Int>()
        w.speed = { reads += w.device.sent.size; if (w.count("2E") >= 1) 5 else 0 }
        write(w)
        val exitAt = w.requests().indexOf("1001")
        assertTrue(exitAt > 0)
        assertEquals(1, reads.count { it == exitAt })
        assertEquals(1, w.count("1001"))
    }

    private fun worldWithUnansweredExit(): World {
        val (w, car) = world()
        car.override = { h -> if (h == "1001") emptyList() else null }
        write(w)
        return w
    }

    @Test fun `C6e after a job whose exit was not confirmed no job starts for 6 seconds`() {
        val w = worldWithUnansweredExit()
        val end = w.clock.t
        val before = w.device.sent.size
        w.clock.t = end + 1_000
        assertEquals(1, w.scanner.start { w.gear() })
        assertEquals(1, w.scanner.startTpmsRead { w.gear() })
        assertEquals(1, w.scanner.startErase(intArrayOf(0x712)) { w.gear() })
        assertEquals(1, w.scanner.startFirmware { w.gear() })
        assertEquals(1, write(w))
        assertEquals(before, w.device.sent.size)
        w.clock.t = end + 6_500
        assertEquals(0, w.scanner.startTpmsRead { w.gear() })
    }

    @Test fun `C6e after a stop on a foreign frame inside the session no job starts at once`() {
        val (w, car) = world()
        car.override = { h ->
            if (h == "1003") twice(hex("5003003201F4")) + DtcFixtures.frame(0x7E0, hex("620000")) else null
        }
        write(w)
        val before = w.device.sent.size
        assertEquals(1, w.scanner.startTpmsRead { w.gear() })
        assertEquals(before, w.device.sent.size)
    }

    @Test fun `C6e after a write whose exit was confirmed the next job starts at once`() {
        val (w, _) = world()
        write(w)
        assertEquals(DtcWire.STATE_DONE, w.scanner.tpmsStatus().state)
        assertEquals(0, w.scanner.startTpmsRead { w.gear() })
    }

    // C9: an entry whose 2E went out

    @Test fun `C9 a stop while waiting for the answer to a 2E leaves that entry unverified, the rest not run`() {
        val (w, car) = world()
        car.override = { h ->
            if (h.startsWith("2E")) listOf(DtcFixtures.frame(0x7E0, hex("620000"))) + twice(hex("6E3305")) else null
        }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertEquals(1, w.count("2E"))
        assertEquals(TpmsVerdict.UNVERIFIED, slot(s, 0x3305).verdict)
        assertEquals(List(3) { TpmsVerdict.NOT_RUN }, dids.drop(1).map { slot(s, it).verdict })
    }

    // Review round 2

    @Test fun `D1 a re-read answer whose frame result byte is not 1 leaves that slot unread and unverified`() {
        val (w, car) = world()
        car.override = { h ->
            if (h == "223305" && w.count("1001") >= 1) twice(slotUds(0x3305, newIds[0]), result = 2) else null
        }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(TpmsVerdict.UNVERIFIED, slot(s, 0x3305).verdict)
        assertEquals(null, slot(s, 0x3305).afterUds)
        assertEquals(List(3) { TpmsVerdict.WRITTEN }, dids.drop(1).map { slot(s, it).verdict })
    }

    private fun assertExitSentOnceRightAfterLastWrite(w: World) {
        val r = w.requests()
        assertEquals(4, w.count("2E"))
        assertEquals(1, w.count("1001"))
        assertEquals("1001", r[r.indexOfLast { it.startsWith("2E") } + 1])
    }

    @Test fun `D2 the normal exit after the last 2E is sent although the gear read now throws`() {
        val (w, _) = world()
        w.gear = { if (w.count("2E") >= 4) error("gear") else 1 }
        write(w)
        assertExitSentOnceRightAfterLastWrite(w)
    }

    @Test fun `D2 the normal exit after the last 2E is sent although the gear is now R`() {
        val (w, _) = world()
        w.gear = { if (w.count("2E") >= 4) 2 else 1 }
        write(w)
        assertExitSentOnceRightAfterLastWrite(w)
    }

    @Test fun `E1 no gear or speed read happens between the last 2E answer and 10 01, which is sent once`() {
        val (w, _) = world()
        val late = mutableListOf<String>()
        w.gear = {
            if (w.count("2E") >= 4 && w.count("1001") == 0) late += "gear"
            1
        }
        w.speed = {
            if (w.count("2E") >= 4 && w.count("1001") == 0) late += "speed"
            0
        }
        write(w)
        assertEquals(emptyList<String>(), late)
        assertExitSentOnceRightAfterLastWrite(w)
    }

    @Test fun `E1 the exit after the last 2E is sent once when any gear or speed read would throw`() {
        val (w, _) = world()
        w.gear = { if (w.count("2E") >= 4 && w.count("1001") == 0) error("gear") else 1 }
        w.speed = { if (w.count("2E") >= 4 && w.count("1001") == 0) error("speed") else 0 }
        write(w)
        assertExitSentOnceRightAfterLastWrite(w)
    }

    @Test fun `E2 a guard that changes during the second gear read of the gate sends no 2E and stops as diag_busy`() {
        val (w, _) = world()
        var bad = false
        var gateReads = 0
        w.guard = { if (bad) "r@1_0&w@-1_0" else DtcWire.GUARD_BASELINE }
        w.gear = {
            if (w.count("2702") >= 1 && w.count("2E") == 0 && ++gateReads == 2) bad = true
            1
        }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("diag_busy"))
        assertEquals(0, w.count("2E"))
        assertEquals(0, w.count("1001"))
        assertTrue(w.requests().last().startsWith("2702"))
    }

    @Test fun `D3 a gear read right after the speed read, in the same gate, before the first 2E, that is not P sends no 2E`() {
        val (w, _) = world()
        var speedAt = -1
        w.speed = { speedAt = w.device.sent.size; 0 }
        // The re-read: a gear read with the speed read already made and no request sent in between.
        w.gear = { val g = if (speedAt == w.device.sent.size && w.count("2702") >= 1) 2 else 1; speedAt = -1; g }
        write(w)
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_ABORTED, s.state)
        assertTrue(s.reason, s.reason.startsWith("not_park"))
        assertEquals(0, w.count("2E"))
        assertEquals(1, w.count("1001"))
    }

    @Test fun `D4 a pending 7F 2E 78 for each 2E of a four-wheel swap extends that request's own window`() {
        val (w, car) = world()
        car.override = { h ->
            if (h.startsWith("2E")) {
                val did = h.substring(2, 6).toInt(16)
                car.ids[did] = id(h.substring(6))
                w.device.later(3_000, twice(hex("6E%04X".format(did))))
                twice(hex("7F2E78"))
            } else null
        }
        // A three-wheel subset of this swap would leave one ID on two wheels; the full plan is valid.
        write(w, plan())
        val s = w.scanner.tpmsStatus()
        assertEquals(DtcWire.STATE_DONE, s.state)
        assertEquals(4, w.count("2E"))
        assertEquals(List(4) { TpmsVerdict.WRITTEN }, dids.map { slot(s, it).verdict })
    }

    // D11: security attempts (3 per window, 60 s pause after a failure, 30 min reset, NRC 36/37 wait 10 min)

    private fun rejectKeys(car: Car, whileKeysAtMost: Int, w: World) {
        car.override = { h -> if (h.startsWith("2702") && w.count("2702") <= whileKeysAtMost) twice(hex("7F2735")) else null }
    }

    private fun assertStartRefusedNothingSent(w: World) {
        val before = w.device.sent.size
        assertEquals(-1, write(w))
        assertEquals(before, w.device.sent.size)
    }

    @Test fun `D11 a rejected key refuses a start 30 s later with nothing sent, and 61 s later it sends 27 02 again`() {
        val (w, car) = world()
        rejectKeys(car, 99, w)
        write(w)
        assertEquals(1, w.count("2702"))
        assertPauseThenProceed(w)
        assertEquals(2, w.count("2702"))
    }

    @Test fun `D11 three failures each after the pause refuse the fourth start until 30 min after the last failure`() {
        val (w, car) = world()
        rejectKeys(car, 99, w)
        write(w)
        w.clock.t += 61_000
        assertEquals(0, write(w))
        w.clock.t += 61_000
        assertEquals(0, write(w))
        assertEquals(3, w.count("2702"))
        val end = w.clock.t
        w.clock.t = end + 61_000
        assertStartRefusedNothingSent(w)
        w.clock.t = end + 29 * 60_000L
        assertStartRefusedNothingSent(w)
        w.clock.t = end + 31 * 60_000L
        assertEquals(0, write(w))
        assertEquals(4, w.count("2702"))
    }

    @Test fun `D11 a good 67 02 after one failure resets the count, two more failures are then allowed`() {
        val (w, car) = world()
        rejectKeys(car, 1, w)
        write(w) // failure 1
        w.clock.t += 61_000
        assertEquals(0, write(w)) // good key, the plan is written
        assertEquals(DtcWire.STATE_DONE, w.scanner.tpmsStatus().state)
        dids.forEachIndexed { i, d -> car.ids[d] = oldIds[i] }
        rejectKeys(car, 99, w)
        w.clock.t += 61_000
        assertEquals(0, write(w)) // failure A
        w.clock.t += 61_000
        assertEquals(0, write(w)) // failure B
        w.clock.t += 61_000
        val keys = w.count("2702")
        assertEquals(0, write(w)) // not refused: the count was reset, only two failures stand
        assertEquals(keys + 1, w.count("2702"))
    }

    @Test fun `D11 NRC 36 on 27 02 stops with one 10 01 and refuses starts for 10 min`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2702")) twice(hex("7F2736")) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("2E"))
        assertEquals(1, w.count("1001"))
        assertTrue(w.requests().indexOf("1001") > key(w))
        val end = w.clock.t
        w.clock.t = end + 61_000 // the 60 s pause alone would be over
        assertStartRefusedNothingSent(w)
        w.clock.t = end + 9 * 60_000L
        assertStartRefusedNothingSent(w)
        w.clock.t = end + 11 * 60_000L
        assertEquals(0, write(w))
        assertEquals(2, w.count("2702"))
    }

    @Test fun `D11 NRC 37 on 27 01 stops, sends no 27 02 and one 10 01, and refuses starts for 10 min`() {
        val (w, car) = world()
        car.override = { h -> if (h == "2701") twice(hex("7F2737")) else null }
        write(w)
        assertEquals(DtcWire.STATE_ABORTED, w.scanner.tpmsStatus().state)
        assertEquals(0, w.count("2702") + w.count("2E"))
        assertEquals(1, w.count("1001"))
        assertEquals("1001", w.requests().last())
        val end = w.clock.t
        w.clock.t = end + 61_000
        assertStartRefusedNothingSent(w)
        w.clock.t = end + 9 * 60_000L
        assertStartRefusedNothingSent(w)
        w.clock.t = end + 11 * 60_000L
        assertEquals(0, write(w))
        assertEquals(2, w.count("2701"))
    }

    @Test fun `D11 securityWaitMs reports the remaining wait, zero when a start is allowed`() {
        val (w, car) = world()
        assertEquals(0L, w.scanner.securityWaitMs())
        rejectKeys(car, 99, w)
        write(w)
        val end = w.clock.t
        assertTrue(w.scanner.securityWaitMs() in 1L..60_000L)
        w.clock.t = end + 30_000
        assertTrue(w.scanner.securityWaitMs() in 1L..30_000L)
        w.clock.t = end + 61_000
        assertEquals(0L, w.scanner.securityWaitMs())
        write(w)
        w.clock.t += 61_000
        write(w) // third failure
        assertTrue(w.scanner.securityWaitMs() > 25 * 60_000L)
    }

    @Test fun `D11 securityWaitMs after NRC 36 is the 10 min wait`() {
        val (w, car) = world()
        car.override = { h -> if (h.startsWith("2702")) twice(hex("7F2736")) else null }
        write(w)
        val wait = w.scanner.securityWaitMs()
        assertTrue(wait.toString(), wait in 9 * 60_000L..10 * 60_000L)
    }
}
