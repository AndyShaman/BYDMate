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
        coEvery { auto.getIntRaw(any(), any()) } returns null
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

    /** DiLink 5.0: the primary RR fid answers, so the alternative-generation fid is ignored. */
    @Test
    fun `windowRR prefers the primary generation fid`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("windowRR").field to 30,
                fid("windowRRGen3").field to 80,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(30, data!!.windowRR)
    }

    /** DiLink 3.0 (#79): the primary RR fid is a link-error sentinel (65535 → null after
     *  SentinelDecoder), so the value comes from the alternative-generation fid. */
    @Test
    fun `windowRR falls back to the alternative generation fid on a link error`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("windowRR").field to 65535,
                fid("windowRRGen3").field to 80,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(80, data!!.windowRR)
    }

    /** A transient sentinel is NOT "this fid does not exist here": answering it from the
     *  other generation's fid would report a value read somewhere else (codex, wave C). */
    @Test
    fun `windowRR does not fall back on a transient sentinel`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("windowRR").field to -10013,   // wrong transact, transient
                fid("windowRRGen3").field to 80,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val data = NativeParsReader(auto, settings, helper, gate).fetch()

        assertNotNull("fetch() returned null", data)
        assertNull("a transient sentinel must not borrow the other generation's fid", data!!.windowRR)
    }

    /** A per-item protocol failure is not a link error either — no raw value to gate on. */
    @Test
    fun `windowRR does not fall back when the primary read itself failed`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val pairs = FidMap.entries.map { entry ->
            when (entry.field) {
                "soc" -> 0 to java.lang.Float.floatToRawIntBits(50.0f)
                "windowRR" -> -998 to 65535
                "windowRRGen3" -> 0 to 80
                else -> 0 to -10011
            }
        }
        coEvery { helper.readBatch(any()) } returns pairs
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val data = NativeParsReader(auto, settings, helper, gate).fetch()

        assertNotNull("fetch() returned null", data)
        assertNull(data!!.windowRR)
    }

    @Test
    fun `sunroof percent decodes from batch`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("sunroof").field to 50,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(50, data!!.sunroof)
    }

    /** driveMode 0 is the transient state while the driver switches modes, not a mode. With
     *  no mode seen yet in this process there is nothing to hold, so it stays null. */
    @Test
    fun `driveMode transient zero is suppressed to null`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("driveMode").field to 0,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertNull(data!!.driveMode)
    }

    @Test
    fun `driveMode off-road code 4 decodes from batch`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("driveMode").field to 4,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val reader = NativeParsReader(auto, settings, helper, gate)
        val data = reader.fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals(4, data!!.driveMode)
    }

    /** Codex, wave C: dropping the transient 0 to null and then reporting the real mode again
     *  is a null→value edge, which "!=" rules read as a change. Holding the last real mode
     *  means the switch produces no edge at all. */
    @Test
    fun `driveMode holds the last real mode across a transient zero`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val gate = gateFixedAt(BatchMode.ACTIVE)
        fun pairs(driveMode: Int) = mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("driveMode").field to driveMode,
            ),
        )
        val reader = NativeParsReader(auto, settings, helper, gate)

        coEvery { helper.readBatch(any()) } returns pairs(3)
        assertEquals(3, reader.fetch()!!.driveMode)

        coEvery { helper.readBatch(any()) } returns pairs(0)
        assertEquals("a switch in flight must look like no change", 3, reader.fetch()!!.driveMode)

        coEvery { helper.readBatch(any()) } returns pairs(2)
        assertEquals(2, reader.fetch()!!.driveMode)

        coEvery { helper.readBatch(any()) } returns pairs(0)
        assertEquals("and the held value follows the latest real mode", 2, reader.fetch()!!.driveMode)
    }

    /** An unavailable fid stays unavailable — the hold only covers the transient 0. */
    @Test
    fun `driveMode reports null again when the fid goes unavailable`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val gate = gateFixedAt(BatchMode.ACTIVE)
        val reader = NativeParsReader(auto, settings, helper, gate)

        coEvery { helper.readBatch(any()) } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("driveMode").field to 3,
            ),
        )
        assertEquals(3, reader.fetch()!!.driveMode)

        // -10011 is rejected by SentinelDecoder, so the field decodes to null.
        coEvery { helper.readBatch(any()) } returns mostlySentinelPairs(
            mapOf(fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f)),
        )
        assertNull(reader.fetch()!!.driveMode)
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

    /** The ADB path must decide the fallback from ONE raw sample of the primary fid: reading it
     *  twice (once filtered, once raw) would let a window that moved in between decide the
     *  generation (codex, wave C). */
    @Test
    fun `the ADB path samples the primary window fid exactly once`() = runTest {
        val rr = fid("windowRR")
        val gen3 = fid("windowRRGen3")
        val auto = liveAutoservice(soc = 55.0f)
        coEvery { auto.getIntRaw(rr.device, rr.fid) } returns 65535
        coEvery { auto.getInt(gen3.device, gen3.fid) } returns 80
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val gate = gateFixedAt(BatchMode.OFF)

        val data = NativeParsReader(auto, settings, helper, gate).fetch()

        assertNotNull("fetch() returned null", data)
        assertEquals("the fallback must come from that same sample", 80, data!!.windowRR)
        coVerify(exactly = 1) { auto.getIntRaw(rr.device, rr.fid) }
        coVerify(exactly = 0) { auto.getInt(rr.device, rr.fid) }
    }

    /** The VALIDATING shadow snapshot is compared and thrown away, so nothing it saw may leak
     *  into the next snapshot that IS returned (codex, wave C). */
    @Test
    fun `the VALIDATING shadow snapshot does not seed the sticky driveMode`() = runTest {
        val auto = liveAutoservice(soc = 60.0f)   // ADB reports no driveMode at all
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val gate = gateFixedAt(BatchMode.VALIDATING)
        fun pairs(driveMode: Int) = mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(50.0f),
                fid("driveMode").field to driveMode,
            ),
        )
        val reader = NativeParsReader(auto, settings, helper, gate)

        coEvery { helper.readBatch(any()) } returns pairs(3)
        assertNull("VALIDATING returns the ADB snapshot, which has no driveMode", reader.fetch()!!.driveMode)

        coEvery { gate.mode() } returns BatchMode.ACTIVE
        coEvery { helper.readBatch(any()) } returns pairs(0)
        assertNull("the shadow's mode must not be held as the last real mode", reader.fetch()!!.driveMode)

        // Positive control: a mode seen on the production path IS held across a transient 0.
        coEvery { helper.readBatch(any()) } returns pairs(3)
        assertEquals(3, reader.fetch()!!.driveMode)
        coEvery { helper.readBatch(any()) } returns pairs(0)
        assertEquals(3, reader.fetch()!!.driveMode)
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

    /** Tech-panel wave: the new fids decode from the same batch, dead ones stay null, and
     *  battery power is the product of the HV pair (#153: positive = draw). */
    @Test
    fun `tech panel fields decode from the batch and derive battery power`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(43.0f),
                fid("insulationKohm").field to 6800,
                fid("motorTempFront").field to 17,
                fid("inverterTempRear").field to 30,
                fid("hvVoltage").field to 499,
                fid("hvCurrent").field to java.lang.Float.floatToRawIntBits(2.4f),
                fid("bmsMaxChargeKw").field to 1732,
                fid("bmsMaxDischargeKw").field to 148,
                fid("motorRpmRear").field to -370,
                fid("compressorW").field to 576,
                fid("tyreTempFL").field to 15,
                fid("pedalBrake").field to 59,
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val data = NativeParsReader(auto, settings, helper, gate).fetch()

        assertNotNull("fetch() returned null", data)
        checkNotNull(data)
        assertEquals(6800, data.insulationKohm)
        assertEquals(17, data.motorTempFront)
        assertEquals(30, data.inverterTempRear)
        assertEquals(499, data.hvVoltage)
        assertEquals(2.4, data.hvCurrent!!, 0.0001)
        assertEquals(173.2, data.bmsMaxChargeKw!!, 0.0001)
        assertEquals(148, data.bmsMaxDischargeKw)
        assertEquals(-370, data.motorRpmRear)
        assertEquals(576, data.compressorW)
        assertEquals(15, data.tyreTempFL)
        assertEquals(59, data.pedalBrake)
        assertEquals(499 * 2.4, data.batteryPowerW!!, 0.0001)
        // -10011 for every other tech fid → null, not a reading.
        assertNull(data.motorTempRear)
        assertNull(data.pedalAccel)
        assertNull(data.tyreTempRR)
        coVerify(exactly = 0) { auto.isAvailable() }
    }

    /** A wrong-transact sentinel must null the field, and with no current there is no power. */
    @Test
    fun `tech panel sentinels null the fields and suppress battery power`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        coEvery {
            helper.readBatch(any())
        } returns mostlySentinelPairs(
            mapOf(
                fid("soc").field to java.lang.Float.floatToRawIntBits(43.0f),
                fid("insulationKohm").field to -10013,
                fid("hvVoltage").field to 499,
                fid("hvCurrent").field to java.lang.Float.floatToRawIntBits(-1.0f),
            ),
        )
        val gate = gateFixedAt(BatchMode.ACTIVE)

        val data = NativeParsReader(auto, settings, helper, gate).fetch()

        assertNotNull("fetch() returned null", data)
        checkNotNull(data)
        assertNull(data.insulationKohm)
        assertNull(data.hvCurrent)
        assertNull(data.batteryPowerW)
        assertEquals(499, data.hvVoltage)
    }

    /** One transaction for the whole map, tech fids included. */
    @Test
    fun `batch carries every FidMap entry in a single readBatch call`() = runTest {
        val auto = mockk<AutoserviceClient>()
        val settings = settingsWithCapacity()
        val helper = mockk<HelperClient>()
        val items = slot<List<com.bydmate.app.data.vehicle.BatchReadItem>>()
        coEvery { helper.readBatch(capture(items)) } returns allOkPairs()
        val gate = gateFixedAt(BatchMode.ACTIVE)

        NativeParsReader(auto, settings, helper, gate).fetch()

        coVerify(exactly = 1) { helper.readBatch(any()) }
        assertEquals(FidMap.entries.size, items.captured.size)
    }
}
