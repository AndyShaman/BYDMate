package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.remote.DiParsData
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * DM-i hybrid tail of the poll (#184): [FidMap.hybrid] joins the batch and the plain reads only
 * when the car is HYBRID, and every hybrid sentinel decodes to null.
 */
class NativeParsReaderHybridTest {

    private fun fid(field: String) = FidMap.entries.first { it.field == field }

    private fun floatRaw(value: Float) = java.lang.Float.floatToRawIntBits(value)

    private fun allOkPairs(): List<Pair<Int, Int>> = FidMap.entries.map { entry ->
        if (entry.transact == 7) 0 to floatRaw(50.0f) else 0 to 50
    }

    private fun mostlySentinelPairs(okFields: Map<String, Int>): List<Pair<Int, Int>> =
        FidMap.entries.map { entry -> 0 to (okFields[entry.field] ?: -10011) }

    private fun liveAutoservice(soc: Float): AutoserviceClient {
        val auto = mockk<AutoserviceClient>()
        coEvery { auto.isAvailable() } returns true
        coEvery { auto.getInt(any(), any()) } returns null
        coEvery { auto.getIntRaw(any(), any()) } returns null
        coEvery { auto.getFloat(any(), any()) } returns null
        coEvery { auto.getFloat(fid("soc").device, fid("soc").fid) } returns soc
        return auto
    }

    private fun settingsWithCapacity(): SettingsRepository {
        val settings = mockk<SettingsRepository>()
        coEvery { settings.getBatteryCapacity() } returns 72.9
        return settings
    }

    private fun gateFixedAt(mode: BatchMode): BatchReadGate {
        val gate = mockk<BatchReadGate>()
        coEvery { gate.mode() } returns mode
        coEvery { gate.recordComparison(any(), any()) } just Runs
        every { gate.recordBatchUnavailable() } just Runs
        return gate
    }

    /** The EV batch plus the hybrid tail, in FidMap.hybrid order: fuel, range, total, hev, ev, rpm, evRange. */
    private fun hybridReply(tail: List<Int>): List<Pair<Int, Int>> =
        mostlySentinelPairs(mapOf("soc" to floatRaw(43.0f))) + tail.map { 0 to it }

    private suspend fun fetchHybrid(reply: List<Pair<Int, Int>>): DiParsData {
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns reply
        return checkNotNull(
            NativeParsReader(
                mockk<AutoserviceClient>(), settingsWithCapacity(), helper,
                gateFixedAt(BatchMode.ACTIVE), kindMonitor(VehicleKind.HYBRID),
            ).fetch()
        )
    }

    @Test
    fun `a HYBRID batch is the EV batch plus the seven hybrid fids in order`() = runTest {
        val helper = mockk<HelperClient>()
        val items = slot<List<BatchReadItem>>()
        coEvery { helper.readBatch(capture(items)) } returns null
        val auto = mockk<AutoserviceClient> { coEvery { isAvailable() } returns false }

        NativeParsReader(
            auto, settingsWithCapacity(), helper, gateFixedAt(BatchMode.ACTIVE), kindMonitor(VehicleKind.HYBRID),
        ).fetch()

        assertEquals(98 + 7, items.captured.size)
        assertEquals(
            FidMap.entries.map { Triple(it.transact, it.device, it.fid) },
            items.captured.take(98).map { Triple(it.tx, it.dev, it.fid) },
        )
        assertEquals(
            listOf(
                Triple(5, 1014, 1246785600), Triple(5, 1014, 1246773304), Triple(7, 1014, 1246760976),
                Triple(5, 1014, 1246773264), Triple(5, 1014, 1246773284), Triple(5, 1012, 339738642),
                Triple(5, 1014, 1246765118),
            ),
            items.captured.drop(98).map { Triple(it.tx, it.dev, it.fid) },
        )
    }

    @Test
    fun `UNKNOWN keeps the EV batch`() = runTest {
        val helper = mockk<HelperClient>()
        val items = slot<List<BatchReadItem>>()
        coEvery { helper.readBatch(capture(items)) } returns null
        val auto = mockk<AutoserviceClient> { coEvery { isAvailable() } returns false }

        NativeParsReader(
            auto, settingsWithCapacity(), helper, gateFixedAt(BatchMode.ACTIVE), kindMonitor(VehicleKind.UNKNOWN),
        ).fetch()

        assertEquals(98, items.captured.size)
    }

    @Test
    fun `a hybrid reply decodes into the hybrid fields`() = runTest {
        val data = fetchHybrid(hybridReply(listOf(54, 512, floatRaw(302.4f), 3812, 9240, 1850, 46)))

        assertEquals(54, data.fuelPercent)
        assertEquals(512, data.fuelRangeKm)
        assertEquals(302.4, data.fuelTotalL!!, 0.001)
        assertEquals(3812, data.mileageHevKm)
        assertEquals(9240, data.mileageEvKm)
        assertEquals(1850, data.engineRpm)
        assertEquals(46, data.evRangeKm)
        assertEquals(43, data.soc)
    }

    @Test
    fun `hybrid sentinels decode to null, not to a number`() = runTest {
        val data = fetchHybrid(hybridReply(listOf(255, 2046, floatRaw(-1.0f), 1048575, 1048575, 8191, 2046)))

        assertNull(data.fuelPercent)
        assertNull(data.fuelRangeKm)
        assertNull(data.fuelTotalL)
        assertNull(data.mileageHevKm)
        assertNull(data.mileageEvKm)
        assertNull(data.engineRpm)
        assertNull(data.evRangeKm)
    }

    @Test
    fun `the other hybrid sentinels decode to null too`() = runTest {
        val data = fetchHybrid(hybridReply(listOf(0, 0, floatRaw(Float.NaN), 0, 0, 0, 65535)))
        assertNull(data.fuelTotalL)
        assertNull(data.evRangeKm)
        assertEquals(0, data.engineRpm)
        assertEquals(0, data.mileageHevKm)
        assertNull(fetchHybrid(hybridReply(listOf(0, 0, 0, 0, 0, 0, 1048575))).evRangeKm)
        assertNull("zero litres is no counter", fetchHybrid(hybridReply(listOf(0, 0, floatRaw(0f), 0, 0, 0, 0))).fuelTotalL)
    }

    @Test
    fun `an EV fetch leaves the hybrid fields null`() = runTest {
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns allOkPairs()
        val data = checkNotNull(
            NativeParsReader(
                mockk<AutoserviceClient>(), settingsWithCapacity(), helper, gateFixedAt(BatchMode.ACTIVE), kindMonitor(),
            ).fetch()
        )
        assertNull(data.fuelPercent)
        assertNull(data.fuelTotalL)
        assertNull(data.engineRpm)
        assertNull(data.evRangeKm)
    }

    @Test
    fun `a HYBRID ADB fetch reads the hybrid fids one by one`() = runTest {
        val auto = liveAutoservice(soc = 61.0f)
        coEvery { auto.getInt(1014, 1246785600) } returns 54
        coEvery { auto.getInt(1012, 339738642) } returns 8191
        coEvery { auto.getFloat(1014, 1246760976) } returns 302.4f
        val helper = mockk<HelperClient>()

        val data = checkNotNull(
            NativeParsReader(
                auto, settingsWithCapacity(), helper, gateFixedAt(BatchMode.OFF), kindMonitor(VehicleKind.HYBRID),
            ).fetch()
        )

        assertEquals(54, data.fuelPercent)
        assertNull(data.engineRpm)
        assertEquals(302.4, data.fuelTotalL!!, 0.001)
        assertNull(data.mileageHevKm)
    }
}
