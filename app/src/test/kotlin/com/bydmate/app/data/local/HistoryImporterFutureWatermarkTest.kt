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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #348: the import watermark (`last_energydata_import_ts`) is the newest start_timestamp seen,
 * and the next sync reads only `start_timestamp > watermark`. One energydata row dated in the
 * future (head unit clock jumped forward) must therefore never become the watermark: every real
 * trip after it starts "before" the watermark and would be skipped forever. A user already
 * poisoned by such a row must heal on the next sync, without pressing anything.
 */
class HistoryImporterFutureWatermarkTest {

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

    /** Stateful watermark, so a heal written by the importer is what it reads back. */
    private var storedWatermark = 0L

    private val nowSec = System.currentTimeMillis() / 1000L
    private val daySec = 24L * 3600L

    /** A real drive that ended an hour ago. */
    private val realTrip = BydTripRecord(
        id = 501L, startTimestamp = nowSec - 2 * 3600L, endTimestamp = nowSec - 3600L,
        duration = 3600L, tripKm = 40.0, electricityKwh = 7.0,
    )

    /** A row written while the head unit clock was ~400 days ahead. */
    private fun futureRow(durationSec: Long) = BydTripRecord(
        id = 502L, startTimestamp = nowSec + 400 * daySec,
        endTimestamp = nowSec + 400 * daySec + durationSec,
        duration = durationSec, tripKm = 1.0, electricityKwh = 0.2,
    )

    /** Mirrors the real reader: `WHERE start_timestamp > ?` over the rows on disk. */
    private fun stubReader(watermark: Long, rowsOnDisk: List<BydTripRecord>, sourceChanged: Boolean = true) {
        coEvery { energyDataReader.peekSourceChanged(any()) } returns sourceChanged
        coEvery { energyDataReader.readTripsSince(any()) } answers {
            val since = firstArg<Long>()
            rowsOnDisk.filter { it.startTimestamp > since }.sortedBy { it.startTimestamp }
        }
        coEvery { energyDataReader.readTrips() } returns rowsOnDisk
        storedWatermark = watermark
        coEvery { settingsRepository.getLastEnergyImportTs() } answers { storedWatermark }
        coEvery { settingsRepository.setLastEnergyImportTs(any()) } answers { storedWatermark = firstArg() }
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getByBydId(any()) } returns null
        coEvery { tripDao.getSameTripByTime(any(), any()) } returns null
        coEvery { tripDao.getLiveTrips() } returns emptyList()
    }

    @Test
    fun `a short future-dated row does not become the watermark`() = runTest {
        // Shorter than MIN_TRIP_DURATION_SEC: never shown as a trip, yet today it still sets
        // the watermark, which is why #348 saw no strange trip, only no new ones.
        stubReader(watermark = nowSec - 3 * daySec, rowsOnDisk = listOf(realTrip, futureRow(10L)))

        importer().syncFromEnergyData()

        assertEquals(
            "watermark must stay at the newest real trip, not jump to the future row",
            realTrip.startTimestamp, storedWatermark,
        )
    }

    @Test
    fun `a future-dated row is not imported as a trip`() = runTest {
        stubReader(watermark = nowSec - 3 * daySec, rowsOnDisk = listOf(realTrip, futureRow(1800L)))

        importer().syncFromEnergyData()

        val inserted = mutableListOf<TripEntity>()
        coVerify(atLeast = 1) { tripRepository.insertTrip(capture(inserted)) }
        assertEquals(
            "only the real trip may be imported",
            listOf(realTrip.startTimestamp * 1000L), inserted.map { it.startTs },
        )
    }

    @Test
    fun `a watermark already in the future heals and real trips import again`() = runTest {
        // The state of a user poisoned before the fix: watermark ~400 days ahead.
        stubReader(watermark = nowSec + 400 * daySec, rowsOnDisk = listOf(realTrip, futureRow(10L)))

        importer().syncFromEnergyData()

        val inserted = mutableListOf<TripEntity>()
        coVerify(atLeast = 1) { tripRepository.insertTrip(capture(inserted)) }
        assertEquals(listOf(realTrip.startTimestamp * 1000L), inserted.map { it.startTs })
        assertEquals("healed watermark", realTrip.startTimestamp, storedWatermark)
    }

    @Test
    fun `a poisoned watermark heals even when the energydata file did not change`() = runTest {
        // After the update the file may stay untouched until the next drive ends; the heal must
        // not wait for that, so it runs before the unchanged-source shortcut.
        stubReader(
            watermark = nowSec + 400 * daySec,
            rowsOnDisk = listOf(realTrip, futureRow(10L)),
            sourceChanged = false,
        )

        importer().syncFromEnergyData()

        coVerify(exactly = 1) { tripRepository.insertTrip(any()) }
    }

    @Test
    fun `deduplicateWithExisting does not set a future watermark`() = runTest {
        stubReader(watermark = 0L, rowsOnDisk = listOf(realTrip, futureRow(1800L)))

        importer().deduplicateWithExisting()

        assertEquals(
            "watermark must stay at the newest real trip, not jump to the future row",
            realTrip.startTimestamp, storedWatermark,
        )
    }
}
