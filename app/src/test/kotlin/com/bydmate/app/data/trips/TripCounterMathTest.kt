package com.bydmate.app.data.trips

import com.bydmate.app.data.local.dao.TripCounterStats
import org.junit.Assert.assertEquals
import org.junit.Test

class TripCounterMathTest {

    // straddlingKm/Kwh/Ms = 0 when no straddling row has landed yet; landedSession* = 0
    private val emptyStats = TripCounterStats(0.0, 0.0, 0.0, 0.0, 0.0, 0, 0L, 0.0, 0.0, 0L, 0, 0.0, 0.0, 0L)

    private fun reset(
        ts: Long = 0L,
        km: Double = 0.0,
        kwh: Double = 0.0,
        ms: Long = 0L,
        excludeStraddling: Boolean = false,
    ) = TripResetState(ts, km, kwh, ms, excludeStraddling)

    @Test fun empty_history_is_all_zeros() {
        val ui = TripCounterMath.compute(emptyStats, reset(), null, null, null, false, 100L, 10.0)
        assertEquals(0.0, ui.km, 0.001)
        assertEquals(0.0, ui.kwh, 0.001)
        assertEquals(0.0, ui.cost, 0.001)
        assertEquals(0L, ui.drivingMs)
    }

    @Test fun completed_trips_pass_through() {
        val stats = TripCounterStats(40.0, 9.5, 1.9, 8.0, 1.5, 2, 3_000L, 0.0, 0.0, 0L, 0, 0.0, 0.0, 0L)
        val ui = TripCounterMath.compute(stats, reset(ts = 5_000L), null, null, null, false, 20_000L, 10.0)
        assertEquals(40.0, ui.km, 0.001)
        assertEquals(9.5, ui.kwh, 0.001)
        assertEquals(1.5, ui.idleKwh, 0.001)
        assertEquals(2, ui.tripCount)
    }

    @Test fun live_session_started_after_reset_adds_fully() {
        // Reset at t=5000 while parked; a new session started at t=6000, live 12 km / 2.4 kWh.
        val ui = TripCounterMath.compute(emptyStats, reset(ts = 5_000L), 12.0, 2.4, 6_000L, true, 9_000L, 10.0)
        assertEquals(12.0, ui.km, 0.001)
        assertEquals(2.4, ui.kwh, 0.001)
        assertEquals(24.0, ui.cost, 0.001)          // live kWh * tariff
        assertEquals(3_000L, ui.drivingMs)          // now - sessionStart
    }

