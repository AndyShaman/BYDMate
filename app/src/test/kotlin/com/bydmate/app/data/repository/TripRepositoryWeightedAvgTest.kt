package com.bydmate.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
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
class TripRepositoryWeightedAvgTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TripRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = TripRepository(db.tripDao(), db.tripPointDao())
    }

    @After fun teardown() { db.close() }

    @Test fun `empty DB returns fallback`() = runBlocking {
        val result = repo.getWeightedHistoricalAvg(fallback = 18.0)
        assertEquals(18.0, result, 0.001)
    }

    @Test fun `three trips weights 50-30-20`() = runBlocking {
        // Newest-first order in DB achieved by startTs. DAO returns newest first.
        // Trip at startTs=3000 → 30 kWh/100km (newest, weight 0.5)
        // Trip at startTs=2000 → 20 kWh/100km (middle, weight 0.3)
        // Trip at startTs=1000 → 10 kWh/100km (oldest, weight 0.2)
        // Expected: 0.5*30 + 0.3*20 + 0.2*10 = 15 + 6 + 2 = 23.0
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 1.0))
        repo.insertTrip(TripEntity(startTs = 2000, endTs = 3000, distanceKm = 10.0, kwhConsumed = 2.0))
        repo.insertTrip(TripEntity(startTs = 3000, endTs = 4000, distanceKm = 10.0, kwhConsumed = 3.0))
        val result = repo.getWeightedHistoricalAvg(
            minKm = 3.0,
            weights = listOf(0.5, 0.3, 0.2),
            fallback = 18.0,
        )
        assertEquals(23.0, result, 0.001)
    }

    @Test fun `single trip gets full weight`() = runBlocking {
        // Only one trip: 25 kWh/100km. Weights renormalized to [1.0]. Expected: 25.0
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 2.5))
        val result = repo.getWeightedHistoricalAvg(
            minKm = 3.0,
            weights = listOf(0.5, 0.3, 0.2),
            fallback = 18.0,
        )
        assertEquals(25.0, result, 0.001)
    }

    @Test fun `two trips renormalized 5-8 + 3-8`() = runBlocking {
        // Two trips, weights 50/30/20 truncated to [0.5, 0.3] → sum=0.8
        // Newest (startTs=2000): 30 kWh/100km → weight 0.5/0.8 = 0.625
        // Older  (startTs=1000): 20 kWh/100km → weight 0.3/0.8 = 0.375
        // Expected: 0.625*30 + 0.375*20 = 18.75 + 7.5 = 26.25
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 2.0))
        repo.insertTrip(TripEntity(startTs = 2000, endTs = 3000, distanceKm = 10.0, kwhConsumed = 3.0))
        val result = repo.getWeightedHistoricalAvg(
            minKm = 3.0,
            weights = listOf(0.5, 0.3, 0.2),
            fallback = 18.0,
        )
        assertEquals(26.25, result, 0.001)
    }

    @Test fun `minKm filter excludes short trips`() = runBlocking {
        // Trip at startTs=3000: 2 km — FAILS minKm=3, excluded
        // Trip at startTs=2000: 10 km @ 20 kWh/100km — newest PASSING (weight 0.5)
        // Trip at startTs=1000: 10 km @ 30 kWh/100km — older  PASSING (weight 0.3)
        // Two trips, weights truncated to [0.5, 0.3] → sum=0.8
        // Expected: 0.625*20 + 0.375*30 = 12.5 + 11.25 = 23.75
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 3.0))
        repo.insertTrip(TripEntity(startTs = 2000, endTs = 3000, distanceKm = 10.0, kwhConsumed = 2.0))
        repo.insertTrip(TripEntity(startTs = 3000, endTs = 4000, distanceKm = 2.0,  kwhConsumed = 0.5))
        val result = repo.getWeightedHistoricalAvg(
            minKm = 3.0,
            weights = listOf(0.5, 0.3, 0.2),
            fallback = 18.0,
        )
        assertEquals(23.75, result, 0.001)
    }
}
