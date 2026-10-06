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

/** dispatch() routes the HUD on/off commands (#292) through the presence check and the readback. */
class VehicleApiHudTest {
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
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        dao, seatStore, windowStore,
    )

    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }

    @Test fun `off writes 2 and succeeds when the status follows`() = runTest {
        coEvery { helper.read(1023, 951058453, any()) } returns 1L
        coEvery { helper.writeStatus(1023, 1276174371, 2) } returns 1
        coEvery { helper.read(1023, 951058460, any()) } returnsMany listOf(1L, 2L)
        assertTrue(impl.dispatch("关闭抬头显示").isSuccess)
        val row = verdictRow()
        assertEquals("verdict=OK", row.error)
        assertEquals(2, row.readback)
        assertEquals(0, row.status)
    }

    @Test fun `on writes 1 on an AR-HUD`() = runTest {
        coEvery { helper.read(1023, 951058453, any()) } returns 2L
        coEvery { helper.writeStatus(1023, 1276174371, 1) } returns 1
        coEvery { helper.read(1023, 951058460, any()) } returns 1L
        assertTrue(impl.dispatch("打开抬头显示").isSuccess)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276174371, 1) }
    }

    @Test fun `a car without a HUD fails with NotEquipped and writes nothing`() = runTest {
        for (config in listOf(65535L, 0L)) {
            audit.clear()
            coEvery { helper.read(1023, 951058453, any()) } returns config
            val err = impl.dispatch("关闭抬头显示").exceptionOrNull()
            assertTrue("config=$config got $err", err is VehicleWriteError.NotEquipped)
            assertEquals("verdict=no hud", verdictRow().error)
        }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `an unreadable config writes nothing and is not called a missing HUD`() = runTest {
        // No answer, "not initialized" and the autoservice error codes say nothing about the HUD.
        for (config in listOf(null, 1048575L, -10011L, -10013L)) {
            audit.clear()
            coEvery { helper.read(1023, 951058453, any()) } returns config
            val err = impl.dispatch("关闭抬头显示").exceptionOrNull()
            assertTrue("config=$config got $err", err is VehicleWriteError.HelperUnreachable)
            assertEquals("verdict=config unreadable", verdictRow().error)
        }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a write the daemon did not take is unreachable and not read back`() = runTest {
        coEvery { helper.read(1023, 951058453, any()) } returns 1L
        coEvery { helper.writeStatus(1023, 1276174371, 2) } returns null
        val err = impl.dispatch("关闭抬头显示").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.HelperUnreachable)
        coVerify(exactly = 0) { helper.read(1023, 951058460, any()) }
    }

    @Test fun `a status that stays opposite fails as unsupported`() = runTest {
        coEvery { helper.read(1023, 951058453, any()) } returns 1L
        coEvery { helper.writeStatus(1023, 1276174371, 2) } returns 1
        coEvery { helper.read(1023, 951058460, any()) } returns 1L
        val err = impl.dispatch("关闭抬头显示").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.Unsupported)
        assertEquals("verdict=no effect", verdictRow().error)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276174371, 2) }
    }

    @Test fun `an invalid status is unconfirmed`() = runTest {
        coEvery { helper.read(1023, 951058453, any()) } returns 1L
        coEvery { helper.writeStatus(1023, 1276174371, 1) } returns 1
        coEvery { helper.read(1023, 951058460, any()) } returns 65535L
        val err = impl.dispatch("打开抬头显示").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.HelperUnreachable)
        assertEquals("verdict=unconfirmed read=65535", verdictRow().error)
    }
}