    @Test fun mid_drive_reset_continuous_counts_only_post_reset_part_live() {
        // Session started at t=1000; reset at t=5000 captured 5 km / 1 kWh / 4000 ms.
        // liveWholeSession=true (continuous): straddling correction applies.
        // Live now: 12 km / 2.4 kWh -> counter shows the post-reset 7 km / 1.4 kWh.
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L)
        val ui = TripCounterMath.compute(emptyStats, r, 12.0, 2.4, 1_000L, true, 9_000L, 10.0)
        assertEquals(7.0, ui.km, 0.001)
        assertEquals(1.4, ui.kwh, 0.001)
        assertEquals(4_000L, ui.drivingMs)          // (now-sessionStart=8000) - corrMs(4000) = 4000
    }

    @Test fun straddling_trip_landed_in_room_gets_correction_subtracted() {
        // The trip the reset happened inside has landed whole (20 km / 4 kWh / cost 0.8 / 10000 ms);
        // correction captured at reset was 5 km / 1 kWh / 4000 ms.
        // straddlingKm=20 (the entire straddling row), cap = minOf(5, 20) = 5. Session over (live null).
        val stats = TripCounterStats(20.0, 4.0, 0.8, 4.0, 0.0, 1, 10_000L, 20.0, 4.0, 10_000L, 1, 0.0, 0.0, 0L)
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L)
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        assertEquals(15.0, ui.km, 0.001)
        assertEquals(3.0, ui.kwh, 0.001)
        assertEquals(0.6, ui.cost, 0.001)           // 0.8 - 1kWh*0.2
        assertEquals(6_000L, ui.drivingMs)
    }

    @Test fun counters_never_go_negative() {
        // Correction larger than everything (straddling trip never landed -- dead energydata).
        // straddlingKm=0 means no straddling row landed -> kmSub = minOf(50, 0) = 0.
        val r = reset(ts = 5_000L, km = 50.0, kwh = 10.0, ms = 99_000L)
        val ui = TripCounterMath.compute(emptyStats, r, null, null, null, false, 9_000L, 10.0)
        assertEquals(0.0, ui.km, 0.001)
        assertEquals(0.0, ui.kwh, 0.001)
        assertEquals(0.0, ui.cost, 0.001)
        assertEquals(0L, ui.drivingMs)
    }

    // --- Continuity gate (replaces epoch gate from Task 10) ---

    /**
     * liveWholeSession=false: after a mid-session restart where baselines could not be
     * recovered from persistence. Live km/kWh must be suppressed entirely (Room-only),
     * but time contribution is still live -- the clock is continuous across restarts.
     *
     * Old epoch formula: km=12 (taken as-is when epochs differ).
     * New formula: continuous=false -> live km/kWh = 0; Room 0 + 0 = 0.
     * Time: straddling -> (liveMs=8000) - corrMs(4000) - landedSession(0) = 4000.
     */
    @Test fun discontinuous_session_suppresses_live_km_kwh() {
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L)
        val ui = TripCounterMath.compute(emptyStats, r, 12.0, 2.4, 1_000L, false, 9_000L, 10.0)
        // Room is empty; live km/kWh are suppressed.
        assertEquals(0.0, ui.km, 0.001)
        assertEquals(0.0, ui.kwh, 0.001)
        // Time is continuous: straddling -> liveMs(8000) - corrMs(4000) - landedSession(0) = 4000.
        assertEquals(4_000L, ui.drivingMs)
    }

    /**
     * Major A (Codex audit round 4): landed session row of 40 min within the session window
     * (bound = sessionStart, continuous=true); live time = 60 min.
     * liveMsContrib = max(60 - 40, 0) = 20 min; drivingMs = Room 40 + live 20 = 60 min.
     *
     * Old formula (epoch > sessionStart -> landedFrom=epoch, row excluded): 40 + 60 = 100 min.
     */
    @Test fun codex_a_landed_row_windowed_to_session_start_gives_60_min() {
        val sessionStart = 0L
        val rowMs = 40L * 60_000L       // 40 min row duration
        val nowMs = 60L * 60_000L       // 60 min live session time
        val stats = TripCounterStats(
            totalKm = 10.0, totalKwh = 2.0, totalCost = 0.4,
            drivingKwh = 2.0, idleKwh = 0.0, tripCount = 1, drivingMs = rowMs,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 10.0, landedSessionKwh = 2.0, landedSessionMs = rowMs,
        )
        val r = reset(ts = 0L)    // resetTs=0, sessionStart=0: NOT straddling (0 < 0 is false)
        val ui = TripCounterMath.compute(stats, r, 10.0, 2.0, sessionStart, true, nowMs, 0.2)
        // drivingMs: no straddling -> (liveMs=60min - landedSession=40min) = 20min; + Room 40min = 60min.
        assertEquals(rowMs + (nowMs - rowMs), ui.drivingMs)   // 60 min total
        assertEquals(60L * 60_000L, ui.drivingMs)
    }

    /**
     * Major B restore-FAIL (Codex audit round 4): TripRecorder resumed a spanning row
     * (Room 11 km). Live shows 5 km post-restart delta. Without fix: 11 + 5 = 16.
     * With fix: liveWholeSession=false -> live suppressed -> 11 km.
     */
    @Test fun codex_b_restore_fail_live_suppressed_room_only_11km() {
        val stats = TripCounterStats(
            totalKm = 11.0, totalKwh = 2.2, totalCost = 0.44,
            drivingKwh = 2.2, idleKwh = 0.0, tripCount = 1, drivingMs = 660_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L,
        )
        val r = reset(ts = 0L)
        val ui = TripCounterMath.compute(stats, r, 5.0, 1.0, 1_000L, false, 10_000L, 0.2)
        // OLD: 11 + 5 = 16; NEW: 11 + 0 = 11.
        assertEquals(11.0, ui.km, 0.001)
        assertEquals(2.2, ui.kwh, 0.001)
    }

    /**
     * Major B restore-OK (Codex audit round 4): liveWholeSession=true, session baseline
     * covers the whole session (11 km). Landed session row also 11 km.
     * liveContrib = max(11 - 11, 0) = 0; total = Room 11 + 0 = 11.
     */
    @Test fun codex_b_restore_ok_spanner_row_deducted_total_11km() {
        val stats = TripCounterStats(
            totalKm = 11.0, totalKwh = 2.2, totalCost = 0.44,
            drivingKwh = 2.2, idleKwh = 0.0, tripCount = 1, drivingMs = 660_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 11.0, landedSessionKwh = 2.2, landedSessionMs = 660_000L,
        )
        val r = reset(ts = 0L)
        val nowMs = 660_000L
        val ui = TripCounterMath.compute(stats, r, 11.0, 2.2, 0L, true, nowMs, 0.2)
        // live remainder = max(11-11, 0) = 0; total = 11 + 0 = 11.
        assertEquals(11.0, ui.km, 0.001)
        assertEquals(2.2, ui.kwh, 0.001)
    }

    // --- Landed-session fix (Task 9) ---

    /**
     * Task 9 Major: DRIVE->ACC transition -- Room row landed (10 km), live still 10 km.
     * With the fix: live contribution = live(10) - landedSession(10) = 0; total = 10.
     */
    @Test fun drive_to_acc_landed_row_prevents_double_count() {
        val stats = TripCounterStats(
            totalKm = 10.0, totalKwh = 2.0, totalCost = 0.4, drivingKwh = 2.0, idleKwh = 0.0,
            tripCount = 1, drivingMs = 5_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 10.0, landedSessionKwh = 2.0, landedSessionMs = 5_000L,
        )
        val r = reset(ts = 0L)
        val ui = TripCounterMath.compute(stats, r, 10.0, 2.0, 1_000L, true, 10_000L, 0.2)
        // OLD formula: Room(10) + live(10) = 20 km; NEW: Room(10) + max(10-10, 0) = 10 km.
        assertEquals(10.0, ui.km, 0.001)
        assertEquals(2.0, ui.kwh, 0.001)
        // drivingMs: liveMs=9000, no straddling, landedSessionMs=5000 -> max(9000-5000, 0)=4000; Room 5000 total
        assertEquals(9_000L, ui.drivingMs)   // Room 5000 + live-remainder 4000
    }

    /**
     * Task 9: multi-trip per session -- first trip landed (10 km), live tracks second trip (15 km total session).
     * live contribution = 15 - 10 = 5; total = 10 + 5 = 15.
     */
    @Test fun multi_trip_session_live_is_remainder_above_landed() {
        val stats = TripCounterStats(
            totalKm = 10.0, totalKwh = 2.0, totalCost = 0.4, drivingKwh = 2.0, idleKwh = 0.0,
            tripCount = 1, drivingMs = 5_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 10.0, landedSessionKwh = 2.0, landedSessionMs = 5_000L,
        )
        val r = reset(ts = 0L)
        val ui = TripCounterMath.compute(stats, r, 15.0, 3.0, 1_000L, true, 20_000L, 0.2)
        assertEquals(15.0, ui.km, 0.001)
        assertEquals(3.0, ui.kwh, 0.001)
    }

    /**
     * Task 9: reset mid-drive + landed row for the current session.
     * Room: 10 km total (straddling 5 km pre-reset); landedSession=10 km.
     * Live: 10 km, continuous -> corrected = 10-5 = 5; landed deduction = min(10,10)=10 -> max(5-10,0)=0.
     * Total km = (Room 10 - straddlingSub 5) + liveRemainder 0 = 5.
     */
    @Test fun reset_mid_drive_with_landed_session_row() {
        val stats = TripCounterStats(
            totalKm = 10.0, totalKwh = 2.0, totalCost = 0.4, drivingKwh = 2.0, idleKwh = 0.0,
            tripCount = 1, drivingMs = 8_000L,
            straddlingKm = 10.0, straddlingKwh = 2.0, straddlingMs = 8_000L, straddlingTripCount = 1,
            landedSessionKm = 10.0, landedSessionKwh = 2.0, landedSessionMs = 8_000L,
        )
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L)
        // liveWholeSession=true; session started at t=1000 (before reset -> straddling).
        val ui = TripCounterMath.compute(stats, r, 10.0, 2.0, 1_000L, true, 9_000L, 0.2)
        // Room: (10 - min(5,10)) = 5; live: corrected=10-5=5, landed=10 -> max(5-10,0)=0.
        assertEquals(5.0, ui.km, 0.001)
        assertEquals(1.0, ui.kwh, 0.001)
        // drivingMs: straddling -> (liveMs=8000 - corrMs=4000 - landedSessionMs=8000) -> clamp 0; Room(8000-4000)=4000.
        assertEquals(4_000L, ui.drivingMs)
    }

    // --- Major #2 from earlier rounds (still tested) ---

    // --- Codex round 5 fixes ---

    /**
     * Major-1 (Codex round 5): reset during degraded session (excludeStraddling=true).
     * A spanner row of 15 km covers the reset. The straddling row is subtracted whole
     * (15 km) so the counter reads 0 -- not 14 km as the old corrKm=1 partial would give.
     */
    @Test fun codex_r5_major1_degraded_reset_spanner_whole_row_excluded() {
        // Stats: the 15-km spanner row landed in Room, whole row is straddling.
        val stats = TripCounterStats(
            totalKm = 15.0, totalKwh = 3.0, totalCost = 0.6,
            drivingKwh = 3.0, idleKwh = 0.0, tripCount = 1, drivingMs = 900_000L,
            straddlingKm = 15.0, straddlingKwh = 3.0, straddlingMs = 900_000L, straddlingTripCount = 1,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L,
        )
        // Degraded reset: corrKm=0 (no valid live partial), excludeStraddling=true.
        val r = reset(ts = 5_000L, km = 0.0, kwh = 0.0, ms = 5_000L, excludeStraddling = true)
        // Session ended (liveWholeSession=false, no live).
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        // kmSub = straddlingKm (15) -> totalKm(15) - 15 = 0.
        assertEquals(0.0, ui.km, 0.001)
        assertEquals(0.0, ui.kwh, 0.001)
        assertEquals(0.0, ui.cost, 0.001)
        // spanner excluded whole -> tripCount = (1 - 1) = 0: "counting restarts from next trip".
        assertEquals(0, ui.tripCount)
    }

    /**
     * Major-1 continuation: next trip of 4 km lands after the degraded reset.
     * The new row is NOT straddling (starts after resetTs), so no subtraction applies.
     * Counter must show 4 km exactly.
     */
    @Test fun codex_r5_major1_degraded_reset_next_trip_shows_4km() {
        // Both the original 15-km spanner and a subsequent 4-km trip have landed.
        // The spanner is straddlingKm=15; the 4-km row started after resetTs so it
        // is not straddling (the DAO only counts rows that started before resetTs as straddling).
        val stats = TripCounterStats(
            totalKm = 19.0, totalKwh = 3.8, totalCost = 0.76,
            drivingKwh = 3.8, idleKwh = 0.0, tripCount = 2, drivingMs = 1_140_000L,
            straddlingKm = 15.0, straddlingKwh = 3.0, straddlingMs = 900_000L, straddlingTripCount = 1,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L,
        )
        val r = reset(ts = 5_000L, km = 0.0, kwh = 0.0, ms = 5_000L, excludeStraddling = true)
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        // kmSub = straddlingKm(15) -> totalKm(19) - 15 = 4.
        assertEquals(4.0, ui.km, 0.001)
        assertEquals(0.8, ui.kwh, 0.001)
        // spanner excluded, next trip landed -> tripCount = (2 - 1) = 1.
        assertEquals(1, ui.tripCount)
    }

    /**
     * Major-2 (Codex round 5): same-process restart with load()==null, stale anchor in companion.
     * liveWholeSession=false, live 5 km present but suppressed -> counter shows Room-only 10.
     * Verifies that the degraded flag (not excludeStraddling) suppresses inflation mid-trip.
     */
    @Test fun codex_r5_major2_degraded_flag_live_suppressed_shows_room_only() {
        // 10 km landed in Room; live session shows another 5 km but coverage is degraded.
        val stats = TripCounterStats(
            totalKm = 10.0, totalKwh = 2.0, totalCost = 0.4,
            drivingKwh = 2.0, idleKwh = 0.0, tripCount = 1, drivingMs = 600_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L,
        )
        val r = reset(ts = 0L)
        // liveWholeSession=false -> live suppressed.
        val ui = TripCounterMath.compute(stats, r, 15.0, 3.0, 1_000L, false, 10_000L, 0.2)
        assertEquals(10.0, ui.km, 0.001)
        assertEquals(2.0, ui.kwh, 0.001)
    }

    /**
     * Major-2 convergence: second row lands (session ended), liveWholeSession no longer relevant.
     * totalKm now 15; counter must converge to 15.
     */
    @Test fun codex_r5_major2_degraded_converges_after_second_row_lands() {
        // Both rows have now landed: 10 km original + 5 km second trip = 15 km total.
        val stats = TripCounterStats(
            totalKm = 15.0, totalKwh = 3.0, totalCost = 0.6,
            drivingKwh = 3.0, idleKwh = 0.0, tripCount = 2, drivingMs = 900_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L,
        )
        val r = reset(ts = 0L)
        // Session ended (no live).
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        assertEquals(15.0, ui.km, 0.001)
        assertEquals(3.0, ui.kwh, 0.001)
    }

    /**
     * Regression: excludeStraddling=false keeps the existing capped-correction behaviour.
     * The spanner row (20 km), correction captured at reset = 5 km. kmSub = min(5, 20) = 5.
     * Same as the existing straddling_trip_landed_in_room_gets_correction_subtracted test.
     */
    @Test fun codex_r5_exclude_false_keeps_capped_correction() {
        val stats = TripCounterStats(20.0, 4.0, 0.8, 4.0, 0.0, 1, 10_000L, 20.0, 4.0, 10_000L, 1, 0.0, 0.0, 0L)
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L, excludeStraddling = false)
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        // Same as the existing test: kmSub = min(5, 20) = 5 -> 20 - 5 = 15.
        assertEquals(15.0, ui.km, 0.001)
        assertEquals(3.0, ui.kwh, 0.001)
        // excl=false: no tripCount subtraction -> tripCount = stats.tripCount = 1.
        assertEquals(1, ui.tripCount)
    }

    /**
     * Major #2: if the straddling trip never landed in Room (dead energydata),
     * the correction must not be subtracted from later trips.
     */
    @Test fun straddling_trip_never_landed_does_not_bite_later_trips() {
        val stats = TripCounterStats(20.0, 4.0, 0.8, 4.0, 0.0, 1, 10_000L,
            straddlingKm = 0.0, straddlingKwh = 0.0, straddlingMs = 0L, straddlingTripCount = 0,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L)
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L)
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        assertEquals(20.0, ui.km, 0.001)
        assertEquals(4.0, ui.kwh, 0.001)
        assertEquals(0.8, ui.cost, 0.001)
    }

    /**
     * Major #2 complement: straddling row landed with fewer km than the correction.
     */
    @Test fun straddling_trip_landed_subtraction_capped_at_row_km() {
        val stats = TripCounterStats(25.0, 5.0, 1.0, 5.0, 0.0, 2, 15_000L,
            straddlingKm = 3.0, straddlingKwh = 0.6, straddlingMs = 3_000L, straddlingTripCount = 1,
            landedSessionKm = 0.0, landedSessionKwh = 0.0, landedSessionMs = 0L)
        val r = reset(ts = 5_000L, km = 5.0, kwh = 1.0, ms = 4_000L)
        val ui = TripCounterMath.compute(stats, r, null, null, null, false, 30_000L, 0.2)
        assertEquals(22.0, ui.km, 0.001)
        assertEquals(4.4, ui.kwh, 0.001)       // 5 - minOf(1, 0.6) = 5 - 0.6 = 4.4
        assertEquals(12_000L, ui.drivingMs)    // 15000 - minOf(4000, 3000) = 15000 - 3000 = 12000
    }
}
