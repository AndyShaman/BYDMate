package com.bydmate.app.data.nativestack

import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.DumpFidsResult
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The manager resolves the hybrid table next to the EV one and installs it (#394). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class FidCatalogManagerHybridTest {

    /** Serves [dump] and answers every probe read from [answers] by fid; -10011 where not listed. */
    private class FakeHelper(
        private val dump: String,
        private val answers: Map<Int, Int>,
    ) : HelperClient by mockk(relaxed = true) {
        override suspend fun dumpFids(): DumpFidsResult = DumpFidsResult.Success(dump)
        override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? =
            items.map { 0 to (answers[it.fid] ?: -10011) }
    }

    private fun manager(helper: HelperClient) = FidCatalogManager(
        ApplicationProvider.getApplicationContext(),
        helper,
        mockk<AutoserviceClient>(relaxed = true).also {
            coEvery { it.getIntRaw(any(), any()) } returns null
            coEvery { it.getFloat(any(), any()) } returns null
        },
    )

    @After fun restoreConstants() {
        FidAddresses.resetToConstants()
        java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "fid-catalog.txt",
        ).delete()
    }

    @Test fun `a resolve on the Tang catalog installs the catalog hybrid addresses`() = runTest {
        val answers = TangHybrid.deadAtCompiled + TangHybrid.liveAtCatalog
        manager(FakeHelper(TangHybrid.catalogText(), answers)).ensureResolved()

        assertEquals(TangHybrid.MILEAGE_HEV, FidAddresses.hybrid.fid("mileageHevKm"))
        assertEquals(TangHybrid.FUEL_PERCENT, FidAddresses.hybrid.fid("fuelPercent"))
    }

    @Test fun `a resolve on the Leopard 3 catalog leaves the hybrid table on the constants`() = runTest {
        val dump = checkNotNull(javaClass.classLoader?.getResource("fid-catalog-leopard3.txt")).readText()
        val plausible = mapOf<Int, Int>().withDefault { 5 }
        val helper = object : HelperClient by mockk(relaxed = true) {
            override suspend fun dumpFids(): DumpFidsResult = DumpFidsResult.Success(dump)
            override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? =
                items.map { 0 to plausible.getValue(it.fid) }
        }
        manager(helper).ensureResolved()

        FidMap.hybrid.forEach { entry ->
            assertEquals(entry.field, FidAddress(entry.device, entry.fid), FidAddresses.hybrid.address(entry.field))
        }
    }

    /**
     * The cache file keeps only the symbols the app uses. A second process start reads that file
     * instead of the daemon, so the hybrid symbols have to be in it too.
     */
    @Test fun `a restart that resolves from the cached catalog still installs the hybrid addresses`() = runTest {
        val answers = TangHybrid.deadAtCompiled + TangHybrid.liveAtCatalog
        manager(FakeHelper(TangHybrid.catalogText(), answers)).ensureResolved()
        FidAddresses.resetToConstants()

        // The daemon is gone on this start: only the cached file can supply the catalog.
        val restarted = object : HelperClient by mockk(relaxed = true) {
            override suspend fun dumpFids(): DumpFidsResult = DumpFidsResult.BinderAbsent
            override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? =
                items.map { 0 to (answers[it.fid] ?: -10011) }
        }
        manager(restarted).ensureResolved()

        assertEquals(TangHybrid.MILEAGE_HEV, FidAddresses.hybrid.fid("mileageHevKm"))
        assertEquals(TangHybrid.FUEL_PERCENT, FidAddresses.hybrid.fid("fuelPercent"))
    }
}
