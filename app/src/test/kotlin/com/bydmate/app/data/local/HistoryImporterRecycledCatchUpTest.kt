package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripTombstoneEntity
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #348 field report: the car recreated energydata (ids restarted from 1) and the trips table came
 * back from a backup with the old ids. Recycling must be seen without a collision in the newest
 * batch, and the first detection must import every row the old watermark had hidden, once.
 * The trips table and the tombstones are in-memory fakes so the end state can be asserted.
 */
class HistoryImporterRecycledCatchUpTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val tombstoneDao = mockk<TripTombstoneDao>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)

    private val trips = mutableListOf<TripEntity>()
    private val tombstones = mutableListOf<TripTombstoneEntity>()
    private var source: List<BydTripRecord> = emptyList()
    private var watermark = 0L
    private var flag = false
    private var nextId = 10_000L

    private val nowSec = System.currentTimeMillis() / 1000L
    private val t0 = nowSec - 300L * 86_400L // the reset of the car's DB

    private fun importer() = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = energyDataReader,
        tripRepository = tripRepository,
        tripDao = tripDao,
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
        settingsRepository = settings,
        lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
        tripTombstoneDao = tombstoneDao,
        costCalculator = mockk(relaxed = true),
        odometerMarks = mockk(relaxed = true),
        vehicleKind = com.bydmate.app.data.nativestack.kindMonitor(),
        counterSnapshotDao = mockk(relaxed = true),
        counterSnapshotWriter = mockk(relaxed = true),
        lastStateDao = mockk(relaxed = true),
    )

    private fun wire() {
        coEvery { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } answers {
            val w = firstArg<Long>()
            source.filter { it.startTimestamp > w }
        }
        coEvery { energyDataReader.readTrips() } answers { source }
        coEvery { settings.getBatteryCapacity() } returns 72.9
        coEvery { settings.isEnergyIdsRecycled() } answers { flag }
        coEvery { settings.setEnergyIdsRecycled() } answers { flag = true }
        coEvery { settings.getLastEnergyImportTs() } answers { watermark }
        coEvery { settings.setLastEnergyImportTs(any()) } answers { watermark = firstArg() }

        coEvery { tripDao.getAllSnapshot() } answers { trips.toList() }
        coEvery { tripDao.getLiveTrips() } answers { trips.filter { it.bydId == null } }
        coEvery { tripDao.getByBydId(any()) } answers { trips.firstOrNull { it.bydId == firstArg<Long>() } }
        coEvery { tripDao.getByStartTsRange(any(), any()) } answers {
            val lo = firstArg<Long>()
            val hi = secondArg<Long>()
            trips.firstOrNull { it.startTs in lo..hi }
        }
        coEvery { tripDao.unlinkBydId(any(), any()) } answers {
            val id = firstArg<Long>()
            val bydId = secondArg<Long>()
            val i = trips.indexOfFirst { it.id == id && it.bydId == bydId }
            if (i >= 0) trips[i] = trips[i].copy(bydId = null)
            if (i >= 0) 1 else 0
        }
        coEvery { tripRepository.insertTrip(any()) } answers {
            val id = nextId++
            trips += firstArg<TripEntity>().copy(id = id)
            id
        }
        coEvery { tripRepository.updateTrip(any()) } answers {
            val t = firstArg<TripEntity>()
            val i = trips.indexOfFirst { it.id == t.id }
            if (i >= 0) trips[i] = t
        }

        coEvery { tombstoneDao.exists(any()) } answers { tombstones.any { it.bydId == firstArg<Long>() } }
        coEvery { tombstoneDao.maxBydId() } answers { tombstones.maxOfOrNull { it.bydId } }
        coEvery { tombstoneDao.maxStartTs() } answers { tombstones.mapNotNull { it.startTs }.maxOrNull() }
        coEvery { tombstoneDao.isForAnotherTrip(any(), any(), any(), any()) } answers {
            val id = firstArg<Long>()
            val start = secondArg<Long>()
            val window = thirdArg<Long>()
            val ignoreLegacy = arg<Boolean>(3)
            tombstones.any {
                it.bydId == id && (it.startTs?.let { s -> kotlin.math.abs(s - start) > window } ?: ignoreLegacy)
            }
        }
    }

    private fun row(id: Long, startSec: Long, durationSec: Long = 1200L) = BydTripRecord(
        id = id, startTimestamp = startSec, endTimestamp = startSec + durationSec,
        duration = durationSec, tripKm = 10.0, electricityKwh = 1.5,
    )

    private fun rowStart(id: Long) = t0 + id * 10_000L

    private fun oldTrip(id: Long, bydId: Long?, startSec: Long) = TripEntity(
        id = id, startTs = startSec * 1000L, endTs = startSec * 1000L + 900_000L,
        distanceKm = 8.0, kwhConsumed = 1.2, source = if (bydId != null) "energydata" else "live", bydId = bydId,
    )

    /** The trip an energydata row became: same start, same id. */
    private fun importedTrip(rowId: Long) = oldTrip(5_000L + rowId, rowId, rowStart(rowId))

    // 1. The field report: old ids 1..oldMax from before the reset, a recreated source of 726 rows
    // plus one new row, watermark already at the newest old row.
    private fun reporterCase(oldMax: Long) = runTest {
        wire()
        for (id in 1..oldMax) trips += oldTrip(id, id, t0 - 100L * 86_400L - (oldMax - id) * 3_600L)
        source = (1L..727L).map { row(it, rowStart(it)) }
        watermark = rowStart(726)
        val imp = importer()

        val first = imp.syncFromEnergyData()

        assertEquals(727, first.trips)
        assertTrue(flag)
        assertEquals(rowStart(727), watermark)
        for (id in 1L..727L) {
            val t = trips.single { it.bydId == id }
            assertEquals(rowStart(id) * 1000L, t.startTs)
            if (id <= oldMax) coVerify(exactly = 1) { tripDao.unlinkBydId(id, id) }
        }
        // The watermark is reset before the flag is stored, once
        coVerifyOrder {
            settings.setLastEnergyImportTs(0L)
            settings.setEnergyIdsRecycled()
        }
        coVerify(exactly = 1) { settings.setLastEnergyImportTs(0L) }
        coVerify(exactly = 1) { energyDataReader.resetWatermark(any()) }
        // Old trips are kept, unlinked: every pre-reset trip is still there
        assertEquals(oldMax.toInt() + 727, trips.size)
        // Old trips whose id the new source reused lose it; the rest keep theirs
        assertEquals(minOf(oldMax, 727L).toInt(), trips.count { it.bydId == null })

        val second = imp.syncFromEnergyData()

        assertEquals(0, second.trips)
        assertEquals(oldMax.toInt() + 727, trips.size)
        coVerify(exactly = 1) { energyDataReader.readTripsSince(0L) }
        coVerify(exactly = 1) { settings.setLastEnergyImportTs(0L) }
    }

    @Test fun `reporter case - old ids up to 900, whole recreated source imported once`() = reporterCase(900L)

    @Test fun `reporter variant - rows 501 to 726 already imported are not duplicated`() = runTest {
        wire()
        for (id in 1L..500L) trips += oldTrip(id, id, t0 - 100L * 86_400L - (500L - id) * 3_600L)
        for (id in 501L..726L) trips += importedTrip(id)
        source = (1L..727L).map { row(it, rowStart(it)) }
        watermark = rowStart(726)

        val result = importer().syncFromEnergyData()

        // Rows 1..500 (recycled) and 727 (new); 501..726 are known
        assertEquals(501, result.trips)
        assertTrue(flag)
        assertEquals(500 + 226 + 501, trips.size)
        for (id in 1L..727L) assertEquals("id $id", 1, trips.count { it.bydId == id })
        assertEquals(rowStart(727), watermark)
        val again = importer().syncFromEnergyData()
        assertEquals(0, again.trips)
    }

    // 3. B1: the only collision is a deleted id with a legacy tombstone (no start).
    private fun legacyTombstoneForId1() {
        tombstones += TripTombstoneEntity(bydId = 1L, startTs = null)
        for (id in 2L..10L) trips += oldTrip(id, id, t0 - 100L * 86_400L + id * 3_600L)
    }

    @Test fun `B1 - deleted id with a legacy tombstone is the only collision, record imported`() = runTest {
        wire()
        legacyTombstoneForId1()
        source = listOf(row(1L, t0))
        watermark = t0 - 90L * 86_400L // the old import state, before the recreated row

        val result = importer().syncFromEnergyData()

        assertEquals(1, result.trips)
        assertTrue(flag)
        assertNotNull(trips.singleOrNull { it.bydId == 1L && it.startTs == t0 * 1000L })
        coVerifyOrder {
            settings.setLastEnergyImportTs(0L)
            settings.setEnergyIdsRecycled()
            settings.setLastEnergyImportTs(t0)
        }
        assertEquals(t0, watermark)
    }

    @Test fun `B1 - flag stored earlier, legacy tombstone ignored, no second catch-up`() = runTest {
        wire()
        flag = true
        legacyTombstoneForId1()
        source = listOf(row(1L, t0))
        watermark = t0 - 90L * 86_400L

        val result = importer().syncFromEnergyData()

        assertEquals(1, result.trips)
        coVerify(exactly = 0) { settings.setLastEnergyImportTs(0L) }
        coVerify(exactly = 0) { energyDataReader.readTripsSince(0L) }
    }

    @Test fun `rows skipped below the watermark before are imported by the catch-up`() = runTest {
        wire()
        for (id in 1L..10L) trips += oldTrip(id, id, t0 - 100L * 86_400L + id * 3_600L)
        source = (1L..4L).map { row(it, rowStart(it)) }
        watermark = rowStart(3) // rows 1..3 were read and skipped by the old code

        val result = importer().syncFromEnergyData()

        assertEquals(4, result.trips)
        for (id in 1L..4L) assertNotNull("id $id", trips.singleOrNull { it.bydId == id && it.startTs == rowStart(id) * 1000L })
        assertTrue(flag)
    }

    @Test fun `normal operation - a row written under a clock jump does not fire the signal`() = runTest {
        wire()
        val base = nowSec - 100L * 86_400L
        fun start(id: Long) = base + id * 3_600L
        val recs = (1L..150L).filter { it != 100L }.map { row(it, start(it)) }
        for (r in recs) trips += oldTrip(r.id + 1_000L, r.id, r.startTimestamp)
        // Row 100 was written while the clock ran ahead: now past, later than trip 150
        source = recs + row(100L, start(150) + 3_600L)
        watermark = start(150)

        val result = importer().syncFromEnergyData()

        assertFalse(flag)
        coVerify(exactly = 0) { settings.setEnergyIdsRecycled() }
        coVerify(exactly = 0) { settings.setLastEnergyImportTs(0L) }
        coVerify(exactly = 0) { tripDao.unlinkBydId(any(), any()) }
        assertEquals(1, result.trips)
    }

    @Test fun `normal operation - ids growing, unchanged source re-read, never imported short row`() = runTest {
        wire()
        for (id in 1L..20L) trips += importedTrip(id)
        source = (1L..20L).map { row(it, rowStart(it)) } + row(21L, rowStart(21), durationSec = 10L)
        watermark = 0L

        val result = importer().syncFromEnergyData()

        assertFalse(flag)
        assertEquals(0, result.trips)
        assertEquals(20, trips.size)
        coVerify(exactly = 0) { tripDao.unlinkBydId(any(), any()) }
        coVerify(exactly = 0) { settings.setEnergyIdsRecycled() }
    }

    @Test fun `normal operation - empty tables import normally with no flag`() = runTest {
        wire()
        source = (1L..5L).map { row(it, rowStart(it)) }

        val result = importer().syncFromEnergyData()

        assertEquals(5, result.trips)
        assertFalse(flag)
        coVerify(exactly = 0) { settings.setEnergyIdsRecycled() }
        // The first sync from an empty watermark is the batch itself, not a catch-up
        coVerify(exactly = 0) { energyDataReader.resetWatermark(any()) }
    }

    @Test fun `a tombstone with a start still hides its trip after the catch-up`() = runTest {
        wire()
        for (id in 1L..900L) trips += oldTrip(id, id, t0 - 100L * 86_400L - (900L - id) * 3_600L)
        tombstones += TripTombstoneEntity(bydId = 5L, startTs = rowStart(5) * 1000L)
        source = (1L..727L).map { row(it, rowStart(it)) }
        watermark = rowStart(726)

        val result = importer().syncFromEnergyData()

        assertEquals(726, result.trips)
        assertTrue(flag)
        assertEquals(0, trips.count { it.bydId == 5L })
        assertEquals(1, trips.count { it.bydId == 6L })
    }

    @Test fun `catch-up respects the time overlap with a live trip`() = runTest {
        wire()
        for (id in 1L..10L) trips += oldTrip(id, id, t0 - 100L * 86_400L + id * 3_600L)
        val live = oldTrip(50L, null, rowStart(2) + 90L)
        trips += live
        source = (1L..3L).map { row(it, rowStart(it)) }
        watermark = rowStart(3)

        val result = importer().syncFromEnergyData()

        // Rows 1 and 3 inserted; row 2 merged into the live trip
        assertEquals(2, result.trips)
        val merged = trips.single { it.id == 50L }
        assertEquals(2L, merged.bydId)
        assertEquals("energydata", merged.source)
        assertEquals(1, trips.count { it.bydId == 2L })
        assertTrue(flag)
    }

    @Test fun `deduplicateWithExisting detects recycling through ids going back, resets the watermark before the flag`() = runTest {
        wire()
        tombstones += TripTombstoneEntity(bydId = 1L, startTs = null)
        for (id in 2L..5L) trips += oldTrip(id, id, t0 - 100L * 86_400L + id * 3_600L)
        source = listOf(row(1L, t0))

        importer().deduplicateWithExisting()

        assertTrue(flag)
        assertNotNull(trips.singleOrNull { it.bydId == 1L && it.startTs == t0 * 1000L })
        coVerifyOrder {
            settings.setLastEnergyImportTs(0L)
            settings.setEnergyIdsRecycled()
            settings.setLastEnergyImportTs(t0)
        }
        coVerify(exactly = 1) { settings.setLastEnergyImportTs(0L) }
        coVerify(exactly = 1) { energyDataReader.resetWatermark(any()) }
        assertEquals(t0, watermark)
    }

    // 14. The anchor is the trip with the highest known id. The source "still holds" it when its
    // record has that id and a start within the 5 min window.
    private fun anchorCase(anchorRecordOffsetSec: Long) = runTest {
        wire()
        val base = nowSec - 100L * 86_400L
        // ids 1..10 known except 3, each in the source at its own start
        val known = (1L..10L).filter { it != 3L }
        for (id in known) trips += oldTrip(id, id, base + id * 3_600L)
        val anchorStart = base + 10L * 3_600L
        source = known.filter { it != 10L }.map { row(it, base + it * 3_600L) } +
            row(10L, anchorStart + anchorRecordOffsetSec) +
            row(3L, anchorStart + 1_000L) // id 3 is not known, newer than every known trip
        watermark = anchorStart

        importer().syncFromEnergyData()
    }

    @Test fun `anchor record 5 min off is still the anchor, no signal`() {
        anchorCase(300L)
        assertFalse(flag)
    }

    @Test fun `anchor record 5 min and 1 s off is not the anchor, signal`() {
        anchorCase(301L)
        assertTrue(flag)
    }
}
