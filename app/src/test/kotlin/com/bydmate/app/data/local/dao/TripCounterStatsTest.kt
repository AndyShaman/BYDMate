package com.bydmate.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripCounterStatsTest {

    private lateinit var db: AppDatabase

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After fun tearDown() = db.close()

    @Test
    fun counter_stats_buckets_and_end_ts_filter() = runBlocking {
        val dao = db.tripDao()
        // Ended BEFORE `from` -> excluded entirely.
        dao.insert(TripEntity(startTs = 1_000L, endTs = 2_000L, distanceKm = 99.0, kwhConsumed = 20.0, cost = 4.0, source = "energydata"))
        // Straddling: started before `from`, ended after -> included WHOLE (caller subtracts correction).
        dao.insert(TripEntity(startTs = 4_000L, endTs = 6_000L, distanceKm = 10.0, kwhConsumed = 2.0, cost = 0.4, source = "energydata"))
        // Fully after `from`.
        dao.insert(TripEntity(startTs = 7_000L, endTs = 8_000L, distanceKm = 30.0, kwhConsumed = 6.0, cost = 1.2, source = "energydata"))
        // Idle (parking drain) row after `from`: zero km, kWh only.
        dao.insert(TripEntity(startTs = 9_000L, endTs = 10_000L, distanceKm = 0.0, kwhConsumed = 1.5, cost = 0.3, source = "energydata"))
        // Open-ended row (null end_ts, live GPS shell): must not crash, excluded from drivingMs.
        dao.insert(TripEntity(startTs = 11_000L, endTs = null, distanceKm = null, kwhConsumed = null, cost = null, source = "live"))

        // sessionStart = Long.MAX_VALUE (no active session) → landedSession* must be 0.
        val stats = dao.observeCounterStats(5_000L, Long.MAX_VALUE).first()
        assertEquals(40.0, stats.totalKm, 0.001)        // 10 + 30
        assertEquals(9.5, stats.totalKwh, 0.001)        // 2 + 6 + 1.5
        assertEquals(1.9, stats.totalCost, 0.001)       // 0.4 + 1.2 + 0.3
        assertEquals(8.0, stats.drivingKwh, 0.001)      // 2 + 6
        assertEquals(1.5, stats.idleKwh, 0.001)
        assertEquals(2, stats.tripCount)                // driving rows only, idle row is not a trip
        assertEquals(3_000L, stats.drivingMs)           // (6000-4000) + (8000-7000)

        // Straddling buckets: the driving row that started before `from=5000` is (start=4000, end=6000, 10km, 2kWh).
        // The idle row (distanceKm=0) and rows starting after `from` are NOT counted.
        assertEquals(10.0, stats.straddlingKm, 0.001)
        assertEquals(2.0, stats.straddlingKwh, 0.001)
        assertEquals(2_000L, stats.straddlingMs)        // 6000-4000

        // No active session (Long.MAX_VALUE): landedSession* must all be zero.
        assertEquals(0.0, stats.landedSessionKm, 0.001)
        assertEquals(0.0, stats.landedSessionKwh, 0.001)
        assertEquals(0L, stats.landedSessionMs)
    }

    @Test
    fun straddling_idle_row_not_counted_in_straddling_buckets() = runBlocking {
        val dao = db.tripDao()
        // Idle row (distanceKm=0) started before `from` but ended after `from` — must not appear in straddling buckets.
        dao.insert(TripEntity(startTs = 3_000L, endTs = 7_000L, distanceKm = 0.0, kwhConsumed = 1.0, cost = 0.2, source = "energydata"))
        // Driving row fully after `from`.
        dao.insert(TripEntity(startTs = 6_000L, endTs = 8_000L, distanceKm = 10.0, kwhConsumed = 2.0, cost = 0.4, source = "energydata"))

        val stats = dao.observeCounterStats(5_000L, Long.MAX_VALUE).first()
        // The idle row straddles but must not contribute to straddlingKm (driving-only gate).
        assertEquals(0.0, stats.straddlingKm, 0.001)
        assertEquals(0.0, stats.straddlingKwh, 0.001)
        assertEquals(0L, stats.straddlingMs)
    }

    /**
     * Task 9: landed-session bucket — row with start_ts >= sessionStart appears in landedSession*.
     * Row earlier than sessionStart must NOT appear; idle row of current session must NOT appear.
     */
    @Test
    fun landed_session_buckets_include_only_current_session_driving_rows() = runBlocking {
        val dao = db.tripDao()
        val sessionStart = 10_000L
        val from = 5_000L

        // Driving row from a PREVIOUS session (start_ts < sessionStart) — must NOT appear in landedSession*.
        dao.insert(TripEntity(startTs = 6_000L, endTs = 8_000L, distanceKm = 20.0, kwhConsumed = 4.0, cost = 0.8, source = "energydata"))
        // Driving row from the CURRENT session (start_ts >= sessionStart) — must appear in landedSession*.
        dao.insert(TripEntity(startTs = 11_000L, endTs = 13_000L, distanceKm = 10.0, kwhConsumed = 2.0, cost = 0.4, source = "energydata"))
        // Idle row from the CURRENT session (distanceKm=0) — must NOT appear in landedSession* (driving-only gate).
        dao.insert(TripEntity(startTs = 14_000L, endTs = 15_000L, distanceKm = 0.0, kwhConsumed = 0.5, cost = 0.1, source = "energydata"))

        val stats = dao.observeCounterStats(from, sessionStart).first()

        // Only the current-session driving row counts.
        assertEquals(10.0, stats.landedSessionKm, 0.001)
        assertEquals(2.0, stats.landedSessionKwh, 0.001)
        assertEquals(2_000L, stats.landedSessionMs)   // 13000-11000

        // Sanity: totalKm includes both driving rows (both end after `from`).
        assertEquals(30.0, stats.totalKm, 0.001)
    }
}
