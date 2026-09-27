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

/** dispatch() sends every drive mode command through DriveModeChannel on the dev=1023 carve-out. */
class VehicleApiDriveModeTest {
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
    private var kmh: Float? = 0f
    private val autoservice = mockk<AutoserviceClient>(relaxed = true).also {
        coEvery { it.getFloat(1013, any()) } answers { kmh }
    }
    private val impl = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), autoservice, helper, allowlist,
        dao, seatStore, windowStore,
    )
    private val target = WriteAllowlist.DRIVE_MODE_TARGET_FID

    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }

    private fun supported(mode: DriveMode, flag: Long = 0L) {
        coEvery { helper.read(1023, mode.supportFid, any()) } returns flag
    }

    @Test fun `the old D+ string ECO模式 switches to eco on dev 1023`() = runTest {
        supported(DriveMode.ECO)
        coEvery { helper.read(1023, target, any()) } returnsMany listOf(1L, 2L)
        coEvery { helper.writeStatus(1023, 1276260400, 2) } returns 1
        assertTrue(impl.dispatch("ECO模式").isSuccess)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276260400, 2) }
        coVerify(exactly = 0) { helper.writeStatus(1006, any(), any()) }
        assertEquals("verdict=OK", verdictRow().error)
        assertEquals(2, verdictRow().readback)
    }

    @Test fun `a mode that did not change fails with a readback mismatch`() = runTest {
        supported(DriveMode.SPORT)
        coEvery { helper.read(1023, target, any()) } returns 1L
        coEvery { helper.writeStatus(1023, 1276260400, 3) } returns 1
        val err = impl.dispatch("运动模式").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.ReadbackMismatch)
        assertEquals("verdict=not changed", verdictRow().error)
    }

    @Test fun `an unsupported mode fails as not equipped and writes nothing`() = runTest {
        supported(DriveMode.ROCK, flag = 1L)
        coEvery { helper.read(1023, target, any()) } returns 1L
        val err = impl.dispatch("岩石模式").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotEquipped)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `flotation fails as state blocked and writes nothing`() = runTest {
        supported(DriveMode.SNOW)
        coEvery { helper.read(1023, target, any()) } returns 10L
        val err = impl.dispatch("雪地模式").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertEquals("verdict=flotation", verdictRow().error)
    }

    @Test fun `a terrain mode above 15 kmh is refused inside VehicleApi, whoever calls it`() = runTest {
        kmh = 40f
        val err = impl.dispatch("雪地模式").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.SpeedBlocked)
        assertEquals(40, (err as VehicleWriteError.SpeedBlocked).speed)
        coVerify(exactly = 0) { helper.read(any(), any(), any()) }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        assertEquals("verdict=too fast", verdictRow().error)
    }

    @Test fun `a terrain mode at exactly 15point0 kmh proceeds`() = runTest {
        kmh = 15.0f
        supported(DriveMode.SNOW)
        coEvery { helper.read(1023, target, any()) } returnsMany listOf(1L, 4L)
        coEvery { helper.writeStatus(1023, 1276260400, 4) } returns 1
        assertTrue(impl.dispatch("雪地模式").isSuccess)
    }

    @Test fun `a terrain mode at 15point1 kmh is refused, the fraction is not truncated away`() = runTest {
        kmh = 15.1f
        val err = impl.dispatch("雪地模式").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.SpeedBlocked)
        assertEquals(16, (err as VehicleWriteError.SpeedBlocked).speed)
        coVerify(exactly = 0) { helper.read(any(), any(), any()) }
    }

    @Test fun `a terrain mode at 15point9 kmh is refused`() = runTest {
        kmh = 15.9f
        val err = impl.dispatch("雪地模式").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.SpeedBlocked)
        assertEquals(16, (err as VehicleWriteError.SpeedBlocked).speed)
    }
}
