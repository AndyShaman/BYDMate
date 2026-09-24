package com.bydmate.app.data.local

import android.content.Context
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.OdometerMarks
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

/** energydata has no odometer: an imported trip takes it from the live odometer marks. */
class HistoryImporterOdometerTest {

    private val energyDataReader = mockk<EnergyDataReader>(relaxed = true)
    private val tripRepository = mockk<TripRepository>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val odometerMarks = mockk<OdometerMarks>(relaxed = true)

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
        odometerMarks = odometerMarks,
    )

    private val record = BydTripRecord(
        id = 71L, startTimestamp = 1_700_000_000L, endTimestamp = 1_700_004_680L,
        duration = 4680L, tripKm = 50.5, electricityKwh = 10.8,
    )
    private val startMs = record.startTimestamp * 1000L
    private val endMs = record.endTimestamp * 1000L

    private fun marksEndingAt(lastTs: Long, lastKm: Double) {
        every { odometerMarks.snapshot() } returns listOf(
            OdometerMarks.Mark(startMs - 60_000L, startMs, lastKm - 50.5, lastTs, lastKm,
                lastChangeTs = minOf(lastTs, endMs)))
    }

    private fun stubSource(existingByTime: TripEntity? = null) {
        coEvery { energyDataReader.peekSourceChanged(any()) } returns true
        coEvery { energyDataReader.readTripsSince(any()) } returns listOf(record)
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { tripDao.getByBydId(record.id) } returns null
        coEvery { tripDao.getByStartTsRange(any(), any()) } returns existingByTime
    }

    @Test fun `imported trip gets the odometer from the matching mark`() = runTest {
        // The car stayed on for 10 min after the drive; the odometer stopped at the end.
        marksEndingAt(endMs + 10 * 60_000L, 11092.9)
        stubSource()

        importer().syncFromEnergyData()

        val inserted = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.insertTrip(capture(inserted)) }
        assertEquals(11092.9, inserted.captured.odometerEndKm!!, 1e-9)
        assertEquals(11042.4, inserted.captured.odometerStartKm!!, 1e-9)
    }

    @Test fun `no matching mark leaves the odometer empty`() = runTest {
        marksEndingAt(endMs - 10 * 60_000L, 11080.0)
        stubSource()

        importer().syncFromEnergyData()

        val inserted = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.insertTrip(capture(inserted)) }
        assertNull(inserted.captured.odometerEndKm)
        assertNull(inserted.captured.odometerStartKm)
    }

    @Test fun `dedup merge keeps the odometer the existing trip already has`() = runTest {
        marksEndingAt(endMs + 1_000L, 99999.9)
        val existing = TripEntity(
            id = 5L, startTs = startMs, endTs = endMs, distanceKm = 50.0,
            odometerStartKm = 11042.4, odometerEndKm = 11092.4, source = "native_polling",
        )
        stubSource(existingByTime = existing)

        importer().syncFromEnergyData()

        val updated = slot<TripEntity>()
        coVerify(exactly = 1) { tripRepository.updateTrip(capture(updated)) }
        assertEquals(50.5, updated.captured.distanceKm!!, 1e-9)
        assertEquals(11042.4, updated.captured.odometerStartKm!!, 1e-9)
        assertEquals(11092.4, updated.captured.odometerEndKm!!, 1e-9)
    }

    /** Rows as the database holds them: the fake conditional update honours its WHERE. */
    private val rows = mutableMapOf<Long, Pair<Double?, Double?>>()

    private fun stubFill() {
        coEvery { tripDao.fillOdometerIfEmpty(any(), any(), any()) } answers {
            val id = firstArg<Long>()
            if (rows[id] != Pair(null, null)) {
                0
            } else {
                rows[id] = Pair(secondArg<Double?>(), thirdArg<Double?>())
                1
            }
        }
    }

    @Test fun `later fill completes an empty energydata trip inside the marks window only`() = runTest {
        marksEndingAt(endMs + 1_000L, 11092.9)
        val empty = TripEntity(id = 1L, startTs = startMs, endTs = endMs, distanceKm = 50.5, source = "energydata")
        val known = empty.copy(id = 2L, odometerStartKm = 1.0, odometerEndKm = 51.5)
        val old = empty.copy(id = 3L, startTs = 1_000L, endTs = 2_000L)
        coEvery { tripDao.getAllSnapshot() } returns listOf(old, empty, known)
        rows[1L] = Pair(null, null)
        stubFill()

        importer().fillOdometerFromMarks()

        coVerify(exactly = 1) { tripDao.fillOdometerIfEmpty(any(), any(), any()) }
        coVerify(exactly = 0) { tripRepository.updateTrip(any()) }
        assertEquals(11042.4, rows[1L]!!.first!!, 1e-9)
        assertEquals(11092.9, rows[1L]!!.second!!, 1e-9)
    }

    @Test fun `fill leaves a row whose odometer became known after the snapshot`() = runTest {
        marksEndingAt(endMs + 1_000L, 11092.9)
        val empty = TripEntity(id = 1L, startTs = startMs, endTs = endMs, distanceKm = 50.5, source = "energydata")
        coEvery { tripDao.getAllSnapshot() } returns listOf(empty)
        rows[1L] = Pair(11042.0, 11092.5) // written between the snapshot and the fill
        stubFill()

        importer().fillOdometerFromMarks()

        coVerify(exactly = 1) { tripDao.fillOdometerIfEmpty(1L, 11042.4, 11092.9) }
        assertEquals(Pair(11042.0, 11092.5), rows[1L])
    }
}
