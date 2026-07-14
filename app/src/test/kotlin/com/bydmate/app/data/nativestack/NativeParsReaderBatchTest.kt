package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Integration coverage for the batch-transport dispatch wired into
 * NativeParsReader.fetch(): BatchMode.ACTIVE/OFF/VALIDATING routing,
 * the decodeBatch pipeline (both tx=5 and tx=7 entries), and the ADB
 * fallback whenever the batch path is unavailable or dead-on-arrival.
 */
class NativeParsReaderBatchTest {

    private fun fid(field: String) = FidMap.entries.first { it.field == field }

    /** All FidMap entries decode successfully: tx=5 -> plain int 50 (satisfies every
     *  range-checked decoder), tx=7 -> IEEE-754 bits of 50.0f. */
    private fun allOkPairs(): List<Pair<Int, Int>> = FidMap.entries.map { entry ->
        when (entry.transact) {
            7 -> 0 to java.lang.Float.floatToRawIntBits(50.0f)
            else -> 0 to 50
        }
    }

    /** Every item fails at the batch protocol level (per-item transact failure, Task 1/2's
     *  documented -998 status) — decodeBatch nulls out every field regardless of tx, which
     *  is what drives assembleSnapshot's liveness gate to null the whole snapshot. */
    private fun allFailedPairs(): List<Pair<Int, Int>> = FidMap.entries.map { -998 to 0 }

    /** [okFields]: field name -> already tx-appropriate raw word (int for tx=5, float bits
     *  for tx=7). Every other entry gets status=0 / word=-10011 (SentinelDecoder rejects it
     *  on the tx=5 path; irrelevant here since those fields aren't asserted on). */
    private fun mostlySentinelPairs(okFields: Map<String, Int>): List<Pair<Int, Int>> =
        FidMap.entries.map { entry -> 0 to (okFields[entry.field] ?: -10011) }

    /** AutoserviceClient stubbed so fetchViaAdb() returns a real, non-null DiParsData
     *  (soc + mileage live; everything else null/absent from the mock). */
    private fun liveAutoservice(soc: Float = 75.0f, mileageRaw: Int = 1000): AutoserviceClient {
        val auto = mockk<AutoserviceClient>()
        coEvery { auto.isAvailable() } returns true
        coEvery { auto.getInt(any(), any()) } returns null
        coEvery { auto.getFloat(any(), any()) } returns null
        coEvery { auto.getFloat(fid("soc").device, fid("soc").fid) } returns soc
        coEvery { auto.getInt(fid("mileage").device, fid("mileage").fid) } returns mileageRaw
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

    @Test
    fun `ACTIVE plus full ok batch decodes without ever touching autoservice`() = runTest {
        val auto = mockk<AutoserviceClient>()
        // Deliberately NOT stubbed beyond mockk() defaults — any call throws inside MockK's
        // strict verification model, but we assert via coVerify(exactly = 0) below anyway.
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns allOkPairs()
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        checkNotNull(data)
        assertEquals(50, data.soc)

        coVerify(exactly = 0) { auto.isAvailable() }
        coVerify(exactly = 0) { auto.getInt(any(), any()) }
        coVerify(exactly = 0) { auto.getFloat(any(), any()) }
    }

    @Test
    fun `ACTIVE plus null readBatch falls back to fetchViaAdb`() = runTest {
        val auto = liveAutoservice(soc = 75.0f)
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns null
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(75, data!!.soc)
        coVerify(exactly = 1) { auto.isAvailable() }
    }

    @Test
    fun `ACTIVE plus all-sentinel batch triggers liveness gate and falls back to ADB`() = runTest {
        val auto = liveAutoservice(soc = 80.0f)
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns allFailedPairs()
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(80, data!!.soc)
        coVerify(exactly = 1) { auto.isAvailable() }
    }

    @Test
    fun `VALIDATING reads both transports, returns the ADB snapshot, and records the comparison`() = runTest {
        val auto = liveAutoservice(soc = 65.0f)
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns allOkPairs()
        val gate = gateFixedAt(BatchMode.VALIDATING)

        // Reference: what fetchViaAdb alone (via OFF dispatch) would have produced, from the
        // exact same autoservice + settings mocks.
        val referenceReader = NativeParsReader(auto, settings, helper, gateFixedAt(BatchMode.OFF))
        val expectedAdb = referenceReader.fetch()
        assertNotNull(expectedAdb)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertEquals("VALIDATING must still return the proven ADB snapshot", expectedAdb, data)

        val adbSlot = slot<DiParsData>()
        val batchSlot = slot<DiParsData>()
        coVerify(exactly = 1) { gate.recordComparison(capture(adbSlot), capture(batchSlot)) }
        assertEquals(expectedAdb, adbSlot.captured)
        assertNotNull("batch side of the comparison must be a decoded snapshot", batchSlot.captured)
        assertEquals(50, batchSlot.captured!!.soc)
    }

    @Test
    fun `tx=7 entry decodes through the raw float-bits path`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(87.0f),
                fid("mileage").field to 1000, // INT_SCALED * 0.1 = 100.0, irrelevant to the assertion
                fid("voltage12v").field to java.lang.Float.floatToRawIntBits(14.0f),
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(87, data!!.soc)
        coVerify(exactly = 0) { auto.isAvailable() }
    }

    @Test
    fun `bmsState decodes from batch and drives the chargingStatus derivation`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("mileage").field to 1000,
                fid("chargeGunState").field to 3,
                fid("bmsState").field to 1,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(1, data!!.bmsState)
        assertEquals(2, data.chargingStatus) // gun connected (3) + bms CHARGING (1) -> code 2
        coVerify(exactly = 0) { auto.isAvailable() }
    }

    @Test
    fun `OFF never touches the batch transport`() = runTest {
        val auto = liveAutoservice(soc = 42.0f)
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val gate = gateFixedAt(BatchMode.OFF)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull(data)
        assertEquals(42, data!!.soc)
        coVerify(exactly = 0) { helper.readBatch(any()) }
    }

    @Test
    fun `ACTIVE with unavailable batch and dead ADB returns null`() = runTest {
        val auto = mockk<AutoserviceClient>()
        coEvery { auto.isAvailable() } returns false
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } returns null
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        assertNull(reader.fetch())
    }
}
