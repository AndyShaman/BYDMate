package com.bydmate.app.domain.calculator

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Contract for the redesigned aggregator (v2.4.4):
 *
 * - Session boundary is driven by TrackingService (powerState + tripTracker fallback).
 *   Aggregator receives `sessionStartedAt: Long?` as a stable session ID — when it
 *   changes, a new session starts and all per-session state resets.
 *
 * - Display value:
 *     * sessionStartedAt == null → baseline EMA (idle ambient), or null if EMA is 0.
 *     * Active session, cum distance < 0.5 km → null (first 500 m suppressed).
 *     * Active session, 0.5 km ≤ cum dist < 5 km → cumulative session average
 *       (totalElec since start / km since start × 100).
 *     * Active session, cum dist ≥ 5 km → rolling window over the last ≥5 km.
 *
 * - Trend arrow: only when cum dist ≥ 2 km. Compares displayed value to baselineEma
 *   with hysteresis + 60 sec debounce (unchanged from previous version).
 *
 * - Persistence: SessionBaseline snapshot exposed via currentSessionBaseline(), and
 *   restorable via persistedBaseline argument on the next onSample.
 */
class ConsumptionAggregatorTest {

    @Before fun reset() { ConsumptionAggregator.reset() }
    @After fun teardown() { ConsumptionAggregator.reset() }

    private val baselineEma = 18.0
    private fun sample(
        now: Long,
        sessionStart: Long?,
        km: Double?,
        kwh: Double?,
        baseline: Double = baselineEma,
        persisted: SessionBaseline? = null,
    ) {
        ConsumptionAggregator.onSample(now, sessionStart, km, kwh, baseline, persisted)
    }

    // ---------- Idle (no session) ----------

    @Test fun `no session — show baseline EMA, no arrow`() {
        sample(now = 1_000, sessionStart = null, km = 12_345.0, kwh = 1500.0)
        val state = ConsumptionAggregator.state.value
        assertEquals(baselineEma, state.displayValue!!, 0.001)
        assertEquals(Trend.NONE, state.trend)
    }

    @Test fun `no session and no baseline history — display null`() {
        sample(now = 1_000, sessionStart = null, km = 0.0, kwh = 0.0, baseline = 0.0)
        val state = ConsumptionAggregator.state.value
        assertNull(state.displayValue)
        assertEquals(Trend.NONE, state.trend)
    }

    // ---------- First 500 m suppression ----------

    @Test fun `active session, first 400 m — display null`() {
        val s = 1_000L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        sample(now = s + 60_000, sessionStart = s, km = 100.4, kwh = 1000.08)
        val state = ConsumptionAggregator.state.value
        assertNull(state.displayValue)
        assertEquals(Trend.NONE, state.trend)
    }

    // ---------- Cumulative mode (0.5 km — 5 km) ----------

    @Test fun `cum 600 m — show cumulative avg, no arrow`() {
        val s = 0L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        // 0.6 km driven, 0.12 kWh → 20 kWh/100km
        sample(now = s + 120_000, sessionStart = s, km = 100.6, kwh = 1000.12)
        val state = ConsumptionAggregator.state.value
        assertEquals(20.0, state.displayValue!!, 0.001)
        assertEquals(Trend.NONE, state.trend)   // < 2 km → arrow off
    }

    @Test fun `cum 3 km — show cumulative avg, trend gate open`() {
        val s = 0L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        // 3 km driven, 0.48 kWh → 16 kWh/100km
        sample(now = s + 240_000, sessionStart = s, km = 103.0, kwh = 1000.48)
        val state = ConsumptionAggregator.state.value
        assertEquals(16.0, state.displayValue!!, 0.001)
    }

    // ---------- Rolling window (≥ 5 km) ----------

    @Test fun `rolling window isolates recent 5 km from early heavy segment`() {
        val s = 0L
        // first tick — session baseline captured from this sample
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        // segment 1: 0 → 2 km at 30 kWh/100 → elec += 0.6
        sample(now = s + 120_000, sessionStart = s, km = 102.0, kwh = 1000.6)
        // segment 2: 2 → 8 km at 12 kWh/100 → elec += 0.72
        //   total cum: 8 km / 1.32 kWh = 16.5 avg
        //   last 5 km: 3 → 8 km at 12 kWh/100 → 12.0 avg  (segment 2 is uniform)
        sample(now = s + 720_000, sessionStart = s, km = 108.0, kwh = 1001.32)
        val display = ConsumptionAggregator.state.value.displayValue!!
        // Rolling should follow recent 5 km, not the whole trip
        assertEquals(12.0, display, 0.5)
    }

    @Test fun `rolling window follows a style shift toward higher consumption`() {
        val s = 0L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        // 10 km at 15 kWh/100 → elec += 1.5
        sample(now = s + 600_000, sessionStart = s, km = 110.0, kwh = 1001.5)
        // +5 km at 25 kWh/100 → elec += 1.25 (total 15 km, 2.75 kWh, avg 18.3)
        // last 5 km should be 25
        sample(now = s + 900_000, sessionStart = s, km = 115.0, kwh = 1002.75)
        val display = ConsumptionAggregator.state.value.displayValue!!
        assertEquals(25.0, display, 1.0)
    }

