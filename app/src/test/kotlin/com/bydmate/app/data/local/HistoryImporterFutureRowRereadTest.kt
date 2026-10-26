package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * R1/F3 (#348 heal): a row skipped as "from the future" while the head unit clock was behind must
 * be read again once the clock is corrected, even though the energydata file did not change.
 * The skipped sync must therefore not commit the source fingerprint.
 */
class HistoryImporterFutureRowRereadTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private fun importer() = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
        settingsRepository = settingsRepository,
        lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
        tripTombstoneDao = mockk<TripTombstoneDao>(relaxed = true),
        costCalculator = mockk(relaxed = true),
        odometerMarks = mockk(relaxed = true),
        vehicleKind = com.bydmate.app.data.nativestack.kindMonitor(),
        counterSnapshotDao = mockk(relaxed = true),
        counterSnapshotWriter = mockk(relaxed = true),
        lastStateDao = mockk(relaxed = true),
    )

    @Test
    fun `a row skipped as future is imported once after the clock catches up`() = runTest {
        val nowSec = System.currentTimeMillis() / 1000L
        // Just beyond the skew now; the real clock passes it during the sleep below.
        val row = BydTripRecord(
            id = 601L, startTimestamp = nowSec + HistoryImporter.FUTURE_SKEW_SEC + 2,
            endTimestamp = nowSec + HistoryImporter.FUTURE_SKEW_SEC + 2 + 1800L,
            duration = 1800L, tripKm = 12.0, electricityKwh = 2.0,
        )
        var watermark = nowSec - 3600L
        var fingerprintProcessed = false
        coEvery { energyDataReader.peekSourceChanged(any()) } answers { !fingerprintProcessed }
        every { energyDataReader.markSourceProcessed(any()) } answers { fingerprintProcessed = true }
        coEvery { energyDataReader.readTripsSince(any()) } answers {
            listOf(row).filter { it.startTimestamp > firstArg<Long>() }
        }
        coEvery { settingsRepository.getLastEnergyImportTs() } answers { watermark }
        coEvery { settingsRepository.setLastEnergyImportTs(any()) } answers { watermark = firstArg() }
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getByBydId(any()) } returns null
        coEvery { tripDao.getByStartTsRange(any(), any()) } returns null
        coEvery { tripDao.getLiveTrips() } returns emptyList()

        importer().syncFromEnergyData()
        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }

        Thread.sleep(3_000) // the head unit clock is corrected: the row is no longer in the future

        importer().syncFromEnergyData()
        coVerify(exactly = 1) { tripRepository.insertTrip(any()) }

        val third = importer().syncFromEnergyData()
        coVerify(exactly = 1) { tripRepository.insertTrip(any()) }
        assertEquals(0, third.trips)
    }
}
