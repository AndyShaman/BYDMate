package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
    private val audit = mutableListOf<VehicleWriteLogEntity>()
    private val dao = mockk<VehicleWriteLogDao> { coEvery { insert(capture(audit)) } returns Unit }
    private val impl = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        dao, seatStore, windowStore,
    )

    /** The row that records the channel's verdict (the per-write rows carry no "verdict="). */
    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }

    @Test fun `on writes dev 1023 and succeeds when the state follows`() = runTest {
        coEvery { helper.writeStatus(1023, 944767029, 2) } returns 1
        coEvery { helper.read(1023, 1116733454, any()) } returnsMany listOf(1L, 2L)
        assertTrue(impl.dispatch("方向盘加热").isSuccess)
        coVerify(exactly = 0) { helper.writeStatus(1000, any(), any()) }
        val row = verdictRow()
        assertEquals("verdict=OK", row.error)
        assertEquals(2, row.readback)
        assertEquals(1023, row.dev)
        assertEquals(0, row.status)
    }

    @Test fun `a car without the heater fails with NotEquipped and writes nothing`() = runTest {
        coEvery { helper.read(1023, 1116733454, any()) } returns 0L
        val err = impl.dispatch("关闭方向盘加热").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotEquipped)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertEquals("verdict=not equipped", verdictRow().error)
    }

    @Test fun `an unreadable state is not confirmed, never falls back, and says so in the audit`() = runTest {
        coEvery { helper.writeStatus(1023, 944767029, 2) } returns 1
        coEvery { helper.read(1023, 1116733454, any()) } returns null
        val err = impl.dispatch("方向盘加热").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.HelperUnreachable)
        coVerify(exactly = 0) { helper.writeStatus(1000, any(), any()) }
        val row = verdictRow()
        assertEquals("verdict=unconfirmed", row.error)
        assertEquals(null, row.readback)
        assertEquals(-1, row.status)
    }

    @Test fun `unchanged state retries on dev 1000 and fails as unsupported`() = runTest {
        coEvery { helper.writeStatus(any(), 944767029, 2) } returns 1
        coEvery { helper.read(1023, 1116733454, any()) } returns 1L
        val err = impl.dispatch("方向盘加热").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.Unsupported)
        coVerify(exactly = 1) { helper.writeStatus(1023, 944767029, 2) }
        coVerify(exactly = 1) { helper.writeStatus(1000, 944767029, 2) }
        val row = verdictRow()
        assertEquals("verdict=no effect", row.error)
        assertEquals(1000, row.dev)
        assertEquals(1, row.readback)
    }
}
