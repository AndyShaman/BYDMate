package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** dispatch() routes the spoiler up/down commands through the presence check and the status readback. */
class VehicleApiSpoilerTest {
    private val helper: HelperClient = mockk()
    private val allowlist = WriteAllowlist.loadProduction { "{}" }
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
        mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        dao, seatStore, windowStore,
    )

    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }

    @Test fun `up writes 1 and succeeds when the spoiler starts opening`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.writeStatus(1023, 1276182582, 1) } returns 1
        coEvery { helper.read(1023, 1200619531, any()) } returnsMany listOf(6L, 2L)
        assertTrue(impl.dispatch("打开尾翼").isSuccess)
        assertEquals("verdict=OK", verdictRow().error)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182582, 1) }
    }

    @Test fun `a car without a spoiler fails with NotEquipped and writes nothing`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 1L
        val err = impl.dispatch("打开尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotEquipped)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a spoiler warning fails with StateBlocked and writes nothing`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1023, 1200619557, any()) } returns 1L
        val err = impl.dispatch("打开尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        assertEquals("verdict=blocked warning=1", verdictRow().error)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    // The pilot state lives on the ADAS device, not on the Setting device the spoiler fids are on.
    @Test fun `an active navigation pilot is read on dev 1038 and blocks the command`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1038, 700448800, any()) } returns 6L
        val err = impl.dispatch("关闭尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `anti-pinch fails with StateBlocked`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.writeStatus(1023, 1276182582, 2) } returns 1
        coEvery { helper.read(1023, 1200619531, any()) } returnsMany listOf(5L, 4L)
        val err = impl.dispatch("关闭尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182582, 2) }
    }
}
