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
    private val autoservice = mockk<AutoserviceClient>(relaxed = true)
    private val impl = VehicleApiImpl(
        autoservice, helper, allowlist,
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

    // Literal addresses on purpose: a wrong fid number in a constant must fail here. The pilot
    // state lives on the ADAS device (1038), the rest on the Setting device (1023).
    @Test fun `each of the car's refusals is read at its own address and blocks the command`() = runTest {
        val refusals = listOf(
            Triple(1023, 1200619598, 1L) to "verdict=blocked repair=1",
            Triple(1023, 1200619557, 1L) to "verdict=blocked warning=1",
            Triple(1023, 1200619544, 1L) to "verdict=blocked thermal=1",
            Triple(1038, 700448800, 6L) to "verdict=blocked pilot=6",
        )
        for ((read, verdict) in refusals) {
            val (dev, fid, value) = read
            audit.clear()
            io.mockk.clearMocks(helper)
            coEvery { helper.read(1023, 1200619534, any()) } returns 2L
            coEvery { helper.read(dev, fid, any()) } returns value
            val err = impl.dispatch("打开尾翼").exceptionOrNull()
            assertTrue("$verdict got $err", err is VehicleWriteError.StateBlocked)
            assertEquals(verdict, verdictRow().error)
            coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
        }
    }

    @Test fun `auto mode on writes 1 to its own fid and is confirmed by the mode status`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1023, 1200619594, any()) } returnsMany listOf(1L, 2L)
        coEvery { helper.writeStatus(1023, 1276182596, 1) } returns 1
        assertTrue(impl.dispatch("尾翼自动模式").isSuccess)
        assertEquals("verdict=OK", verdictRow().error)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182596, 1) }
    }

    @Test fun `the auto mode entry is one value on the fid the voice assistant uses`() {
        val entry = allowlist.find("spoiler_auto_on")!!
        assertEquals(listOf(1023, 1276182596, 1, 1), listOf(entry.dev, entry.writeFid, entry.valueMin, entry.valueMax))
        assertEquals(false, entry.validated)
        assertEquals(false, WriteAllowlist.isBanned(1023, 1276182596))
        // The settings service's table names 0x4C110046 for the same switch; it stays closed.
        assertTrue(WriteAllowlist.isBanned(1023, 1276182598))
    }

    @Test fun `a raised spoiler in a standing car is not lowered`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1023, 1200619531, any()) } returns 5L
        coEvery { helper.read(1023, 1200619536, any()) } returns 100L
        coEvery { autoservice.getFloat(any(), any()) } returns 0f
        val err = impl.dispatch("关闭尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        assertTrue(verdictRow().error, verdictRow().error!!.startsWith("verdict=blocked standing"))
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