    // ---------- Trend debounce ----------

    @Test fun `trend stays NONE below 2 km even when rolling ratio would trigger`() {
        val s = 0L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        // 1.5 km, 0.225 kWh → 15 kWh/100km (ratio 0.833 < 0.95, but km < 2)
        sample(now = s + 240_000, sessionStart = s, km = 101.5, kwh = 1000.225)
        assertEquals(15.0, ConsumptionAggregator.state.value.displayValue!!, 0.001)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
    }

    @Test fun `trend commits DOWN after 60 sec of below-threshold ratio`() {
        val s = 0L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        // jump to 3 km at 15 kWh/100 (ratio 0.833)
        sample(now = s + 240_000, sessionStart = s, km = 103.0, kwh = 1000.45)
        val t0 = s + 240_000
        // 58 sec later — still candidate, not committed
        sample(now = t0 + 58_000, sessionStart = s, km = 104.0, kwh = 1000.6)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        // 62 sec — debounce passed
        sample(now = t0 + 62_000, sessionStart = s, km = 104.2, kwh = 1000.63)
        assertEquals(Trend.DOWN, ConsumptionAggregator.state.value.trend)
    }

    // ---------- Session change ----------

    @Test fun `new session id resets rolling state`() {
        val s1 = 1_000L
        sample(now = s1, sessionStart = s1, km = 100.0, kwh = 1000.0)
        sample(now = s1 + 600_000, sessionStart = s1, km = 110.0, kwh = 1001.5) // 10 km at 15

        // Session ended, then new session
        sample(now = s1 + 700_000, sessionStart = null, km = 110.0, kwh = 1001.5)
        val s2 = s1 + 800_000
        sample(now = s2, sessionStart = s2, km = 200.0, kwh = 2000.0)
        // Only 0.3 km into new session → first-500m-suppressed null
        sample(now = s2 + 60_000, sessionStart = s2, km = 200.3, kwh = 2000.06)
        assertNull(ConsumptionAggregator.state.value.displayValue)
    }

    // ---------- Persistence ----------

    @Test fun `currentSessionBaseline null when no active session`() {
        sample(now = 1_000, sessionStart = null, km = 100.0, kwh = 1000.0)
        assertNull(ConsumptionAggregator.currentSessionBaseline())
    }

    @Test fun `currentSessionBaseline exposes captured session start mileage and elec`() {
        val s = 5_000L
        sample(now = s, sessionStart = s, km = 12_345.0, kwh = 1500.0)
        val snap = ConsumptionAggregator.currentSessionBaseline()
        assertNotNull(snap)
        assertEquals(s, snap!!.sessionStartedAt)
        assertEquals(12_345.0, snap.mileageStart, 0.0001)
        assertEquals(1500.0, snap.totalElecStart, 0.0001)
    }

    @Test fun `persisted baseline restores cumulative from ignition-on across process restart`() {
        val s = 5_000L
        // Simulate: process restarted mid-trip. On the first sample after restart,
        // TrackingService hands us the persisted baseline captured before the kill.
        val persisted = SessionBaseline(
            sessionStartedAt = s,
            mileageStart = 100.0,
            totalElecStart = 1000.0,
        )
        // First tick after restart: odometer is already 103 km, elec is already 1000.48 kWh.
        sample(
            now = s + 700_000, sessionStart = s,
            km = 103.0, kwh = 1000.48,
            persisted = persisted,
        )
        // Without persistence, aggregator would capture (103, 1000.48) as baseline
        // and show null (cum=0). With persistence it knows we've already done 3 km
        // and 0.48 kWh since ignition → 16 kWh/100km.
        assertEquals(16.0, ConsumptionAggregator.state.value.displayValue!!, 0.01)
    }

    @Test fun `persisted baseline ignored when sessionStartedAt does not match`() {
        val persisted = SessionBaseline(
            sessionStartedAt = 1_000L,
            mileageStart = 100.0,
            totalElecStart = 1000.0,
        )
        // Different session id → aggregator must treat this as fresh.
        sample(now = 9_000L, sessionStart = 9_000L, km = 200.0, kwh = 2000.0, persisted = persisted)
        val snap = ConsumptionAggregator.currentSessionBaseline()
        assertEquals(9_000L, snap!!.sessionStartedAt)
        assertEquals(200.0, snap.mileageStart, 0.0001)
    }

    // ---------- reset ----------

    @Test fun `reset clears everything`() {
        val s = 0L
        sample(now = s, sessionStart = s, km = 100.0, kwh = 1000.0)
        sample(now = s + 240_000, sessionStart = s, km = 103.0, kwh = 1000.48)
        ConsumptionAggregator.reset()
        assertNull(ConsumptionAggregator.state.value.displayValue)
        assertEquals(Trend.NONE, ConsumptionAggregator.state.value.trend)
        assertNull(ConsumptionAggregator.currentSessionBaseline())
    }
}
