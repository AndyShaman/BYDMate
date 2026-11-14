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
    /** The speed the helper's own reader gives the last check before the send. */
    private var lockedKmh: Float? = 50f
    /** What the helper's own reader gives the last check for (dev, fid); not listed = the read fails. */
    private val lockedInts = mutableMapOf<Pair<Int, Int>, Int>()
    private val impl = VehicleApiImpl(
        autoservice, helper, allowlist,
        dao, seatStore, windowStore,
    )

    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }

    /** The helper as HelperClientImpl behaves: the guard runs first, a refusal throws and nothing is sent. */
    private fun accepts(fid: Int, value: Int) = coEvery { helper.writeStatus(1023, fid, value, any()) } coAnswers {
        val guard = arg<(suspend (LockedReader) -> Boolean)?>(3)
        val locked = object : LockedReader {
            override suspend fun readFloat(dev: Int, fid: Int) = if (dev == 1013 && fid == SPEED_FID) lockedKmh else null
            override suspend fun readInt(dev: Int, fid: Int) = lockedInts[dev to fid]
        }
        if (guard == null || guard(locked)) 1 else throw WriteGuardRefused()
    }

    @Test fun `up writes 1 and succeeds when the spoiler starts opening`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        accepts(1276182582, 1)
        coEvery { helper.read(1023, 1200619531, any()) } returnsMany listOf(6L, 2L)
        assertTrue(impl.dispatch("打开尾翼").isSuccess)
        assertEquals("verdict=OK", verdictRow().error)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182582, 1, any()) }
    }

    @Test fun `a car without a spoiler fails with NotEquipped and writes nothing`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 1L
        val err = impl.dispatch("打开尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotEquipped)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any(), any()) }
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
            coVerify(exactly = 0) { helper.writeStatus(any(), any(), any(), any()) }
        }
    }

    @Test fun `auto mode on writes 1 to its own fid and is confirmed by the mode status`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1023, 1200619594, any()) } returnsMany listOf(1L, 2L)
        accepts(1276182596, 1)
        assertTrue(impl.dispatch("尾翼自动模式").isSuccess)
        assertEquals("verdict=OK", verdictRow().error)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182596, 1, any()) }
    }

    @Test fun `the auto mode entry is one value on the fid the voice assistant uses`() {
        val entry = allowlist.find("spoiler_auto_on")!!
        assertEquals(listOf(1023, 1276182596, 1, 1), listOf(entry.dev, entry.writeFid, entry.valueMin, entry.valueMax))
        assertEquals(false, entry.validated)
        assertEquals(false, WriteAllowlist.isBanned(1023, 1276182596))
        // The settings service's table names 0x4C110046 for the same switch; it stays closed.
        assertTrue(WriteAllowlist.isBanned(1023, 1276182598))
    }

    @Test fun `no other auto mode value opens up, whatever the source`() {
        val fixture = """
            {
              "auto_any": { "featureId": 1276182596, "deviceType": 1023 },
              "auto_zero": { "featureId": 1276182596, "deviceType": 1023, "value": 0 },
              "auto_two": { "featureId": 1276182596, "deviceType": 1023, "value": 2 },
              "auto_range": { "featureId": 1276182596, "deviceType": 1023, "valueMin": 1, "valueMax": 2 }
            }
        """.trimIndent()
        val al = WriteAllowlist.loadProduction { fixture }
        for (name in listOf("auto_any", "auto_zero", "auto_two", "auto_range")) assertEquals(name, null, al.find(name))
        val values = al.allEntries().filter { it.dev == 1023 && it.writeFid == 1276182596 }.map { it.valueMin }
        assertEquals(listOf(1), values)
    }

    @Test fun `a raised spoiler in a standing car is not lowered`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1023, 1200619531, any()) } returns 5L
        coEvery { helper.read(1023, 1200619536, any()) } returns 100L
        coEvery { autoservice.getFloat(any(), any()) } returns 0f
        val err = impl.dispatch("关闭尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        assertTrue(verdictRow().error, verdictRow().error!!.startsWith("verdict=blocked standing"))
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any(), any()) }
    }

    // Any other address reads 0 here (a standing car), so a wrong speed address refuses the command.
    @Test fun `a raised spoiler in a moving car is lowered, the speed read at its own address`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        coEvery { helper.read(1023, 1200619531, any()) } returnsMany listOf(5L, 3L)
        coEvery { helper.read(1023, 1200619536, any()) } returns 100L
        coEvery { autoservice.getFloat(any(), any()) } returns 0f
        coEvery { autoservice.getFloat(1013, SPEED_FID) } returns 50f
        lockedInts += mapOf((1023 to 1200619531) to 5, (1023 to 1200619536) to 100) // raised at the send too
        accepts(1276182582, 2)
        assertTrue(impl.dispatch("关闭尾翼").isSuccess)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182582, 2, any()) }
    }

    // The first check passes at 50 km/h; by the send the car stands. The helper's guard, run
    // right before the transact, has to refuse: nothing is sent and the driver gets the standstill text.
    @Test fun `a car that stopped before the send is not sent the command`() = runTest {
        for ((command, fid, value) in listOf(Triple("关闭尾翼", 1276182582, 2), Triple("尾翼自动模式", 1276182596, 1))) {
            audit.clear()
            io.mockk.clearMocks(helper)
            coEvery { helper.read(1023, 1200619534, any()) } returns 2L
            coEvery { helper.read(1023, 1200619531, any()) } returns 5L
            coEvery { helper.read(1023, 1200619536, any()) } returns 100L
            coEvery { helper.read(1023, 1200619594, any()) } returns 1L
            coEvery { autoservice.getFloat(1013, SPEED_FID) } returns 50f
            lockedInts += mapOf((1023 to 1200619531) to 5, (1023 to 1200619536) to 100)
            lockedKmh = 0f
            accepts(fid, value)
            val err = impl.dispatch(command).exceptionOrNull()
            assertTrue("$command got $err", err is VehicleWriteError.StateBlocked)
            assertEquals("verdict=blocked standing position=100 speed=0", verdictRow().error)
            assertTrue(command, audit.any { it.error == "pre_send_refused" })
        }
    }

    // Literal addresses again: under the transport's lock the pilot state is still on device 1038.
    @Test fun `a refusal that appears before the send is read at its own address and holds the command`() = runTest {
        val refusals = listOf(
            (1023 to 1200619598) to "verdict=blocked repair=1",
            (1023 to 1200619557) to "verdict=blocked warning=1",
            (1023 to 1200619544) to "verdict=blocked thermal=1",
            (1038 to 700448800) to "verdict=blocked pilot=4",
        )
        for ((address, verdict) in refusals) {
            audit.clear()
            lockedInts.clear()
            io.mockk.clearMocks(helper)
            coEvery { helper.read(1023, 1200619534, any()) } returns 2L
            lockedInts[address] = if (address.first == 1038) 4 else 1
            accepts(1276182582, 1)
            val err = impl.dispatch("打开尾翼").exceptionOrNull()
            assertTrue("$verdict got $err", err is VehicleWriteError.StateBlocked)
            assertEquals(verdict, verdictRow().error)
            assertTrue(verdict, audit.any { it.error == "pre_send_refused" })
        }
    }

    @Test fun `anti-pinch fails with StateBlocked`() = runTest {
        coEvery { helper.read(1023, 1200619534, any()) } returns 2L
        accepts(1276182582, 2)
        coEvery { helper.read(1023, 1200619531, any()) } returnsMany listOf(5L, 4L)
        val err = impl.dispatch("关闭尾翼").exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.StateBlocked)
        coVerify(exactly = 1) { helper.writeStatus(1023, 1276182582, 2, any()) }
    }

    private companion object {
        /** Speed.SPEED_AUTO_SPEED on the speed device (1013), literal on purpose. */
        const val SPEED_FID = -1807745016
    }
}
