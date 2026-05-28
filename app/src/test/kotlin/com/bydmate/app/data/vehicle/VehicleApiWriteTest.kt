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

    @Test fun `writeAcOn calls helper write with dev=1000 fid=501219364 val=2 and returns success`() = runTest {
        val entry = allowlist.find("ac_on")!!
        // ac_on has no readbackFid — no helper.read call needed
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true

        assertTrue(api.writeAcOn().isSuccess)
        coVerify(exactly = 1) { helper.write(entry.dev, entry.writeFid, 2) }
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

    // ── C.5: DAO receives audit entry on write ────────────────────────────────

    @Test fun `writeAcOn persists audit log entry`() = runTest {
        val captured = slot<VehicleWriteLogEntity>()
        coEvery { helper.write(1000, 501219364, 2) } returns true
        coEvery { writeLogDao.insert(capture(captured)) } returns Unit
        api.writeAcOn()
        coVerify(exactly = 1) { writeLogDao.insert(any()) }
        assertEquals("ac_on", captured.captured.actionName)
        assertEquals(1000, captured.captured.dev)
        assertEquals(2, captured.captured.requested)
        assertEquals(0, captured.captured.status)
    }
}
