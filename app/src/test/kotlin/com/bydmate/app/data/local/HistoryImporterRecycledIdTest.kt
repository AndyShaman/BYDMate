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
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * energydata `_id` restarts from 1 when the car recreates its DB (reset, firmware, a backup
 * restored onto it): a byd_id already held by an old trip with a far-off start is a recycled
 * id, not a duplicate (#348).
 */
class HistoryImporterRecycledIdTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val tombstoneDao = mockk<TripTombstoneDao>(relaxed = true)
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
        tripTombstoneDao = tombstoneDao,
        costCalculator = mockk(relaxed = true),
        odometerMarks = mockk(relaxed = true),
        vehicleKind = com.bydmate.app.data.nativestack.kindMonitor(),
        counterSnapshotDao = mockk(relaxed = true),
        counterSnapshotWriter = mockk(relaxed = true),
        lastStateDao = mockk(relaxed = true),
    )

    private val record = BydTripRecord(
        id = 3L, startTimestamp = 1_700_000_000L, endTimestamp = 1_700_001_800L,
        duration = 1800L, tripKm = 20.0, electricityKwh = 3.5,
    )
    private val startMs = record.startTimestamp * 1000L

    // The trip the old energydata DB wrote under _id = 3, half a year earlier.
    private val stale = TripEntity(
        id = 11L, startTs = startMs - 180L * 86_400_000L, endTs = startMs - 180L * 86_400_000L + 900_000L,
        distanceKm = 8.0, kwhConsumed = 1.4, source = "energydata", bydId = 3L,
    )

    private fun stubSync(byId: TripEntity?, byTime: TripEntity? = null, tombstoned: Boolean = false) {
        coEvery { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(record)
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getByBydId(record.id) } returns byId
        coEvery { tripDao.getSameTripByTime(any(), any()) } returns byTime
        coEvery { tombstoneDao.exists(record.id) } returns tombstoned
    }

    @Test fun `recycled id with a far-off start is imported as a new trip`() = runTest {
        stubSync(byId = stale)

        val result = importer().syncFromEnergyData()

        val inserted = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.insertTrip(capture(inserted)) }
        assertEquals(startMs, inserted.captured.startTs)
        assertEquals(3L, inserted.captured.bydId)
        assertEquals(1, result.trips)
    }

    @Test fun `recycled id is released from the stale trip`() = runTest {
        stubSync(byId = stale)

        importer().syncFromEnergyData()

        // A conditional UPDATE, not a full-row write: the old trip's other columns are never touched
        coVerify(exactly = 1) { tripDao.unlinkBydId(11L, 3L) }
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
    }

    @Test fun `same id with a start inside the window is still a duplicate`() = runTest {
        stubSync(byId = stale.copy(startTs = startMs + 60_000L))

        val result = importer().syncFromEnergyData()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
        coVerify(exactly = 0) { tripDao.unlinkBydId(any(), any()) }
        assertEquals(0, result.trips)
    }

    @Test fun `same id at the edge of the window is still a duplicate`() = runTest {
        // A live trip linked by time overlap may start up to the full window apart.
        stubSync(byId = stale.copy(startTs = startMs - 300_000L))

        importer().syncFromEnergyData()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
        coVerify(exactly = 0) { tripDao.unlinkBydId(any(), any()) }
    }

    @Test fun `time overlap path is unchanged`() = runTest {
        val live = TripEntity(id = 20L, startTs = startMs + 90_000L, distanceKm = 19.0, source = "live")
        stubSync(byId = null, byTime = live)

        importer().syncFromEnergyData()

        val updated = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.updateTrip(capture(updated)) }
        assertEquals(20L, updated.captured.id)
        assertEquals(3L, updated.captured.bydId)
        assertEquals("energydata", updated.captured.source)
        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
    }

    @Test fun `recycled id falls through to the time overlap check`() = runTest {
        val live = TripEntity(id = 20L, startTs = startMs + 90_000L, distanceKm = 19.0, source = "live")
        stubSync(byId = stale, byTime = live)

        importer().syncFromEnergyData()

        coVerify(exactly = 1) { tripDao.unlinkBydId(11L, 3L) }
        coVerify(exactly = 1) { tripRepository.updateTrip(match { it.id == 20L && it.bydId == 3L }) }
        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
    }

    @Test fun `tombstone left for this trip still drops the record`() = runTest {
        stubSync(byId = null, tombstoned = true)
        coEvery { tombstoneDao.isForAnotherTrip(record.id, startMs, 300_000L, false) } returns false

        importer().syncFromEnergyData()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
    }

    @Test fun `tombstone left by another trip with the recycled id does not drop the record`() = runTest {
        stubSync(byId = null, tombstoned = true)
        coEvery { tombstoneDao.isForAnotherTrip(record.id, startMs, 300_000L, any()) } returns true

        importer().syncFromEnergyData()

        coVerify(exactly = 1) { tripRepository.insertTrip(match { it.bydId == 3L }) }
    }

    @Test fun `a recycled id in the batch makes legacy tombstones not apply`() = runTest {
        // Record 3 hits a legacy tombstone, record 4 proves the source was recreated: the
        // signal is taken from the whole batch before the loop, whatever the order.
        val other = record.copy(id = 4L, startTimestamp = record.startTimestamp + 7200L,
            endTimestamp = record.endTimestamp + 7200L)
        stubSync(byId = null, tombstoned = true)
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(record, other)
        coEvery { tripDao.getByBydId(4L) } returns stale.copy(id = 12L, bydId = 4L)
        // The collision is found in the trips snapshot
        coEvery { tripDao.getAllSnapshot() } returns listOf(stale.copy(id = 12L, bydId = 4L))
        coEvery { tombstoneDao.exists(4L) } returns false
        coEvery { tombstoneDao.isForAnotherTrip(3L, startMs, 300_000L, true) } returns true
        coEvery { tombstoneDao.isForAnotherTrip(3L, startMs, 300_000L, false) } returns false

        importer().syncFromEnergyData()

        coVerify(exactly = 1) { tripRepository.insertTrip(match { it.bydId == 3L }) }
        coVerify(exactly = 1) { tripRepository.insertTrip(match { it.bydId == 4L }) }
    }

    @Test fun `without a recycled id legacy tombstones keep hiding by id`() = runTest {
        stubSync(byId = null, tombstoned = true)

        importer().syncFromEnergyData()

        coVerify { tombstoneDao.isForAnotherTrip(record.id, startMs, 300_000L, false) }
        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
    }

    @Test fun `deduplicateWithExisting imports a recycled id as a new trip`() = runTest {
        coEvery { energyDataReader.readTrips() } returns listOf(record)
        coEvery { tripDao.getLiveTrips() } returns emptyList()
        coEvery { tripDao.getByBydId(record.id) } returns stale
        coEvery { tripDao.getAllSnapshot() } returns listOf(stale)
        coEvery { tombstoneDao.exists(record.id) } returns false

        importer().deduplicateWithExisting()

        coVerify(exactly = 1) { tripDao.unlinkBydId(11L, 3L) }
        coVerify(exactly = 1) { tripRepository.insertTrip(match { it.startTs == startMs && it.bydId == 3L }) }
    }

    @Test fun `deduplicateWithExisting still skips a record already imported`() = runTest {
        coEvery { energyDataReader.readTrips() } returns listOf(record)
        coEvery { tripDao.getLiveTrips() } returns emptyList()
        coEvery { tripDao.getByBydId(record.id) } returns stale.copy(startTs = startMs)

        importer().deduplicateWithExisting()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
        coVerify(exactly = 0) { tripDao.unlinkBydId(any(), any()) }
    }

    @Test fun `consumption recalculation never takes kWh from a recycled id`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("energydata").toFile()
        coEvery { settingsRepository.isConsumptionRecalcDone() } returns false
        coEvery { energyDataReader.energyDataDir() } returns dir
        coEvery { energyDataReader.readTrips() } returns listOf(record)
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getAllSnapshot() } returns listOf(stale)

        importer().recalculateConsumptionFromEnergyData()

        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
        dir.delete()
    }

    @Test fun `consumption recalculation still matches by id inside the window`() = runTest {
        val dir = java.nio.file.Files.createTempDirectory("energydata").toFile()
        coEvery { settingsRepository.isConsumptionRecalcDone() } returns false
        coEvery { energyDataReader.energyDataDir() } returns dir
        coEvery { energyDataReader.readTrips() } returns listOf(record)
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getAllSnapshot() } returns listOf(stale.copy(startTs = startMs, distanceKm = 20.0))

        importer().recalculateConsumptionFromEnergyData()

        coVerify(exactly = 1) { tripRepository.updateTrip(match { it.kwhConsumed == 3.5 }) }
        dir.delete()
    }

    // Legacy tombstone (no start): ignored only when the recycling flag is on.
    private fun stubLegacyTombstone() {
        coEvery { tombstoneDao.exists(record.id) } returns true
        coEvery { tombstoneDao.isForAnotherTrip(record.id, startMs, 300_000L, true) } returns true
        coEvery { tombstoneDao.isForAnotherTrip(record.id, startMs, 300_000L, false) } returns false
    }

    @Test fun `recycling detected in an earlier sync keeps legacy tombstones ignored`() = runTest {
        var flag = false
        coEvery { settingsRepository.isEnergyIdsRecycled() } answers { flag }
        coEvery { settingsRepository.setEnergyIdsRecycled() } answers { flag = true }
        val other = record.copy(id = 4L)

        // Sync A: id 4 is held by an old trip, so the source is proven recreated.
        stubSync(byId = null)
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(other)
        coEvery { tripDao.getByBydId(4L) } returns stale.copy(id = 12L, bydId = 4L)
        coEvery { tripDao.getAllSnapshot() } returns listOf(stale.copy(id = 12L, bydId = 4L))
        importer().syncFromEnergyData()
        assertEquals(true, flag)

        // Sync B: no recycled signal, record 3 meets a legacy tombstone.
        stubSync(byId = null)
        stubLegacyTombstone()
        importer().syncFromEnergyData()

        coVerify(exactly = 1) { tripRepository.insertTrip(match { it.bydId == 3L }) }
    }

    @Test fun `legacy tombstone still hides its id without the flag and without a signal`() = runTest {
        coEvery { settingsRepository.isEnergyIdsRecycled() } returns false
        stubSync(byId = null)
        stubLegacyTombstone()

        importer().syncFromEnergyData()

        coVerify(exactly = 0) { tripRepository.insertTrip(any()) }
        coVerify(exactly = 0) { settingsRepository.setEnergyIdsRecycled() }
    }
}
