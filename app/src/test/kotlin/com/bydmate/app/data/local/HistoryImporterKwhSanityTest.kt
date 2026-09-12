package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryImporterKwhSanityTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val idleDrainDao = mockk<IdleDrainDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private fun importer() = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = idleDrainDao,
        settingsRepository = settingsRepository,
        lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
        tripTombstoneDao = mockk<TripTombstoneDao>(relaxed = true),
    )

    // VadimV, 2026-09-12: 54681,2 kWh on 38,2 km with SOC 92 -> 84.
    private val poisoned = TripEntity(
        id = 7L, startTs = 1_700_000_000_000L, endTs = 1_700_000_100_000L,
        distanceKm = 38.2, kwhConsumed = 54681.2, kwhPer100km = 143143.5,
        socStart = 92, socEnd = 84, cost = 30074.0, source = "energydata", bydId = 55L,
    )

    private val healthy = poisoned.copy(
        id = 8L, kwhConsumed = 5.6, kwhPer100km = 14.6, cost = 1.1, bydId = 56L,
    )

    @Test fun `repair replaces the implausible value with the SOC delta`() = runTest {
        coEvery { settingsRepository.isEnergyKwhSanityDone() } returns false
        coEvery { settingsRepository.getBatteryCapacity() } returns 60.0
        coEvery { tripDao.getAllSnapshot() } returns listOf(poisoned, healthy)

        val updated = slot<TripEntity>()
        val repaired = importer().repairImplausibleKwh()

        assertEquals(1, repaired)
        coVerify(exactly = 1) { tripRepository.updateTrip(capture(updated)) }
        assertEquals(4.8, updated.captured.kwhConsumed!!, 1e-9)
        assertEquals(4.8 / 38.2 * 100.0, updated.captured.kwhPer100km!!, 1e-9)
        assertNull(updated.captured.cost)
        coVerify(exactly = 1) { settingsRepository.setEnergyKwhSanityDone() }
    }

    @Test fun `repair leaves plausible trips alone`() = runTest {
        coEvery { settingsRepository.isEnergyKwhSanityDone() } returns false
        coEvery { settingsRepository.getBatteryCapacity() } returns 60.0
        coEvery { tripDao.getAllSnapshot() } returns listOf(healthy)

        assertEquals(0, importer().repairImplausibleKwh())
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
    }

    @Test fun `repair leaves a natively recorded trip alone`() = runTest {
        // TripRecorder writes its own counter delta on cars without energydata; this bound
        // knows nothing about it, and the SOC pair there may legitimately be unchanged.
        // 60 kWh on 10 km = 600 per 100: without the source gate it would be dropped to null.
        val native = poisoned.copy(
            id = 9L, kwhConsumed = 60.0, distanceKm = 10.0, socStart = null, socEnd = null,
            source = "NATIVE_POLLING", bydId = null,
        )
        coEvery { settingsRepository.isEnergyKwhSanityDone() } returns false
        coEvery { settingsRepository.getBatteryCapacity() } returns 60.0
        coEvery { tripDao.getAllSnapshot() } returns listOf(native)

        assertEquals(0, importer().repairImplausibleKwh())
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
    }

    @Test fun `repair is a no-op once the flag is set`() = runTest {
        coEvery { settingsRepository.isEnergyKwhSanityDone() } returns true

        assertEquals(0, importer().repairImplausibleKwh())
        coVerify(exactly = 0) { tripDao.getAllSnapshot() }
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
    }

    @Test fun `repair clears an implausible idle drain but keeps a plausible one`() = runTest {
        val poisonedDrain = IdleDrainEntity(
            id = 3L, startTs = 1_700_000_000_000L, endTs = 1_700_003_600_000L, kwhConsumed = 54681.2)
        val healthyDrain = poisonedDrain.copy(id = 4L, kwhConsumed = 0.4)
        coEvery { settingsRepository.isEnergyKwhSanityDone() } returns false
        coEvery { settingsRepository.getBatteryCapacity() } returns 60.0
        coEvery { tripDao.getAllSnapshot() } returns emptyList()
        coEvery { idleDrainDao.getAll() } returns listOf(poisonedDrain, healthyDrain)

        val updated = slot<IdleDrainEntity>()
        importer().repairImplausibleKwh()

        coVerify(exactly = 1) { idleDrainDao.update(capture(updated)) }
        assertEquals(3L, updated.captured.id)
        assertNull(updated.captured.kwhConsumed)
        // The parking window itself stays — the hours statistics live off the timestamps.
        assertEquals(poisonedDrain.startTs, updated.captured.startTs)
        assertEquals(poisonedDrain.endTs, updated.captured.endTs)
    }

    @Test fun `sync stores an implausible idle drain with null kWh`() = runTest {
        val zeroKm = BydTripRecord(
            id = 91L, startTimestamp = 1_700_000_000L, endTimestamp = 1_700_003_600L,
            duration = 3600L, tripKm = 0.0, electricityKwh = 54681.2,
        )
        coEvery { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(zeroKm)
        coEvery { settingsRepository.getBatteryCapacity() } returns 60.0
        coEvery { tripDao.getByBydId(91L) } returns null
        coEvery { tripDao.getByStartTsRange(any(), any()) } returns null

        val drain = slot<IdleDrainEntity>()
        val inserted = slot<TripEntity>()
        val result = importer().syncFromEnergyData()

        // The parking window is kept, only the impossible value is dropped.
        assertEquals(1, result.idleDrains)
        coVerify(exactly = 1) { idleDrainDao.insert(capture(drain)) }
        assertNull(drain.captured.kwhConsumed)
        assertEquals(1_700_000_000_000L, drain.captured.startTs)
        assertEquals(1_700_003_600_000L, drain.captured.endTs)
        // The same record is still visible as a zero-km trip, with the value dropped too.
        coVerify(exactly = 1) { tripRepository.insertTrip(capture(inserted)) }
        assertNull(inserted.captured.kwhConsumed)
    }

    @Test fun `sync stores a plausible idle drain`() = runTest {
        val zeroKm = BydTripRecord(
            id = 92L, startTimestamp = 1_700_000_000L, endTimestamp = 1_700_003_600L,
            duration = 3600L, tripKm = 0.0, electricityKwh = 0.4,
        )
        coEvery { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(zeroKm)
        coEvery { settingsRepository.getBatteryCapacity() } returns 60.0
        coEvery { tripDao.getByBydId(92L) } returns null
        coEvery { tripDao.getByStartTsRange(any(), any()) } returns null

        val drain = slot<IdleDrainEntity>()
        val result = importer().syncFromEnergyData()

        assertEquals(1, result.idleDrains)
        coVerify(exactly = 1) { idleDrainDao.insert(capture(drain)) }
        assertEquals(0.4, drain.captured.kwhConsumed!!, 0.0)
    }
}
