package com.bydmate.app.data.trips

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.EnergyDataDeadDetector
import com.bydmate.app.data.local.EnergyDataReader
import com.bydmate.app.data.local.dao.LastStateDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.database.AppDatabase
import com.bydmate.app.data.local.entity.LastStateEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.remote.diParsData
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #250: a trip opened in one process and closed after a restart (from last_state)
 * lost its outside temperature, because the start temperature lived in memory only.
 * Real Room last_state, a fresh TripRecorder per process.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TripRecorderExteriorTempRestartTest {

    private lateinit var db: AppDatabase
    private lateinit var lastState: LastStateDao

    @Before fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        lastState = db.lastStateDao()
    }

    @After fun tearDown() = db.close()

    private fun recorder(tripDao: TripDao, now: () -> Long) = TripRecorder(
        tripDao, lastState,
        mockk<EnergyDataReader> { every { isAvailable() } returns false },
        mockk<EnergyDataDeadDetector>(relaxed = true),
        batteryCapacityKwh = { 72.9 },
        now = now,
    )

    private fun inserted(tripDao: TripDao): TripEntity {
        val cap = slot<TripEntity>()
        coVerify(exactly = 1) { tripDao.insert(capture(cap)) }
        return cap.captured
    }

    /** Process 1: the loop has written a snapshot, then the trip opens at 10 °C. */
    private suspend fun openTripInFirstProcess() {
        lastState.upsert(LastStateEntity(id = 1, ts = 1_000_000L, soc = 80, mileage = 100.0))
        recorder(mockk(relaxed = true)) { 1_000_000L }
            .consume(diParsData(powerState = 2, soc = 80, mileage = 100.0, exteriorTemp = 10))
    }

    @Test fun `short gap resume keeps the start temperature for the later close`() = runBlocking {
        openTripInFirstProcess()

        val tripDao = mockk<TripDao>(relaxed = true)
        val rec = recorder(tripDao) { 1_000_000L + 60_000L }  // restart 1 min later
        rec.reconcileColdStart()
        rec.consume(diParsData(powerState = 0, soc = 75, mileage = 105.0, exteriorTemp = 12))

        val t = inserted(tripDao)
        assertEquals(10, t.exteriorTemp)
        assertEquals(12, t.exteriorTempEnd)
    }

    @Test fun `stale gap close keeps the start temperature`() = runBlocking {
        openTripInFirstProcess()

        val tripDao = mockk<TripDao>(relaxed = true)
        recorder(tripDao) { 1_000_000L + 10 * 60_000L }.reconcileColdStart()

        assertEquals(10, inserted(tripDao).exteriorTemp)
    }

    @Test fun `first-ever tick without a snapshot row also persists the start temperature`() = runBlocking {
        recorder(mockk(relaxed = true)) { 1_000_000L }
            .consume(diParsData(powerState = 2, soc = 80, mileage = 100.0, exteriorTemp = -3))

        val tripDao = mockk<TripDao>(relaxed = true)
        recorder(tripDao) { 1_000_000L + 10 * 60_000L }.reconcileColdStart()

        assertEquals(-3, inserted(tripDao).exteriorTemp)
    }
}
