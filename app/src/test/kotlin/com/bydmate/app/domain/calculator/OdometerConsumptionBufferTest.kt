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

    @Test fun `huge forward jump wipes stale baseline and re-anchors`() = runBlocking {
        // v2.4.8: silent-skip on jump > 100 km used to permanently freeze the
        // buffer if the very first row was a DiPars startup glitch. Now we
        // discard the stale baseline and re-anchor on the real reading.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(5.0, 1500.0, 80, s)        // legacy poisoned row (would not pass new guard, simulates pre-v2.4.8 leftover)
        b.onSample(10500.0, 1600.0, 70, s)    // real odometer arrives
        assertEquals(1, dao.count())
        assertEquals(10500.0, dao.snapshot().single().mileageKm, 0.001)
    }

    @Test fun `mileage below MIN_VALID_ODOMETER_KM rejected at insert`() = runBlocking {
        // v2.4.8: DiPars startup race returns Mileage:0 (and sometimes small
        // fractional values) before the CAN bus delivers the real odometer.
        // No real BYD vehicle reports an odometer below 1 km — drop those.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(0.0, 1500.0, 80, s)
        b.onSample(0.5, 1500.05, 80, s)
        b.onSample(0.99, 1500.10, 80, s)
        assertEquals(0, dao.count())
        // Real reading after the glitch settles must anchor cleanly.
        b.onSample(2075.8, 1500.20, 80, s)
        assertEquals(1, dao.count())
        assertEquals(2075.8, dao.snapshot().single().mileageKm, 0.001)
    }

    @Test fun `cleanupCorruptStartupRows wipes when oldest row is sub-1-km`() = runBlocking {
        // v2.4.8: one-shot recovery for users upgrading from v2.4.5–v2.4.7
        // who already have a poisoned buffer on disk. Buffer is wiped so the
        // next sample anchors freshly.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        // Simulate legacy poisoned state — bypass guard via direct DAO insert.
        dao.insert(com.bydmate.app.data.local.entity.OdometerSampleEntity(
            mileageKm = 0.0, totalElecKwh = 1500.0, socPercent = 80, sessionId = 1L,
            timestamp = System.currentTimeMillis()
        ))
        dao.insert(com.bydmate.app.data.local.entity.OdometerSampleEntity(
            mileageKm = 0.0, totalElecKwh = 1500.05, socPercent = 80, sessionId = 1L,
            timestamp = System.currentTimeMillis()
        ))
        val cleared = b.cleanupCorruptStartupRows()
        assertEquals(2, cleared)
        assertEquals(0, dao.count())
    }

    @Test fun `cleanupCorruptStartupRows is no-op for healthy buffer`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(10001.0, 1500.18, 80, s)
        val cleared = b.cleanupCorruptStartupRows()
        assertEquals(0, cleared)
        assertEquals(2, dao.count())
    }

    @Test fun `startup race - zero mileage rejected then real reading anchors buffer`() = runBlocking {
        // Full-flow regression for the v2.4.7 widget-trend report: Mileage:0
        // on first DiPars poll, real odometer ~2076 km on subsequent polls.
        // Pre-v2.4.8 this would freeze recentAvg on the EMA fallback forever.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 28.13, dao = dao)
        val s = 1L
        // First few polls glitched.
        b.onSample(0.0, 598.0, 97, s)
        b.onSample(0.0, 598.0, 97, s)
        b.onSample(0.0, 598.0, 97, s)
        assertEquals(0, dao.count())
        // CAN bus settles — real odometer + driving for 15 km.
        var m = 2075.8; var e = 598.0
        b.onSample(m, e, 97, s)
        repeat(30) { m += 0.5; e += 0.095; b.onSample(m, e, 97, s) }
        assertTrue("buffer must accumulate samples after recovery", dao.count() > 10)
        // recentAvg must now be the real rolling number, not the EMA fallback.
        assertEquals(19.0, b.recentAvgConsumption(), 0.5)
        // shortAvg must be available so the trend arrow can compute.
        assertTrue("shortAvg must be non-null after >= 2 km", b.shortAvgConsumption() != null)
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

    @Test fun `short avg null when distance under MIN_SHORT_BUFFER_KM`() = runBlocking {
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        b.onSample(10000.0, 1500.0, 80, s)
        b.onSample(10001.0, 1500.18, 80, s)
        // 1.0 km of pair data — below the 1.5 km MIN_SHORT_BUFFER_KM threshold.
        assertNull(b.shortAvgConsumption())
    }

    @Test fun `short avg null just below MIN_SHORT_BUFFER_KM threshold`() = runBlocking {
        // Fixes the threshold introduced in v2.4.12: short window must accept
        // 1.5 km of pair data but reject anything below.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        var m = 10000.0; var e = 1500.0
        b.onSample(m, e, 80, s)
        repeat(14) { m += 0.1; e += 0.02; b.onSample(m, e, 80, s) }
        // 14 × 0.1 = 1.4 km — just under MIN_SHORT_BUFFER_KM.
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

    @Test fun `short avg stays available during continuous driving with irregular ticks`() = runBlocking {
        // Repro for the v2.4.11 widget grey-arrow report (2026-04-25 drive):
        // user reported the consumption number going grey without an arrow for
        // long stretches of a 10-km drive. Root cause: windowFrom(newest - 2.0)
        // returns samples with mileage >= newest - 2.0, but the oldest such
        // sample is almost never sitting EXACTLY on that boundary — typical
        // poll intervals at real-world speeds yield non-divisible mileage steps,
        // so the oldest in-window sample lies a hair above (newest - 2.0). That
        // makes totalKm = (last - oldest_in_window) < 2.0, which trips the
        // `totalKm < windowKm` guard and returns NaN → ConsumptionAggregator
        // sees null shortAvg and publishes Trend.NONE → grey number, no arrow.
        // Once the window slides far enough that an older sample lands ON the
        // boundary by coincidence, the value appears again. Hence the
        // intermittent "grey/coloured/grey" oscillation the user observed.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        var m = 10000.0; var e = 1500.0
        val step = 0.07          // ~3-sec poll @ 84 km/h, realistic spacing
        val kwhStep = 0.014      // 20 kWh/100km
        b.onSample(m, e, 80, s)
        // Drive 5 km of continuous samples — well past the 2 km short-window threshold.
        repeat(75) {
            m += step
            e += kwhStep
            b.onSample(m, e, 80, s)
        }
        // After 5 km of uninterrupted driving, short window MUST have a value;
        // a null here means the widget shows a grey number with no arrow.
        assertTrue(
            "shortAvg should be non-null after 5 km of continuous driving (was null because of boundary case)",
            b.shortAvgConsumption() != null,
        )
    }

    @Test fun `short avg survives a regen tail thanks to 3 km window`() = runBlocking {
        // Repro for the 2026-06 grey-number report: on descents / stop-and-go the
        // trailing 2 km holds regen pairs (dKwh < 0, skipped during averaging),
        // starving the short window below MIN_SHORT_BUFFER_KM. shortAvg went null
        // and the widget number greyed out mid-trip even though it kept changing.
        // A 3 km short window reaches back past the regen tail to >= 1.5 km of
        // valid pair data, so the trend (and now the decoupled number colour)
        // stay alive.
        val dao = FakeOdometerSampleDao()
        val b = newBuffer(fallback = 18.0, dao = dao)
        val s = 1L
        // 2 km of valid driving at 20 kWh/100km.
        b.onSample(10002.0, 1500.00, 80, s)
        b.onSample(10002.5, 1500.10, 80, s)
        b.onSample(10003.0, 1500.20, 80, s)
        b.onSample(10003.5, 1500.30, 80, s)
        b.onSample(10004.0, 1500.40, 80, s)
        // 1 km of regen tail — both pairs have dKwh < 0 and are skipped.
        b.onSample(10004.5, 1500.30, 80, s)
        b.onSample(10005.0, 1500.20, 80, s)
        // Trailing 2 km = only ~1 km of valid pairs (< 1.5 km) → null at a 2 km
        // window; the 3 km window recovers the 2 km of valid data behind the tail.
        val short = b.shortAvgConsumption()
        assertTrue("shortAvg must survive the regen tail with a 3 km window", short != null)
        assertEquals(20.0, short!!, 0.1)
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
