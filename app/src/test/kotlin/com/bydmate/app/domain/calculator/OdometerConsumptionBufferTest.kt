package com.bydmate.app.domain.calculator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OdometerConsumptionBufferTest {

    private fun newBuffer(fallback: Double = 18.0, dao: FakeOdometerSampleDao = FakeOdometerSampleDao()) =
        OdometerConsumptionBuffer(dao = dao, fallbackEmaProvider = { fallback })

    @Test fun `empty buffer returns fallback`() = runBlocking {
        val b = newBuffer(fallback = 18.0)
        assertEquals(18.0, b.recentAvgConsumption(), 0.001)
        assertNull(b.shortAvgConsumption())
    }

    @Test fun `under MIN_BUFFER_KM returns fallback`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(mileage = 10000.0, totalElec = 1500.0, socPercent = 80, sessionId = s)
        b.onSample(mileage = 10001.0, totalElec = 1500.18, socPercent = 80, sessionId = s)
        b.onSample(mileage = 10003.0, totalElec = 1500.54, socPercent = 80, sessionId = s)
        assertEquals(18.0, b.recentAvgConsumption(), 0.001)
    }

    @Test fun `normal case computes from buffer`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        var mile = 10000.0
        var elec = 1500.0
        b.onSample(mile, elec, 80, s)
        repeat(10) {
            mile += 1.0
            elec += 0.20
            b.onSample(mile, elec, 80, s)
        }
        assertEquals(20.0, b.recentAvgConsumption(), 0.1)
    }

    @Test fun `sessionId boundary skips cross-session pair`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s1 = 1L
        val s2 = 2L
        var m = 10000.0; var e = 1500.0
        b.onSample(m, e, 80, s1)
        repeat(10) { m += 1.0; e += 0.18; b.onSample(m, e, 80, s1) }
        // cross-session jump (overnight drift)
        b.onSample(m + 0.05, e + 1.2, 78, s2)
        var m2 = m + 0.05; var e2 = e + 1.2
        repeat(5) { m2 += 1.0; e2 += 0.22; b.onSample(m2, e2, 78, s2) }
        // Within-session: 10 km @ 18 + 5 km @ 22 → weighted avg ≈ 19.33
        assertEquals(19.33, b.recentAvgConsumption(), 0.5)
    }

    @Test fun `HVAC idle inside same session does not pollute avg`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        var m = 10000.0; var e = 1500.0
        b.onSample(m, e, 80, s)
        repeat(5) { m += 1.0; e += 0.18; b.onSample(m, e, 80, s) }
        e += 0.3  // 30-min HVAC absorbed while tinyMoves were skipped
        m += 1.0; e += 0.18; b.onSample(m, e, 80, s)  // first post-idle tick, dKwh = 0.48 over 1 km
        repeat(4) { m += 1.0; e += 0.18; b.onSample(m, e, 80, s) }
        // 10 km, kWh = 5*0.18 + 0.48 + 4*0.18 = 2.1 → 21 kWh/100km
        assertEquals(21.0, b.recentAvgConsumption(), 0.5)
    }

    @Test fun `parked samples with zero mileage delta do not pollute`() = runBlocking {
        // Regression guard for v2.4.7: charging / parked-with-HVAC ticks arrive with
        // constant mileage. Built-in MIN_MILEAGE_DELTA must suppress inserts so the
        // buffer does not bloat with static rows.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 50, s)
        // Simulate 20 polling ticks during parking — mileage doesn't change.
        repeat(20) { b.onSample(10000.0, 1500.0 + it * 0.01, 50, s) }
        assertEquals(1, dao.count())
    }

    @Test fun `charging after session close does not bloat the table`() = runBlocking {
        // Second v2.4.7 regression guard: after ignition-off the session closes
        // (sessionId flips to null). First charging tick has sessionId=null while
        // the prev row has a real sessionId, so sameSession=false and the tinyMove
        // suppression is bypassed — exactly ONE boundary row is inserted. Every
        // subsequent null-session tick has a null-session prev too, so sameSession
        // holds and the tinyMove guard kicks in. Table must not grow further.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        // One real driving sample.
        b.onSample(10000.0, 1500.0, 80, sessionId = 1L)
        // Session closed → 30 charging ticks with no mileage change.
        repeat(30) { b.onSample(10000.0, 1500.0 + it * 0.01, 50, sessionId = null) }
        assertEquals(2, dao.count())
    }

    @Test fun `odometer regression skipped`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(9999.5, 1500.05, 80, s)
        assertEquals(1, dao.count())
    }

    @Test fun `huge forward jump skipped`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(10500.0, 1600.0, 70, s)
        assertEquals(1, dao.count())
    }

    @Test fun `tick under MIN_MILEAGE_DELTA in same session not inserted`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(10000.02, 1500.001, 80, s)  // 20 m — skipped
        b.onSample(10000.04, 1500.002, 80, s)  // 40 m — skipped (still < 50 m from prev insert)
        b.onSample(10000.06, 1500.003, 80, s)  // 60 m — inserted (60 > 50)
        assertEquals(2, dao.count())
    }

    @Test fun `session change forces insert even on tiny mileage delta`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        b.onSample(10000.0, 1500.0, 80, 1L)
        b.onSample(10000.02, 1501.0, 79, 2L)
        assertEquals(2, dao.count())
    }

    @Test fun `short avg null when distance under 2 km`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(10001.0, 1500.18, 80, s)
        assertNull(b.shortAvgConsumption())
    }

    @Test fun `short avg computed when distance at least 2 km`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        var m = 10000.0; var e = 1500.0
        b.onSample(m, e, 80, s)
        repeat(2) { m += 1.0; e += 0.20; b.onSample(m, e, 80, s) }
        assertEquals(20.0, b.shortAvgConsumption()!!, 0.1)
    }

    @Test fun `null totalElec pair is skipped`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(10001.0, null, 80, s)
        b.onSample(10002.0, 1500.36, 80, s)
        // Both pairs touch null, so totalKm = 0 → fallback
        assertEquals(18.0, b.recentAvgConsumption(), 0.001)
    }

    @Test fun `samples beyond WINDOW_KM trimmed on insert`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        var m = 10000.0; var e = 1500.0
        b.onSample(m, e, 80, s)
        repeat(50) { m += 1.0; e += 0.20; b.onSample(m, e, 80, s) }
        // newest mileage 10050; trim cutoff = 10050 - 25 - 1 = 10024
        val all = dao.snapshot()
        assertTrue("all samples within WINDOW_KM + hysteresis", all.all { it.mileageKm >= 10024.0 })
    }
}
