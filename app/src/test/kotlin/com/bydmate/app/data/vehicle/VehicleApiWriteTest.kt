package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for VehicleApi structured write methods (Task C.2 / C.5 / C.6).
 *
 * Allowlist built from LIVE_VALIDATED so tests don't depend on competitor JSON asset.
 * All write methods now return Result<Unit>; assertions use isSuccess / isFailure.
 */
class VehicleApiWriteTest {

    private val parsReader: ParsReader = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val helper: HelperClient = mockk()
    private val writeLogDao: VehicleWriteLogDao = mockk(relaxed = true)

    // Build allowlist from LIVE_VALIDATED — same data VehicleApiImpl uses at runtime.
    private val allowlist = WriteAllowlist(
        (WriteAllowlist.LIVE_VALIDATED + WriteAllowlist.CANDIDATE_UNVALIDATED)
            .associateBy { it.actionName.lowercase() }
    )

    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) {}
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }

    private val windowStore = object : WindowChannelStore {
        override fun winner() = WindowChannel.UNKNOWN
        override fun setWinner(channel: WindowChannel) {}
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) {}
    }

    private val api: VehicleApi = VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, windowStore)

    // ── Test 1: writeAcOn happy path ──────────────────────────────────────────

    @Test fun `writeAcOn calls helper write with dev=1000 fid=501219364 val=1 and returns success`() = runTest {
        val entry = allowlist.find("ac_on")!!
        // ac_on = ac_power (501219364) set to 1 (ON); no readbackFid
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns true

        assertTrue(api.writeAcOn().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 1) }
        // No readback for ac_on
        coVerify(exactly = 0) { helper.read(any(), any()) }
    }

    // ── Test 2: writeSetDriverTemp out of range rejected ──────────────────────

    @Test fun `writeSetDriverTemp with celsius=99 is rejected before calling helper`() = runTest {
        val result = api.writeSetDriverTemp(99)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.OutOfRange)
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }

    @Test fun `writeSetDriverTemp with celsius=5 is rejected before calling helper`() = runTest {
        val result = api.writeSetDriverTemp(5)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.OutOfRange)
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }

    // ── Test 3: writeUnlockDoors calls helper with val=1 ─────────────────────

    @Test fun `writeUnlockDoors calls helper with val=1 and returns success`() = runTest {
        val entry = allowlist.find("doors_unlock")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns true
        // No readback for doors_unlock (see WriteAllowlist comment).

        assertTrue(api.writeUnlockDoors().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 1) }
    }

    // ── Test 4: writeLockDoors calls helper with val=2 ────────────────────────

    @Test fun `writeLockDoors calls helper with val=2 and returns success`() = runTest {
        val entry = allowlist.find("doors_lock")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        // No readback for doors_lock (see WriteAllowlist comment).

        assertTrue(api.writeLockDoors().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 2) }
    }

    // ── Test 5: allowlist miss returns failure without crash ──────────────────

    @Test fun `write method with EMPTY allowlist returns failure AllowlistMiss without throwing`() = runTest {
        val emptyApi: VehicleApi = VehicleApiImpl(parsReader, autoservice, helper, WriteAllowlist.EMPTY, writeLogDao, seatStore, windowStore)
        val result = emptyApi.writeAcOn()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.AllowlistMiss)
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }

    // ── Test 6: doors_lock has no readback — a stale/mismatched read (the old
    // false-error case, field report 2026-06-25) must not affect the outcome ──

    @Test fun `writeLockDoors succeeds and never touches helper read (readback removed)`() = runTest {
        val entry = allowlist.find("doors_lock")!!
        assertTrue(entry.readbackFid == null)
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        // Simulates the field bug: dev=1001 read of 1081081864 returns the old
        // state (1) right after writing 2. Stub is unreachable if the fix holds.
        coEvery { helper.read(entry.dev, 1081081864) } returns 1L

        val result = api.writeLockDoors()
        assertTrue(result.isSuccess)
        coVerify(exactly = 0) { helper.read(any(), any()) }
    }

    // ── Window pos: happy path + helper failure ───────────────────────────────

    @Test fun `writeWindowDriver with 50 percent calls helper write with correct fid and returns success`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 50) } returns true
        // window_driver_pos has no readbackFid; an unreadable position means the movement
        // verification has no evidence and leaves the outcome alone (see the wave 3 block below).
        coEvery { autoservice.getIntRaw(any(), any()) } returns null
        assertTrue(api.writeWindowDriver(50).isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 50) }
    }

    // A hung window-position read must never delay the write (P2 audit fix): the readback
    // sample runs on its own scope, started before helper.write, not awaited by doWrite.
    // Real dispatchers (not runTest's virtual scheduler): the readback lives on its own
    // Dispatchers.IO scope, so timing here is real wall-clock, bounded by
    // WINDOW_BEFORE_READ_BUDGET_MS (300 ms) — comfortably under the 1 s outer budget.
    @Test fun `writeWindowDriver does not wait for a hung readback read`() = runBlocking {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 50) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers { awaitCancellation() }

        val result = withTimeout(1_000) { api.writeWindowDriver(50) }

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 50) }
    }

    // Dispatchers.Unconfined instead of the real Dispatchers.IO: the "before" async job then
    // runs to completion before doWrite's await() resumes, deterministically, instead of
    // racing the WINDOW_BEFORE_READ_BUDGET_MS timeout against the real IO scheduler.
    @Test fun `writeWindowDriver reads the before position ahead of the write when the read is fast`() = runBlocking {
        val entry = allowlist.find("window_driver_pos")!!
        val impl = VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, windowStore)
        impl.readbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val calls = mutableListOf<String>()
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers { calls.add("read"); null }
        coEvery { helper.write(entry.dev, entry.writeFid, 50) } coAnswers { calls.add("write"); true }

        val result = impl.writeWindowDriver(50)

        assertTrue(result.isSuccess)
        assertEquals(listOf("read", "write"), calls)
    }

    // ── Wave 3: the pane either moved or the write is reported as failed ──────

    // Unconfined readback scope so the "before" sample is taken deterministically before the
    // write; runTest's virtual clock makes the two 400 ms verification waits free.
    private fun verifyingApi() = VehicleApiImpl(
        parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, windowStore,
    ).also { it.readbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }

    @Test fun `window write that does not move the pane is reported as a failure`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 0) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returns 100

        val result = verifyingApi().writeWindowDriver(0)

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as VehicleWriteError.ReadbackMismatch
        assertTrue(err.message!!, err.message!!.contains("не сдвинулось"))
    }

    @Test fun `window write that starts the pane moving is a success`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 0) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returnsMany listOf(100, 82)

        assertTrue(verifyingApi().writeWindowDriver(0).isSuccess)
    }

    // A read we could not take is not evidence against the write: fail open, never invent a
    // failure out of a dead read channel.
    @Test fun `unreadable position after the write leaves the outcome successful`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 0) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returnsMany listOf(100, null)

        assertTrue(verifyingApi().writeWindowDriver(0).isSuccess)
    }

    @Test fun `pane already at the requested position is not verified at all`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 100) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returns 100

        assertTrue(verifyingApi().writeWindowDriver(100).isSuccess)
        coVerify(exactly = 1) { autoservice.getIntRaw(any(), any()) }
    }

    // The sentinel classes mean "no data", not "position 0".
    @Test fun `sentinel position before the write leaves the outcome successful`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 0) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returns -10011

        assertTrue(verifyingApi().writeWindowDriver(0).isSuccess)
    }

    @Test fun `writeWindowDriver returns failure HelperUnreachable when helper write fails (validated entry)`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 50) } returns false
        val result = api.writeWindowDriver(50)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.HelperUnreachable)
    }

    // ── writeSunroof: enum maps to correct action name ────────────────────────

    @Test fun `writeSunroof TILT calls helper with sunroof_tilt entry fid and val=3`() = runTest {
        val entry = allowlist.find("sunroof_tilt")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 3) } returns true
        assertTrue(api.writeSunroof(SunroofMode.TILT).isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 3) }
    }

    // ── writeSunshade: open vs close ──────────────────────────────────────────

    @Test fun `writeSunshade open=true calls sunshade_open entry with val=1`() = runTest {
        val entry = allowlist.find("sunshade_open")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns true
        assertTrue(api.writeSunshade(open = true).isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 1) }
    }

    @Test fun `writeSunshade open=false calls sunshade_close entry with val=2`() = runTest {
        val entry = allowlist.find("sunshade_close")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        assertTrue(api.writeSunshade(open = false).isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 2) }
    }

    // ── Composite dispatch: rear windows fan out to both rear open fids ───────

    @Test fun `dispatch rear windows open writes both rear open fids and returns success`() = runTest {
        val rl = allowlist.find("window_rear_left_open")!!
        val rr = allowlist.find("window_rear_right_open")!!
        coEvery { helper.write(rl.dev, rl.writeFid, 1) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 1) } returns true
        // Fan-out is what this checks; an unreadable position keeps the movement verdict out of it.
        coEvery { autoservice.getIntRaw(any(), any()) } returns null

        val result = api.dispatch("后排车窗全开")
        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(rl.dev, rl.writeFid, 1) }
        coVerify(exactly = 1) { helper.write(rr.dev, rr.writeFid, 1) }
    }

    @Test fun `dispatch rear windows open returns failure but still attempts both when one fid fails`() = runTest {
        val rl = allowlist.find("window_rear_left_open")!!
        val rr = allowlist.find("window_rear_right_open")!!
        coEvery { helper.write(rl.dev, rl.writeFid, 1) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 1) } returns false // rear-right fails

        val result = api.dispatch("后排车窗全开")
        assertTrue(result.isFailure)
        coVerify(exactly = 1) { helper.write(rl.dev, rl.writeFid, 1) }
        coVerify(exactly = 1) { helper.write(rr.dev, rr.writeFid, 1) }
    }

    // ── Wave 3 follow-up: one verification window for a whole burst ───────────

    private fun rearWindowFids() = Pair(
        allowlist.find("window_rear_left_open")!!, allowlist.find("window_rear_right_open")!!)

    /** Both rear panes answer [before] until the write, then [after] — per pane, in order. */
    private fun stubRearPositions(before: Int, afterLeft: Int, afterRight: Int) {
        val rl = FidAddresses.of("windowRL")
        val rr = FidAddresses.of("windowRR")
        coEvery { autoservice.getIntRaw(rl.device, rl.fid) } returnsMany listOf(before, afterLeft, afterLeft)
        coEvery { autoservice.getIntRaw(rr.device, rr.fid) } returnsMany listOf(before, afterRight, afterRight)
    }

    // The burst must cost ONE verification window, not one per pane: two panes that both move
    // are judged in a single pass, so the whole command stays inside the stagger + one wait.
    @Test fun `a window burst is verified once, not once per pane`() = runTest {
        val (rl, rr) = rearWindowFids()
        coEvery { helper.write(rl.dev, rl.writeFid, 1) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 1) } returns true
        stubRearPositions(before = 0, afterLeft = 25, afterRight = 25)
        val impl = verifyingApi()

        val startedAt = testScheduler.currentTime
        assertTrue(impl.dispatch("后排车窗全开").isSuccess)

        // One stagger between the two writes plus a single 400 ms verification pass.
        assertEquals(550L, testScheduler.currentTime - startedAt)
    }

    // Song L (#97) shape: the burst is accepted, one pane moves, the other does not.
    @Test fun `a burst names the pane that did not move`() = runTest {
        val (rl, rr) = rearWindowFids()
        coEvery { helper.write(rl.dev, rl.writeFid, 1) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 1) } returns true
        stubRearPositions(before = 0, afterLeft = 25, afterRight = 0)
        val impl = verifyingApi()

        val result = impl.dispatch("后排车窗全开")

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()!!.message!!
        assertTrue(message, message.contains("заднее правое окно"))
        assertFalse(message, message.contains("заднее левое"))
    }

    // Two 400 ms passes is the worst case for a burst where nothing moves at all.
    @Test fun `a stuck burst costs at most two verification passes`() = runTest {
        val (rl, rr) = rearWindowFids()
        coEvery { helper.write(rl.dev, rl.writeFid, 1) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 1) } returns true
        stubRearPositions(before = 0, afterLeft = 0, afterRight = 0)
        val impl = verifyingApi()

        val startedAt = testScheduler.currentTime
        assertTrue(impl.dispatch("后排车窗全开").isFailure)

        assertEquals(950L, testScheduler.currentTime - startedAt)
    }

    // "Open" and "close" are their own fids on the car but still name a position, so the
    // commands the driver actually uses are judged too — not just explicit percentages.
    @Test fun `an open command that moves nothing is reported as a failure`() = runTest {
        val entry = allowlist.find("window_driver_open")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returns 0

        val result = verifyingApi().dispatch("主驾打开100")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("окно водителя"))
    }

    // The physical write lands before the verdict is read back, so a caller cancelled inside
    // that ~1 s window must still get the outcome instead of an exception for a command the
    // car already obeyed — the same protection the composite burst has.
    @Test fun `a cancel during the window verdict still returns the outcome`() = runTest {
        val entry = allowlist.find("window_driver_open")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 1) } returns true
        coEvery { autoservice.getIntRaw(any(), any()) } returns 0
        val rows = mutableListOf<VehicleWriteLogEntity>()
        coEvery { writeLogDao.insert(capture(rows)) } returns Unit
        val impl = verifyingApi()

        var outcome: Result<Unit>? = null
        val job = launch { outcome = impl.dispatch("主驾打开100") }
        advanceTimeBy(500)
        job.cancel()
        job.join()

        assertNotNull("dispatch must return a verdict despite the cancel", outcome)
        assertTrue(outcome!!.isFailure)
        assertTrue(rows.any { it.error == "window_noop" })
    }

    @Test fun `dispatch unknown command returns failure AllowlistMiss without helper call`() = runTest {
        val result = api.dispatch("不存在的命令")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.AllowlistMiss)
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }

    // ── Task 7: seat dispatch routes through adaptive channel ─────────────────

    @Test fun `dispatch routes seat command through adaptive channel primary REAL`() = runTest {
        val sw = allowlist.find("driver_seat_vent_switch")!!
        val lv = allowlist.find("driver_seat_vent_level")!!
        coEvery { helper.writeStatus(sw.dev, sw.writeFid, 1) } returns 1
        coEvery { helper.writeStatus(lv.dev, lv.writeFid, 1) } returns 1
        // resolveSeat → DRIVER_VENT level 1; UNKNOWN store → primary
        assertTrue(api.dispatch("主驾座椅通风1档").isSuccess)
        coVerify(exactly = 1) { helper.writeStatus(sw.dev, sw.writeFid, 1) }
    }

    // ── C.5: DAO receives audit entry on write ────────────────────────────────

    @Test fun `writeAcOn persists audit log entries (attempt + outcome)`() = runTest {
        val insertions = mutableListOf<VehicleWriteLogEntity>()
        coEvery { helper.write(1000, 501219364, 1) } returns true
        coEvery { writeLogDao.insert(capture(insertions)) } returns Unit
        api.writeAcOn()
        // Expect 2 rows: attempt (status=-2) + outcome (status=0)
        coVerify(exactly = 2) { writeLogDao.insert(any()) }
        val attempt = insertions.first { it.status == -2 }
        assertEquals("ac_on", attempt.actionName)
        assertEquals(1000, attempt.dev)
        assertEquals(1, attempt.requested)
        assertEquals("attempt", attempt.error)
        val outcome = insertions.first { it.status == 0 }
        assertEquals("ac_on", outcome.actionName)
        assertEquals(1000, outcome.dev)
        assertEquals(1, outcome.requested)
    }
}
