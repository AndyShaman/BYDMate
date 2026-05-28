package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the fail-soft Result<Unit> contract introduced in Task C.6.
 * Covers AllowlistMiss, HelperUnreachable (IOException), Sentinel, and
 * Unsupported (non-validated helper-false) paths.
 */
class VehicleApiFailSoftTest {

    private val parsReader: ParsReader = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val helper: HelperClient = mockk()
    private val dao: VehicleWriteLogDao = mockk(relaxed = true)

    private val allowlist = WriteAllowlist(
        WriteAllowlist.LIVE_VALIDATED.associateBy { it.actionName.lowercase() }
    )

    private val api: VehicleApiImpl = VehicleApiImpl(parsReader, autoservice, helper, allowlist, dao)

    // ── Test 1: allowlist miss returns Result failure AllowlistMiss ───────────

    @Test fun `allowlist miss returns Result failure AllowlistMiss`() = runTest {
        val result = api.dispatch("nonsense_action_42")
        assertTrue(result.isFailure)
        // CommandTranslator has no "nonsense_action_42" → no_translator_mapping path
        assertTrue(result.exceptionOrNull() is VehicleWriteError.AllowlistMiss)
        coVerify { dao.insert(any()) }
    }

    // ── Test 2: helper IOException wraps into HelperUnreachable ──────────────

    @Test fun `helper IOException wraps into HelperUnreachable`() = runTest {
        coEvery { helper.write(any(), any(), any()) } throws java.io.IOException("daemon dead")

        val result = api.writeAcOn()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.HelperUnreachable)
    }

    // ── Test 3: readback sentinel -10011 returns Result failure Sentinel ──────

    @Test fun `readback sentinel returns Result failure Sentinel`() = runTest {
        // doors_lock has readbackFid; -10011 triggers Sentinel error
        val entry = allowlist.find("doors_lock")!!
        coEvery { helper.write(entry.dev, entry.writeFid, 2) } returns true
        coEvery { helper.read(entry.dev, entry.readbackFid!!) } returns -10011L

        val result = api.writeLockDoors()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.Sentinel)
    }

    // ── Test 4: non-validated entry helper-false returns Unsupported ──────────
    //
    // dispatch() goes through CommandTranslator which has no Chinese mapping for
    // "nonvalidated_action_test". Using doWrite() directly (internal visibility)
    // to bypass the translator and exercise the Unsupported path cleanly.

    @Test fun `non-validated entry helper-false returns Unsupported`() = runTest {
        val customAllowlist = WriteAllowlist(mapOf(
            "nonvalidated_action_test" to WriteEntry(
                actionName = "nonvalidated_action_test",
                dev = 1001,
                writeFid = 999999999,
                readbackFid = null,
                valueMin = 1,
                valueMax = 1,
                category = "other",
                validated = false,
                source = "competitor-v80",
            )
        ))
        val customApi = VehicleApiImpl(parsReader, autoservice, helper, customAllowlist, dao)

        coEvery { helper.write(1001, 999999999, 1) } returns false

        val result = customApi.doWrite("nonvalidated_action_test", 1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.Unsupported)
    }

    // ── Test 5: helper RuntimeException also wraps into HelperUnreachable ─────

    @Test fun `helper RuntimeException wraps into HelperUnreachable`() = runTest {
        coEvery { helper.write(any(), any(), any()) } throws RuntimeException("unexpected")

        val result = api.writeAcOff()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.HelperUnreachable)
    }
}
