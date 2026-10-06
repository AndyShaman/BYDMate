package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * A write without a readback of its own is read once, later, for the trace (#267: the hazard
 * reported ok, the lamps stayed dark). Diagnostics only: the write's result is already returned
 * and nothing waits for the read.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleApiLateVerifyTest {

    @get:Rule val trace = TraceRecorder()

    private val helper: HelperClient = mockk()
    private val allowlist = WriteAllowlist(
        (WriteAllowlist.LIVE_VALIDATED + WriteAllowlist.CANDIDATE_UNVALIDATED).associateBy { it.actionName.lowercase() }
    )
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

    private fun api(scope: CoroutineScope) = VehicleApiImpl(
        mockk<ParsReader>(relaxed = true), mockk<AutoserviceClient>(relaxed = true), helper, allowlist,
        mockk<VehicleWriteLogDao>(relaxed = true), seatStore, windowStore,
    ).also { it.readbackScope = scope }

    private fun events() = trace.events().map { it.replace(Regex(" #\\d+"), "").replace(Regex(" +"), " ") }

    @Test fun `a write without readback is read once after the delay, linked to its cause`() = runTest {
        val entry = allowlist.find("doors_lock")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        coEvery { helper.read(entry.dev, entry.writeFid) } returns 1L
        val api = api(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        val result = withContext(Trace.causedBy(7L)) { api.writeLockDoors() }

        assertTrue(result.isSuccess)
        // The result came back first; nothing was read yet.
        coVerify(exactly = 0) { helper.read(any(), any()) }
        advanceTimeBy(VehicleApiImpl.LATE_VERIFY_MS - 1)
        runCurrent()
        coVerify(exactly = 0) { helper.read(any(), any()) }
        advanceTimeBy(2)
        runCurrent()

        coVerify(exactly = 1) { helper.read(entry.dev, entry.writeFid) }
        assertEquals(listOf("car verify action=doors_lock want=2 got=1 after_ms=800 by=#7"), events())
    }

    @Test fun `a failed read is traced as null, not thrown`() = runTest {
        val entry = allowlist.find("ac_on")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns true
        coEvery { helper.read(any(), any()) } throws IllegalStateException("daemon gone")
        val api = api(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        assertTrue(api.writeAcOn().isSuccess)
        advanceTimeBy(VehicleApiImpl.LATE_VERIFY_MS + 1)
        runCurrent()

        assertEquals(listOf("car verify action=ac_on want=1 got=null after_ms=800"), events())
    }

    @Test fun `a failed write is not read back`() = runTest {
        val entry = allowlist.find("ac_on")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns false
        val api = api(CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)))

        api.writeAcOn()
        advanceTimeBy(VehicleApiImpl.LATE_VERIFY_MS + 1)
        runCurrent()

        coVerify(exactly = 0) { helper.read(any(), any()) }
        assertEquals(emptyList<String>(), events())
    }
}
