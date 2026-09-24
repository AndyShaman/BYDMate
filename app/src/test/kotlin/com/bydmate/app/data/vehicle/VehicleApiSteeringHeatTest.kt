package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/** dispatch() routes both steering heat commands through the readback channel. */
class VehicleApiSteeringHeatTest {
    private val helper: HelperClient = mockk()
    private val allowlist = WriteAllowlist.loadProduction {
        """{ "wheel_heat_on": { "featureId": 944767029, "deviceType": 1000, "value": 2 },
             "wheel_heat_off": { "featureId": 944767029, "deviceType": 1000, "value": 1 } }"""
    }
    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) = Unit
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }
    private val windowStore = object : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) = Unit
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) = Unit
    }
    private val impl = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        mockk<VehicleWriteLogDao>(relaxed = true), seatStore, windowStore,
    )

    @Test fun `on writes dev 1023 and succeeds when the state follows`() = runTest {
        coEvery { helper.writeStatus(1023, 944767029, 2) } returns 1
        coEvery { helper.read(1023, 1116733454, any()) } returnsMany listOf(1L, 2L)
        assertTrue(impl.dispatch("方向盘加热").isSuccess)
        coVerify(exactly = 0) { helper.writeStatus(1000, any(), any()) }
    }

    @Test fun `a car without the heater fails with NotEquipped`() = runTest {
        coEvery { helper.writeStatus(1023, 944767029, 1) } returns 1
        coEvery { helper.read(1023, 1116733454, any()) } returns 0L
        val err = impl.dispatch("关闭方向盘加热").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotEquipped)
    }

    @Test fun `unchanged state retries on dev 1000 and fails as unsupported`() = runTest {
        coEvery { helper.writeStatus(any(), 944767029, 2) } returns 1
        coEvery { helper.read(1023, 1116733454, any()) } returns 1L
        val err = impl.dispatch("方向盘加热").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.Unsupported)
        coVerify(exactly = 1) { helper.writeStatus(1023, 944767029, 2) }
        coVerify(exactly = 1) { helper.writeStatus(1000, 944767029, 2) }
    }
}
