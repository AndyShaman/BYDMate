package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
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
        WriteAllowlist.LIVE_VALIDATED.associateBy { it.actionName.lowercase() }
    )

    private val api: VehicleApi = VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao)

    // ── Test 1: writeAcOn happy path ──────────────────────────────────────────

    @Test fun `writeAcOn calls helper write with dev=1000 fid=501219352 val=0 and returns success`() = runTest {
        val entry = allowlist.find("ac_on")!!
        // ac_on = competitor ac_ctrl_mode (501219352) set to 0 (AUTO); no readbackFid
        coEvery { helper.write(entry.dev, entry.writeFid, 0) } returns true

        assertTrue(api.writeAcOn().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 0) }
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
        coEvery { helper.read(entry.dev, entry.readbackFid!!) } returns 1L

        assertTrue(api.writeUnlockDoors().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 1) }
    }

    // ── Test 4: writeLockDoors calls helper with val=2 ────────────────────────

    @Test fun `writeLockDoors calls helper with val=2 and returns success`() = runTest {
        val entry = allowlist.find("doors_lock")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        coEvery { helper.read(entry.dev, entry.readbackFid!!) } returns 2L

        assertTrue(api.writeLockDoors().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 2) }
    }

    // ── Test 5: allowlist miss returns failure without crash ──────────────────

    @Test fun `write method with EMPTY allowlist returns failure AllowlistMiss without throwing`() = runTest {
        val emptyApi: VehicleApi = VehicleApiImpl(parsReader, autoservice, helper, WriteAllowlist.EMPTY, writeLogDao)
        val result = emptyApi.writeAcOn()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.AllowlistMiss)
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }

    // ── Test 6: readback mismatch returns failure ─────────────────────────────

    @Test fun `writeLockDoors returns failure ReadbackMismatch when readback value does not match`() = runTest {
        val entry = allowlist.find("doors_lock")!!
        // Write succeeds but readback returns wrong value (1 instead of 2)
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        coEvery { helper.read(entry.dev, entry.readbackFid!!) } returns 1L

        val result = api.writeLockDoors()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
    }

    // ── Bonus: readback sentinel -10011 returns failure Sentinel ─────────────

    @Test fun `writeLockDoors returns failure Sentinel when readback returns -10011`() = runTest {
        val entry = allowlist.find("doors_lock")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        coEvery { helper.read(entry.dev, entry.readbackFid!!) } returns -10011L

        val result = api.writeLockDoors()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.Sentinel)
    }

    // ── Window pos: happy path + helper failure ───────────────────────────────

    @Test fun `writeWindowDriver with 50 percent calls helper write with correct fid and returns success`() = runTest {
        val entry = allowlist.find("window_driver_pos")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 50) } returns true
        // window_driver_pos has no readbackFid
        assertTrue(api.writeWindowDriver(50).isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 50) }
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

    // ── Composite dispatch: rear windows fan out to both rear % fids ──────────

    @Test fun `dispatch rear windows open writes both rear pos fids and returns success`() = runTest {
        val rl = allowlist.find("window_rear_left_pos")!!
        val rr = allowlist.find("window_rear_right_pos")!!
        coEvery { helper.write(rl.dev, rl.writeFid, 100) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 100) } returns true

        val result = api.dispatch("后排车窗全开")
        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(rl.dev, rl.writeFid, 100) }
        coVerify(exactly = 1) { helper.write(rr.dev, rr.writeFid, 100) }
    }

    @Test fun `dispatch rear windows open returns failure but still attempts both when one fid fails`() = runTest {
        val rl = allowlist.find("window_rear_left_pos")!!
        val rr = allowlist.find("window_rear_right_pos")!!
        coEvery { helper.write(rl.dev, rl.writeFid, 100) } returns true
        coEvery { helper.write(rr.dev, rr.writeFid, 100) } returns false // rear-right fails

        val result = api.dispatch("后排车窗全开")
        assertTrue(result.isFailure)
        coVerify(exactly = 1) { helper.write(rl.dev, rl.writeFid, 100) }
        coVerify(exactly = 1) { helper.write(rr.dev, rr.writeFid, 100) }
    }

    @Test fun `dispatch unknown command returns failure AllowlistMiss without helper call`() = runTest {
        val result = api.dispatch("不存在的命令")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.AllowlistMiss)
        coVerify(exactly = 0) { helper.write(any(), any(), any()) }
    }

    // ── C.5: DAO receives audit entry on write ────────────────────────────────

    @Test fun `writeAcOn persists audit log entries (attempt + outcome)`() = runTest {
        val insertions = mutableListOf<VehicleWriteLogEntity>()
        coEvery { helper.write(1000, 501219352, 0) } returns true
        coEvery { writeLogDao.insert(capture(insertions)) } returns Unit
        api.writeAcOn()
        // Expect 2 rows: attempt (status=-2) + outcome (status=0)
        coVerify(exactly = 2) { writeLogDao.insert(any()) }
        val attempt = insertions.first { it.status == -2 }
        assertEquals("ac_on", attempt.actionName)
        assertEquals(1000, attempt.dev)
        assertEquals(0, attempt.requested)
        assertEquals("attempt", attempt.error)
        val outcome = insertions.first { it.status == 0 }
        assertEquals("ac_on", outcome.actionName)
        assertEquals(1000, outcome.dev)
        assertEquals(0, outcome.requested)
    }
}
