package com.bydmate.app.data.local.dao

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.LastStateEntity
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
class LastStateDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var dao: LastStateDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.lastStateDao()
    }

    @After fun tearDown() = db.close()

    @Test fun `empty table returns null`() = runBlocking {
        assertNull(dao.getCurrent())
    }

    @Test fun `upsert replaces row with same id`() = runBlocking {
        dao.upsert(LastStateEntity(id = 1, ts = 1000L, soc = 80))
        dao.upsert(LastStateEntity(id = 1, ts = 2000L, soc = 75, openTripId = 42L))
        val row = dao.getCurrent()
        assertEquals(2000L, row?.ts)
        assertEquals(75, row?.soc)
        assertEquals(42L, row?.openTripId)
    }

    @Test fun `clearOpenTrip nulls the trip fields only`() = runBlocking {
        dao.upsert(
            LastStateEntity(
                id = 1, ts = 2000L, soc = 75,
                openTripId = 42L, tripStartTs = 1000L,
                tripStartSoc = 80, tripStartMileage = 12345.6,
                tripStartTotalElec = 499.5, tripStartExteriorTemp = 10
            )
        )
        dao.clearOpenTrip()
        val row = dao.getCurrent()!!
        assertNull(row.openTripId)
        assertNull(row.tripStartTs)
        assertNull(row.tripStartSoc)
        assertNull(row.tripStartMileage)
        assertNull(row.tripStartTotalElec)
        assertNull(row.tripStartExteriorTemp)
        assertEquals(75, row.soc) // unrelated fields untouched
    }

    @Test fun `openTrip stores trip_start_total_elec`() = runBlocking {
        dao.upsert(LastStateEntity(id = 1, ts = 1000L, soc = 80))
        dao.openTrip(
            startTs = 1000L, startSoc = 80, startMileage = 100.0, startTotalElec = 499.5,
            startExteriorTemp = null, now = 1000L,
        )
        assertEquals(499.5, dao.getCurrent()!!.tripStartTotalElec!!, 0.001)
    }

    @Test fun `openTrip stores trip_start_exterior_temp`() = runBlocking {
        dao.upsert(LastStateEntity(id = 1, ts = 1000L, soc = 80))
        dao.openTrip(
            startTs = 1000L, startSoc = 80, startMileage = 100.0, startTotalElec = null,
            startExteriorTemp = -7, now = 1000L,
        )
        assertEquals(-7, dao.getCurrent()!!.tripStartExteriorTemp)
    }

    @Test fun `writeSnapshot after openTrip leaves the trip columns untouched`() = runBlocking {
        dao.upsert(LastStateEntity(id = 1, ts = 1000L, soc = 80, mileage = 100.0))
        dao.openTrip(
            startTs = 1500L, startSoc = 80, startMileage = 100.0, startTotalElec = 500.0,
            startExteriorTemp = 10, now = 1500L,
        )
        dao.writeSnapshot(
            ts = 2000L, soc = 78, mileage = 102.0, totalElec = 501.0, ignition = 2, energydataAvailable = 1,
        )
        val row = dao.getCurrent()!!
        assertEquals(1500L, row.openTripId)
        assertEquals(1500L, row.tripStartTs)
        assertEquals(80, row.tripStartSoc)
        assertEquals(100.0, row.tripStartMileage)
        assertEquals(500.0, row.tripStartTotalElec)
        assertEquals(10, row.tripStartExteriorTemp)
        // the snapshot columns still got the new tick's values
        assertEquals(78, row.soc)
        assertEquals(102.0, row.mileage)
        assertEquals(501.0, row.totalElec)
        assertEquals(2, row.ignition)
    }

    @Test fun `writeSnapshot creates the row when it is missing`() = runBlocking {
        assertNull(dao.getCurrent())
        dao.writeSnapshot(
            ts = 1000L, soc = 80, mileage = 100.0, totalElec = 500.0, ignition = 2, energydataAvailable = 1,
        )
        val row = dao.getCurrent()!!
        assertEquals(80, row.soc)
        assertEquals(100.0, row.mileage)
        assertEquals(500.0, row.totalElec)
        assertEquals(2, row.ignition)
        assertNull(row.openTripId)
    }

    @Test fun `clearOpenTrip is not undone by a later snapshot write`() = runBlocking {
        dao.upsert(LastStateEntity(id = 1, ts = 1000L, soc = 80, mileage = 100.0))
        dao.openTrip(
            startTs = 1500L, startSoc = 80, startMileage = 100.0, startTotalElec = 500.0,
            startExteriorTemp = 10, now = 1500L,
        )
        dao.clearOpenTrip()
        dao.writeSnapshot(
            ts = 2000L, soc = 78, mileage = 102.0, totalElec = 501.0, ignition = 0, energydataAvailable = 1,
        )
        val row = dao.getCurrent()!!
        assertNull(row.openTripId)
        assertNull(row.tripStartTs)
        assertNull(row.tripStartExteriorTemp)
        assertEquals(78, row.soc) // the snapshot write still applied
    }
}
