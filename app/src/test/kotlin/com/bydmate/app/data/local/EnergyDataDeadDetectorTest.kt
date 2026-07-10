package com.bydmate.app.data.local

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyDataDeadDetectorTest {

    private class FakeStore : EnergyDataLivenessStore {
        var dead = false
        var streakV = 0
        var pending = false
        var fp: Pair<Long, Long>? = null
        var lastTs = 0L
        override fun isDead() = dead
        override fun setDead() { dead = true }
        override fun streak() = streakV
        override fun setStreak(value: Int) { streakV = value }
        override fun pendingDriving() = pending
        override fun setPendingDriving(value: Boolean) { pending = value }
        override fun fingerprint() = fp
        override fun setFingerprint(mtime: Long, size: Long) { fp = mtime to size }
        override fun lastIncrementTs() = lastTs
        override fun setLastIncrementTs(value: Long) { lastTs = value }
        override fun reset() { dead = false; streakV = 0; pending = false; fp = null; lastTs = 0L }
    }

    private var fileFp: Pair<Long, Long>? = 100L to 4096L
    private var nowMs = 0L
    private val store = FakeStore()

    private fun detector(): EnergyDataDeadDetector {
        val reader = mockk<EnergyDataReader> { every { sourceFingerprint() } answers { fileFp } }
        return EnergyDataDeadDetector(reader, store) { nowMs }
    }

    /** One full driving session: ignition on at [startKm], off at [endKm]. */
    private fun EnergyDataDeadDetector.session(startKm: Double?, endKm: Double?) {
        onTick(true, startKm)
        onTick(false, endKm)
    }

    private val hour = 60 * 60_000L

    // Matrix case 3: dead leftover on DiLink 3 — three spaced driving sessions mark dead.
    @Test
    fun `frozen file and three spaced driving sessions mark dead`() {
        val d = detector()
        d.session(1000.0, 1010.0); nowMs += hour
        d.session(1010.0, 1020.0); nowMs += hour
        d.session(1020.0, 1030.0); nowMs += hour
        d.onTick(true, 1030.0)   // 4th ignition-on evaluates 3rd session -> streak 3 -> dead
        assertTrue(d.isDead())
    }

    // Matrix cases 1/2: live energydata (Leopard 3 / DiLink 4) — file changes, never dead.
    @Test
    fun `file that changes between sessions never marks dead`() {
        val d = detector()
        repeat(10) { i ->
            d.session(1000.0 + i * 10, 1010.0 + i * 10)
            fileFp = (100L + i + 1) to (4096L + i + 1)   // car appended a record while parked
            nowMs += hour
        }
        d.onTick(true, 1200.0)
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
    }

    // Matrix case 5: parked/idle cycles without driving never advance the streak.
    @Test
    fun `ignition cycles without driving never mark dead`() {
        val d = detector()
        repeat(10) { d.session(1000.0, 1000.0); nowMs += hour }
        d.onTick(true, 1000.0)
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
    }

    // Matrix case 4: no file at all — detector stays silent (isAvailable=false handles it).
    @Test
    fun `no file present keeps detector silent`() {
        fileFp = null
        val d = detector()
        d.session(1000.0, 1010.0); nowMs += hour
        d.onTick(true, 1010.0)
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
        assertEquals(null, store.fp)
    }

    @Test
    fun `session under one km does not count`() {
        val d = detector()
        repeat(5) { d.session(1000.0, 1000.5); nowMs += hour }
        d.onTick(true, 1002.5)
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
    }

    @Test
    fun `null mileage session does not count`() {
        val d = detector()
        repeat(5) { d.session(null, null); nowMs += hour }
        d.onTick(true, null)
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
    }

    // Anti-flap guard: ignition flapping inside one drive must not inflate the streak.
    @Test
    fun `increments closer than 30 minutes are ignored`() {
        val d = detector()
        d.session(1000.0, 1010.0); nowMs += hour
        d.onTick(true, 1010.0)                     // streak 1 (first increment always allowed)
        d.onTick(false, 1020.0); nowMs += 5 * 60_000L
        d.onTick(true, 1020.0)                     // 5 min later: pending consumed, no increment
        d.onTick(false, 1030.0); nowMs += 5 * 60_000L
        d.onTick(true, 1030.0)
        assertEquals(1, store.streakV)
        assertFalse(d.isDead())
    }

    // Auto-heal: a revived file on a dead-marked device flips back to passive so
    // HistoryImporter and TripRecorder can never run in parallel on a live file.
    @Test
    fun `file change on dead-marked device heals back to alive`() {
        val d = detector()
        d.session(1000.0, 1010.0); nowMs += hour
        d.session(1010.0, 1020.0); nowMs += hour
        d.session(1020.0, 1030.0); nowMs += hour
        d.onTick(true, 1030.0)
        assertTrue(d.isDead())
        d.onTick(false, 1040.0)
        fileFp = 999L to 8192L                     // firmware service revived energydata
        nowMs += hour
        d.onTick(true, 1040.0)
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
    }

    @Test
    fun `reset clears verdict and cached value`() {
        val d = detector()
        d.session(1000.0, 1010.0); nowMs += hour
        d.session(1010.0, 1020.0); nowMs += hour
        d.session(1020.0, 1030.0); nowMs += hour
        d.onTick(true, 1030.0)
        assertTrue(d.isDead())
        d.reset()
        assertFalse(d.isDead())
        assertEquals(0, store.streakV)
    }

    @Test
    fun `repeated on ticks within one session evaluate only once`() {
        val d = detector()
        d.session(1000.0, 1010.0); nowMs += hour
        d.onTick(true, 1010.0)
        d.onTick(true, 1011.0)   // same session, must not consume/evaluate again
        d.onTick(true, 1012.0)
        assertEquals(1, store.streakV)
    }

    @Test
    fun `debugState mentions dead flag and streak`() {
        val d = detector()
        val s = d.debugState()
        assertTrue(s.contains("dead=false"))
        assertTrue(s.contains("streak=0"))
    }
}
