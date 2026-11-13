package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The hybrid tail of the poll batch follows the installed hybrid table (#394). */
class NativeParsReaderHybridTangTest {

    @After fun restoreConstants() = FidAddresses.resetToConstants()

    private suspend fun hybridBatch(): List<BatchReadItem> {
        val helper = mockk<HelperClient>()
        val items = slot<List<BatchReadItem>>()
        coEvery { helper.readBatch(capture(items)) } returns null
        val auto = mockk<AutoserviceClient> { coEvery { isAvailable() } returns false }
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getBatteryCapacity() } returns 72.9
        val gate = mockk<BatchReadGate>()
        coEvery { gate.mode() } returns BatchMode.ACTIVE
        coEvery { gate.recordComparison(any(), any()) } just Runs
        every { gate.recordBatchUnavailable() } just Runs

        NativeParsReader(auto, settings, helper, gate, kindMonitor(VehicleKind.HYBRID)).fetch()
        return items.captured
    }

    private fun triple(item: BatchReadItem) = Triple(item.tx, item.dev, item.fid)

    @Test fun `with the Tang table the hybrid tail reads the installed addresses and the EV part is unchanged`() = runTest {
        val defaultBatch = hybridBatch()
        FidAddresses.installHybrid(TangHybrid.table())
        val tangBatch = hybridBatch()

        val evSize = FidMap.entries.size
        assertEquals(evSize + FidMap.hybrid.size, tangBatch.size)
        assertEquals(defaultBatch.take(evSize), tangBatch.take(evSize))
        val table = TangHybrid.table()
        assertEquals(
            FidMap.hybrid.map { Triple(it.transact, table.device(it.field), table.fid(it.field)) },
            tangBatch.drop(evSize).map(::triple),
        )
        // engineRpm has no catalog symbol: it stays on its compiled address.
        assertEquals(Triple(5, 1012, 339738642), triple(tangBatch.last { it.dev == 1012 }))
    }

    @Test fun `with the default table the hybrid tail is the compiled addresses`() = runTest {
        val batch = hybridBatch()
        assertEquals(
            FidMap.hybrid.map { Triple(it.transact, it.device, it.fid) },
            batch.drop(FidMap.entries.size).map(::triple),
        )
    }
}
