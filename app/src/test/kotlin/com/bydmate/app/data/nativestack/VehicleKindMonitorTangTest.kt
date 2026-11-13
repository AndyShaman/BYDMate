package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.FidRegistry.TX_GET_INT
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The car-type probe reads the hybrid kind fids from the installed hybrid table (#394). */
class VehicleKindMonitorTangTest {

    @After fun restoreConstants() = FidAddresses.resetToConstants()

    private val settings: SettingsRepository = mockk<SettingsRepository>().also { s ->
        val map = slot<Map<String, String>>()
        coEvery { s.getString(any(), any()) } answers { secondArg() }
        coEvery { s.setStrings(capture(map)) } returns Unit
    }

    private fun tangItems(): List<BatchReadItem> {
        val table = TangHybrid.table()
        return listOf("mileageHevKm", "mileageEvKm", "fuelPercent").map {
            BatchReadItem(TX_GET_INT, table.device(it), table.fid(it))
        }
    }

    @Test fun `with the Tang table two reads 10 s apart on the catalog addresses confirm HYBRID`() = runTest {
        FidAddresses.installHybrid(TangHybrid.table())
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(tangItems()) } returns listOf(0 to 52000, 0 to 31000, 0 to 40)
        val monitor = VehicleKindMonitor(settings, helper)

        monitor.onServiceStart(0L)
        assertTrue(monitor.claimProbe(10_000L))
        monitor.probe(10_000L)

        assertEquals(VehicleKind.HYBRID, monitor.kind.value)
        coVerify(exactly = 2) { helper.readBatch(tangItems()) }
    }

    @Test fun `with the default table the probe batch is exactly the compiled ITEMS`() = runTest {
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(VehicleKindMonitor.ITEMS) } returns listOf(0 to 52000, 0 to 31000, 0 to 40)
        val monitor = VehicleKindMonitor(settings, helper)

        monitor.onServiceStart(0L)

        coVerify(exactly = 1) { helper.readBatch(VehicleKindMonitor.ITEMS) }
    }

    @Test fun `an unconfirmed round starts again when a different hybrid table is installed`() = runTest {
        val seen = mutableListOf<List<BatchReadItem>>()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } answers {
            seen += firstArg<List<BatchReadItem>>()
            listOf(0 to -10011, 0 to -10011, 0 to -10011)
        }
        val monitor = VehicleKindMonitor(settings, helper)

        monitor.onServiceStart(0L)
        for (i in 1 until VehicleKindMonitor.MAX_ATTEMPTS) {
            val at = i * 10_000L
            assertTrue(monitor.claimProbe(at))
            monitor.probe(at)
        }
        val roundEnd = 100_000L
        assertFalse("the round is spent", monitor.claimProbe(roundEnd))

        FidAddresses.installHybrid(TangHybrid.table())

        assertTrue("a new table restarts the round", monitor.claimProbe(roundEnd))
        monitor.probe(roundEnd)
        assertEquals(tangItems(), seen.last())
    }
}
