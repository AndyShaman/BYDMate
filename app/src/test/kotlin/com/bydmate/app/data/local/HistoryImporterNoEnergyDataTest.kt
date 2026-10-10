package com.bydmate.app.data.local

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.dao.TripTombstoneDao
import com.bydmate.app.data.repository.LastSessionRepository
import com.bydmate.app.data.repository.OdometerMarks
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File

/** #301, Song Plus: a car that has no energydata folder (`energydata dir: exists=false`). The
 *  one-time consumption recalculation has nothing to read there; in the log every sync ended in
 *  `E/HistoryImporter: recalculateConsumption failed` with a stack trace. */
@RunWith(RobolectricTestRunner::class)
class HistoryImporterNoEnergyDataTest {

    @get:Rule val tmp = TemporaryFolder()

    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)

    private fun importer(reader: EnergyDataReader) = HistoryImporter(
        context = mockk<Context>(relaxed = true),
        energyDataReader = reader,
        tripRepository = mockk<TripRepository>(relaxed = true),
        tripDao = mockk<TripDao>(relaxed = true),
        tripPointDao = mockk<TripPointDao>(relaxed = true),
        idleDrainDao = mockk<IdleDrainDao>(relaxed = true),
        settingsRepository = settingsRepository,
        lastSessionRepository = mockk<LastSessionRepository>(relaxed = true),
        tripTombstoneDao = mockk<TripTombstoneDao>(relaxed = true),
        costCalculator = mockk(relaxed = true),
        odometerMarks = mockk<OdometerMarks>(relaxed = true),
    )

    private fun readerWithDir(dir: File): EnergyDataReader {
        val spy = spyk(EnergyDataReader(ApplicationProvider.getApplicationContext<Context>()))
        every { spy.energyDataDir() } returns dir
        return spy
    }

    private fun errors(): List<String> =
        ShadowLog.getLogsForTag("HistoryImporter").filter { it.type == Log.ERROR }.map { it.msg }

    @Test fun `a car without the energydata folder skips the recalculation without an error`() = runTest {
        coEvery { settingsRepository.isConsumptionRecalcDone() } returns false

        assertEquals(0, importer(readerWithDir(File(tmp.root, "absent"))).recalculateConsumptionFromEnergyData())

        assertEquals(emptyList<String>(), errors())
        // A folder that is only missing right now (storage not mounted yet) must not close the
        // one-time recalculation for a car that does have energydata.
        coVerify(exactly = 0) { settingsRepository.setConsumptionRecalcDone() }
    }

    @Test fun `a folder that is there but cannot be read is still an error`() = runTest {
        coEvery { settingsRepository.isConsumptionRecalcDone() } returns false

        assertEquals(0, importer(readerWithDir(tmp.newFolder("energydata"))).recalculateConsumptionFromEnergyData())

        assertEquals(listOf("recalculateConsumption failed"), errors())
        coVerify(exactly = 0) { settingsRepository.setConsumptionRecalcDone() }
    }
}
