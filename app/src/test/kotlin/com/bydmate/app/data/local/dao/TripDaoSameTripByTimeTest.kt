package com.bydmate.app.data.local.dao

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

/** Two short drives minutes apart are two trips; only an unlinked trip is the same one by time. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripDaoSameTripByTimeTest {

    private lateinit var db: AppDatabase
    private val window = 300_000L
    private val t = 1_790_000_000_000L

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun teardown() { db.close() }

    @Test fun `a trip linked to another record minutes earlier is not the same trip`() = runBlocking {
        val dao = db.tripDao()
        dao.insert(TripEntity(startTs = t, bydId = 1L, distanceKm = 1.2, source = "energydata"))

        assertNull(dao.getSameTripByTime(t + 120_000L, window))
    }

    @Test fun `an unlinked trip inside the window is the same trip`() = runBlocking {
        val dao = db.tripDao()
        val live = dao.insert(TripEntity(startTs = t + 40_000L, distanceKm = 3.0, source = "live"))

        assertEquals(live, dao.getSameTripByTime(t, window)!!.id)
    }

    @Test fun `a linked trip does not hide an unlinked one in the same window`() = runBlocking {
        val dao = db.tripDao()
        dao.insert(TripEntity(startTs = t - 200_000L, bydId = 1L, distanceKm = 1.2, source = "energydata"))
        val live = dao.insert(TripEntity(startTs = t + 30_000L, distanceKm = 3.0, source = "live"))

        assertEquals(live, dao.getSameTripByTime(t, window)!!.id)
    }

    @Test fun `the nearest unlinked trip wins`() = runBlocking {
        val dao = db.tripDao()
        dao.insert(TripEntity(startTs = t - 250_000L, distanceKm = 2.0, source = "live"))
        val near = dao.insert(TripEntity(startTs = t + 10_000L, distanceKm = 3.0, source = "live"))

        assertEquals(near, dao.getSameTripByTime(t, window)!!.id)
    }

    @Test fun `a linked trip at the very same second is the same trip`() = runBlocking {
        val dao = db.tripDao()
        val same = dao.insert(TripEntity(startTs = t, bydId = 700L, distanceKm = 5.0, source = "energydata"))

        assertEquals(same, dao.getSameTripByTime(t, window)!!.id)
    }

    @Test fun `nothing outside the window`() = runBlocking {
        val dao = db.tripDao()
        dao.insert(TripEntity(startTs = t + window + 1L, distanceKm = 3.0, source = "live"))

        assertNull(dao.getSameTripByTime(t, window))
    }
}
