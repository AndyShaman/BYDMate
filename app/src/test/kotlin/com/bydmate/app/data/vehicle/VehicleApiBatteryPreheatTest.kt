package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceRecorder
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * dispatch() routes the battery warm-up commands (#311) through the stock toggle's own checks
 * (function present, no charge gun, the car permits it) and the status readback.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleApiBatteryPreheatTest {

    @get:Rule val trace = TraceRecorder()

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

    private fun TestScope.api() = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        dao, seatStore, windowStore,
    ).also { it.readbackScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)) }

    private fun verdictRow() = audit.single { it.error?.startsWith("verdict=") == true }
    private fun events() = trace.events().map { it.replace(Regex(" #\\d+"), "").replace(Regex(" +"), " ") }

    private fun config(value: Long?) = coEvery { helper.read(DEV, CONFIG, any()) } returns value
    private fun gun(value: Long?) = coEvery { helper.read(DEV, GUN, any()) } returns value
    private fun permit(value: Long?) = coEvery { helper.read(DEV, PERMIT, any()) } returns value
    private fun status(vararg values: Long?) = coEvery { helper.read(DEV, STATUS, any()) } returnsMany values.toList()
    private fun writeAnswers(value: Int, answer: Int?) = coEvery { helper.writeStatus(DEV, SET, value) } returns answer

    /** A cold Leopard 3: the function is fitted, no gun, the car permits the warm-up, -5..-3 °C. */
    @Before fun coldCar() {
        coEvery { helper.read(any(), any(), any()) } returns null
        config(0); gun(1); permit(0)
        coEvery { helper.read(1014, 1148190736, any()) } returns 35L
        coEvery { helper.read(1014, 1148190752, any()) } returns 37L
        coEvery { helper.read(DEV, POWER, any()) } returns 3000L
    }

    @Test fun `on writes 1 and succeeds when the status turns to heating`() = runTest {
        status(0, 0, 1)   // before, then the readback
        writeAnswers(1, 1)
        assertTrue(api().dispatch(ON).isSuccess)
        val row = verdictRow()
        assertEquals("verdict=OK", row.error)
        assertEquals(DEV, row.dev)
        assertEquals(SET, row.fid)
        assertEquals(1, row.requested)
        assertEquals(1, row.readback)
        assertEquals(0, row.status)
        coVerify(exactly = 1) { helper.writeStatus(DEV, SET, 1) }
    }

    @Test fun `off writes 2 and succeeds when the status leaves heating`() = runTest {
        status(1, 1, 0)
        writeAnswers(2, 1)
        assertTrue(api().dispatch(OFF).isSuccess)
        assertEquals("verdict=OK", verdictRow().error)
        coVerify(exactly = 1) { helper.writeStatus(DEV, SET, 2) }
    }

    @Test fun `off does not ask the car for a permit`() = runTest {
        permit(1)
        status(1, 0)
        writeAnswers(2, 1)
        assertTrue(api().dispatch(OFF).isSuccess)
    }

    @Test fun `a car without the function fails with NotEquipped and writes nothing`() = runTest {
        // 65535 is how a firmware without the fid answers; any other non-zero config hides the stock item.
        for (value in listOf(65535L, 1L, 2L)) {
            audit.clear()
            config(value)
            val err = api().dispatch(ON).exceptionOrNull()
            assertTrue("config=$value got $err", err is VehicleWriteError.NotEquipped)
            assertEquals("verdict=no preheat", verdictRow().error)
            assertEquals(0, verdictRow().dev)
        }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `an unreadable config writes nothing and is not called a missing function`() = runTest {
        for (value in listOf(null, 1048575L, -10011L, -10013L)) {
            audit.clear()
            config(value)
            val err = api().dispatch(ON).exceptionOrNull()
            assertTrue("config=$value got $err", err is VehicleWriteError.HelperUnreachable)
            assertEquals("verdict=config unreadable", verdictRow().error)
        }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a connected charge gun blocks both commands, as the stock switch is disabled`() = runTest {
        for (state in listOf(2L, 3L, 4L)) for (command in listOf(ON, OFF)) {
            audit.clear()
            gun(state)
            val err = api().dispatch(command).exceptionOrNull()
            assertTrue("gun=$state $command got $err", err is VehicleWriteError.StateBlocked)
            assertEquals("verdict=gun connected", verdictRow().error)
        }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a gun state of 1 or 5 does not block`() = runTest {
        for (state in listOf(1L, 5L)) {
            audit.clear()
            gun(state)
            status(0, 1)
            writeAnswers(1, 1)
            assertTrue("gun=$state", api().dispatch(ON).isSuccess)
        }
    }

    @Test fun `a warm battery is refused as not needed and nothing is written`() = runTest {
        permit(1)
        status(0)
        val err = api().dispatch(ON).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotNeeded)
        assertEquals("verdict=not needed permit=1", verdictRow().error)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `an unreadable permit writes nothing`() = runTest {
        for (value in listOf(null, 65535L, -10011L)) {
            audit.clear()
            permit(value)
            status(0)
            val err = api().dispatch(ON).exceptionOrNull()
            assertTrue("permit=$value got $err", err is VehicleWriteError.HelperUnreachable)
            assertEquals("verdict=permit unreadable", verdictRow().error)
        }
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `on while already heating succeeds without a write`() = runTest {
        status(1)
        assertTrue(api().dispatch(ON).isSuccess)
        assertEquals("verdict=already heating", verdictRow().error)
        coVerify(exactly = 0) { helper.writeStatus(any(), any(), any()) }
    }

    @Test fun `a write the car denies is unsupported`() = runTest {
        status(0)
        writeAnswers(1, -10011)
        val err = api().dispatch(ON).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.Unsupported)
        assertEquals("verdict=write denied", verdictRow().error)
    }

    @Test fun `a write the daemon did not take is unreachable`() = runTest {
        status(0)
        writeAnswers(1, null)
        val err = api().dispatch(ON).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.HelperUnreachable)
        assertEquals("verdict=unreachable", verdictRow().error)
    }

    @Test fun `on with a status that never turns to heating is a mismatch`() = runTest {
        status(0)
        writeAnswers(1, 1)
        val err = api().dispatch(ON).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.ReadbackMismatch)
        assertEquals("verdict=no effect read=0", verdictRow().error)
        assertEquals(DEV, verdictRow().dev)
    }

    @Test fun `on answered with finished is not needed`() = runTest {
        status(0, 2)
        writeAnswers(1, 1)
        val err = api().dispatch(ON).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.NotNeeded)
        assertEquals("verdict=finished", verdictRow().error)
    }

    @Test fun `off with a status that stays heating is a mismatch`() = runTest {
        status(1)
        writeAnswers(2, 1)
        val err = api().dispatch(OFF).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.ReadbackMismatch)
        assertEquals("verdict=no effect read=1", verdictRow().error)
    }

    @Test fun `a status that cannot be read after the write is unconfirmed`() = runTest {
        status(0, null)
        writeAnswers(1, 1)
        val err = api().dispatch(ON).exceptionOrNull()
        assertTrue("got $err", err is VehicleWriteError.HelperUnreachable)
        assertEquals("verdict=unconfirmed read=err", verdictRow().error)
    }

    @Test fun `a written command is read again later for the trace`() = runTest {
        status(0, 1)
        writeAnswers(1, 1)
        val api = api()
        assertTrue(withContext(Trace.causedBy(7L)) { api.dispatch(ON) }.isSuccess)
        assertEquals(emptyList<String>(), events())

        advanceTimeBy(5_001)
        runCurrent()
        assertEquals(
            listOf("car preheat_late want=on after_s=5 status=1 permit=0 power=3000 tmin=-5 tmax=-3 by=#7"),
            events(),
        )
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(listOf(5, 30, 120), events().map { it.substringAfter("after_s=").substringBefore(" ").toInt() })
    }

    @Test fun `a refused command leaves no late reads`() = runTest {
        permit(1)
        status(0)
        api().dispatch(ON)
        advanceTimeBy(200_000)
        runCurrent()
        assertEquals(emptyList<String>(), events())
    }

    private companion object {
        const val ON = "打开电池预热"
        const val OFF = "关闭电池预热"
        const val DEV = 1009
        const val SET = 1324617774
        const val STATUS = 879755302
        const val PERMIT = 1151336468
        const val CONFIG = 879755304
        const val GUN = 876609586
        const val POWER = 584067131
    }
}
