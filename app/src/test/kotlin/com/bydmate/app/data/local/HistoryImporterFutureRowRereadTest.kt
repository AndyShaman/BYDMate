package com.bydmate.app.data.local

import com.bydmate.app.data.local.dao.TripDao
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

    private fun importer() = historyImporter(
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        settingsRepository = settingsRepository,
    )

    @Test
    fun `a row skipped as future is imported once after the clock catches up`() = runTest {
        val nowSec = System.currentTimeMillis() / 1000L
        // An hour beyond the skew: the head unit clock is behind, the row reads as from the future.
        var rowStart = nowSec + HistoryImporter.FUTURE_SKEW_SEC + 3_600L
        val row = BydTripRecord(
            id = 601L, startTimestamp = rowStart, endTimestamp = rowStart + 1800L,
            duration = 1800L, tripKm = 12.0, electricityKwh = 2.0,
        )
        var watermark = nowSec - 3600L
        var fingerprintProcessed = false
        coEvery { energyDataReader.peekSourceChanged(any()) } answers { !fingerprintProcessed }
        every { energyDataReader.markSourceProcessed(any()) } answers { fingerprintProcessed = true }
        coEvery { energyDataReader.readTripsSince(any()) } answers {
            listOf(row.copy(startTimestamp = rowStart, endTimestamp = rowStart + 1800L))
                .filter { it.startTimestamp > firstArg<Long>() }
        }
        coEvery { settingsRepository.getLastEnergyImportTs() } answers { watermark }
        coEvery { settingsRepository.setLastEnergyImportTs(any()) } answers { watermark = firstArg() }
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getByBydId(any()) } returns null
        coEvery { tripDao.getSameTripByTime(any(), any()) } returns null
        coEvery { tripDao.getLiveTrips() } returns emptyList()

        importer().syncFromEnergyData()
        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }

        // The head unit clock is corrected: the same row is no longer in the future. The file did not
        // change, so only a fingerprint left uncommitted by the skipped sync lets it be read again.
        rowStart = nowSec - 600L

        importer().syncFromEnergyData()
        coVerify(exactly = 1) { tripRepository.insertTrip(any()) }

        val third = importer().syncFromEnergyData()
        coVerify(exactly = 1) { tripRepository.insertTrip(any()) }
        assertEquals(0, third.trips)
    }
}
