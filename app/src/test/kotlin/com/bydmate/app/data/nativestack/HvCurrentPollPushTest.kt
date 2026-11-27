package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.push.FidPushApplier
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #429: Song Plus EV 2025 reports -1.0f sentinel on hvCurrent tx=7 while tx=5 carries live
 * CAN-offset ints ((raw - 5000) / 10 A), and push packets carry live intValue with doubleValue=0.0.
 */
class HvCurrentPollPushTest {

    private val hvCurrentFid = FidMap.entries.first { it.field == "hvCurrent" }
    private val socFid = FidMap.entries.first { it.field == "soc" }

    @Before fun setUp() {
        FidAddresses.resetToConstants()
    }

    @After fun tearDown() {
        FidAddresses.resetToConstants()
    }

    private fun readerWithResponses(
        hvFloat: Float,
        hvInt: Int,
        hvIntStatus: Int = 0,
    ): NativeParsReader {
        val settings = mockk<SettingsRepository>().also {
            coEvery { it.getBatteryCapacity() } returns 87.04
        }
        val gate = mockk<BatchReadGate>().also {
            coEvery { it.mode() } returns BatchMode.ACTIVE
            coEvery { it.recordComparison(any(), any()) } just Runs
            every { it.recordBatchUnavailable() } just Runs
        }
        val helper = mockk<HelperClient>()
        coEvery { helper.readBatch(any()) } answers {
            firstArg<List<BatchReadItem>>().map { item ->
                when {
                    item.dev == socFid.device && item.fid == socFid.fid && item.tx == socFid.transact ->
                        0 to java.lang.Float.floatToRawIntBits(60.0f)
                    item.dev == hvCurrentFid.device && item.fid == hvCurrentFid.fid && item.tx == 7 ->
                        0 to java.lang.Float.floatToRawIntBits(hvFloat)
                    item.dev == hvCurrentFid.device && item.fid == hvCurrentFid.fid && item.tx == 5 ->
                        hvIntStatus to hvInt
                    else -> 0 to -10011
                }
            }
        }
        return NativeParsReader(mockk<AutoserviceClient>(), settings, helper, gate, kindMonitor())
    }

    @Test fun `poll falls back to CAN int when float read returns sentinel`() = runTest {
        val data = checkNotNull(readerWithResponses(hvFloat = -1.0f, hvInt = 5788).fetch())
        assertNotNull("hvCurrent must decode from tx=5 CAN int when tx=7 returns sentinel", data.hvCurrent)
        assertEquals(78.8, data.hvCurrent!!, 0.01)
    }

    @Test fun `poll keeps float reading on a working car regardless of int answer`() = runTest {
        val dataWithErrorInt = checkNotNull(readerWithResponses(hvFloat = 120.5f, hvInt = -10013).fetch())
        assertEquals(120.5, dataWithErrorInt.hvCurrent!!, 0.01)

        val dataWithOtherInt = checkNotNull(readerWithResponses(hvFloat = 120.5f, hvInt = 5000).fetch())
        assertEquals(120.5, dataWithOtherInt.hvCurrent!!, 0.01)
    }

    @Test fun `poll leaves hvCurrent null when both float and int reads are dead`() = runTest {
        val data = checkNotNull(readerWithResponses(hvFloat = -1.0f, hvInt = -10013).fetch())
        assertNull(data.hvCurrent)
        assertNull(data.batteryPowerW)
    }

    @Test fun `push decodes intValue when poll found the car in int mode`() = runTest {
        readerWithResponses(hvFloat = -1.0f, hvInt = 5788).fetch()

        val patched = FidPushApplier.apply(diParsData(), "hvCurrent", intValue = 5158, doubleValue = 0.0)
        assertEquals(15.8, patched?.hvCurrent!!, 0.01)
    }

    @Test fun `push uses doubleValue on a car where float poll works`() = runTest {
        readerWithResponses(hvFloat = 120.5f, hvInt = -10013).fetch()

        val patched = FidPushApplier.apply(diParsData(), "hvCurrent", intValue = 0, doubleValue = -12.5)
        assertEquals(-12.5, patched?.hvCurrent!!, 0.001)

        val patchedZero = FidPushApplier.apply(diParsData(), "hvCurrent", intValue = 5158, doubleValue = 0.0)
        assertEquals(0.0, patchedZero?.hvCurrent!!, 0.001)
    }
}
