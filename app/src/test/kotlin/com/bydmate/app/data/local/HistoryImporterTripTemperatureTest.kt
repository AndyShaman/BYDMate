package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * energydata carries no outside temperature, so an imported trip gets it from the driving
 * session bookmark that was recorded live while the car was on.
 */
class HistoryImporterTripTemperatureTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val lastSessionRepository = mockk<LastSessionRepository>(relaxed = true)

    private fun importer() = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
        settingsRepository = settingsRepository,
        lastSessionRepository = lastSessionRepository,
        tripTombstoneDao = mockk<TripTombstoneDao>(relaxed = true),
        costCalculator = mockk(relaxed = true),
        odometerMarks = mockk(relaxed = true),
    )

    private val record = BydTripRecord(
        id = 71L, startTimestamp = 1_700_000_000L, endTimestamp = 1_700_001_800L,
        duration = 1800L, tripKm = 20.0, electricityKwh = 3.5,
    )

    private suspend fun syncOneRecord(): TripEntity {
        coEvery { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(record)
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getByBydId(record.id) } returns null
        coEvery { tripDao.getByStartTsRange(any(), any()) } returns null

        val inserted = slot<TripEntity>()
        importer().syncFromEnergyData()
        coVerify(exactly = 1) { tripRepository.insertTrip(capture(inserted)) }
        return inserted.captured
    }

    @Test fun `matched session temperatures land on the imported trip`() = runTest {
        every { lastSessionRepository.takeMatch(any()) } returns LastSessionRepository.Snapshot(
            startSoc = 80, endSoc = 71,
            startTs = 1_700_000_000_000L, endTs = 1_700_001_800_000L,
            startExteriorTemp = 5, endExteriorTemp = 9,
        )

        val trip = syncOneRecord()
        assertEquals(5, trip.exteriorTemp)
        assertEquals(9, trip.exteriorTempEnd)
    }

    @Test fun `no session leaves both temperatures null`() = runTest {
        // No bookmark, and no live snapshot either (TrackingService.lastData is null in
        // unit tests — no public setter), so nothing may be invented for this trip.
        every { lastSessionRepository.takeMatch(any()) } returns null

        val trip = syncOneRecord()
        assertNull(trip.exteriorTemp)
        assertNull(trip.exteriorTempEnd)
    }

    @Test fun `session temperature wins over the live snapshot`() {
        assertEquals(
            9,
            HistoryImporter.importedTempEnd(
                sessionTemp = 9, endTsMs = 1_000L, nowMs = 2_000L, liveTemp = 14),
        )
    }

    @Test fun `live snapshot is the end fallback for a just-ended trip`() {
        val endTs = 1_700_001_800_000L
        assertEquals(
            14,
            HistoryImporter.importedTempEnd(
                sessionTemp = null, endTsMs = endTs, nowMs = endTs + 60_000L, liveTemp = 14),
        )
        // An older trip gets null instead of today's temperature.
        assertNull(
            HistoryImporter.importedTempEnd(
                sessionTemp = null, endTsMs = endTs, nowMs = endTs + 3_600_000L, liveTemp = 14),
        )
    }
}
