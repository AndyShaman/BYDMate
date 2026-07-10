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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HistoryImporterTombstoneTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val tombstoneDao = mockk<TripTombstoneDao>(relaxed = true)

    private fun importer() = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
        settingsRepository = mockk<SettingsRepository>(relaxed = true),
        lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
        tripTombstoneDao = tombstoneDao,
    )

    private val record = BydTripRecord(
        id = 42L, startTimestamp = 1_700_000_000L, endTimestamp = 1_700_003_600L,
        duration = 3600L, tripKm = 25.0, electricityKwh = 4.5,
    )

    @Test
    fun `deduplicateWithExisting skips tombstoned record`() = runTest {
        coEvery { energyDataReader.readTrips() } returns listOf(record)
        coEvery { tripDao.getLiveTrips() } returns emptyList()
        coEvery { tripDao.getByBydId(42L) } returns null
        coEvery { tombstoneDao.exists(42L) } returns true

        importer().deduplicateWithExisting()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
    }

    @Test
    fun `deduplicateWithExisting inserts record without tombstone`() = runTest {
        coEvery { energyDataReader.readTrips() } returns listOf(record)
        coEvery { tripDao.getLiveTrips() } returns emptyList()
        coEvery { tripDao.getByBydId(42L) } returns null
        coEvery { tombstoneDao.exists(42L) } returns false

        importer().deduplicateWithExisting()

        coVerify(exactly = 1) { tripRepository.insertTrip(any()) }
    }
}
