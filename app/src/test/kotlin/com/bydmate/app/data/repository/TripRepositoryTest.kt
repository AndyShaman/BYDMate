package com.bydmate.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.TripEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var repo: TripRepository

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = TripRepository(db.tripDao(), db.tripPointDao(), db.tripTombstoneDao(), db)
    }

    @After fun teardown() { db.close() }

    @Test fun `empty DB returns null`() = runBlocking {
        assertNull(repo.getLastTripAvgConsumption())
    }

    @Test fun `single trip returns kwh per km times 100`() = runBlocking {
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 1.8))
        val result = repo.getLastTripAvgConsumption()
        assertEquals(18.0, result!!, 0.001)
    }

    @Test fun `most recent trip wins`() = runBlocking {
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 1.8))
        repo.insertTrip(TripEntity(startTs = 3000, endTs = 4000, distanceKm = 20.0, kwhConsumed = 5.0))
        val result = repo.getLastTripAvgConsumption()
        assertEquals(25.0, result!!, 0.001)
    }

    @Test fun `trip with null distance is skipped`() = runBlocking {
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 1.8))
        repo.insertTrip(TripEntity(startTs = 3000, endTs = 4000, distanceKm = null, kwhConsumed = 5.0))
        val result = repo.getLastTripAvgConsumption()
        assertEquals(18.0, result!!, 0.001)
    }

    @Test fun `trip with null kwh is skipped`() = runBlocking {
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 1.8))
        repo.insertTrip(TripEntity(startTs = 3000, endTs = 4000, distanceKm = 20.0, kwhConsumed = null))
        val result = repo.getLastTripAvgConsumption()
        assertEquals(18.0, result!!, 0.001)
    }

    @Test fun `trip below 1 km is skipped`() = runBlocking {
        repo.insertTrip(TripEntity(startTs = 1000, endTs = 2000, distanceKm = 10.0, kwhConsumed = 1.8))
        repo.insertTrip(TripEntity(startTs = 3000, endTs = 4000, distanceKm = 0.4, kwhConsumed = 0.5))
        val result = repo.getLastTripAvgConsumption()
        assertEquals(18.0, result!!, 0.001)
    }
}
