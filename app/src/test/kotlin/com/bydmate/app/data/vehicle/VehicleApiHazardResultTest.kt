package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Honest result of the hazard actions (#412): Tang L answers "accepted" to the hazard write and
 * never lights the hazard. After an accepted write the turn-signal mask (fid 950009900, 6 = hazard
 * on) is watched for about 1.5 s. hazard_on fails only when the mask is readable and stays 1;
 * hazard_off fails only when it stays 6. Unreadable or unexpected masks keep today's success, so a
 * car whose monitor is blind does not regress.
 *
 * The mask is stubbed on every read path the implementation may use (helper.read and the
 * autoservice client), as a function of virtual time since the write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VehicleApiHazardResultTest {

    private companion object {
        const val DEV = 1004
        const val WRITE_FID = 871366669
        const val MASK_FID = 950009900
        const val SENTINEL = -10011L
    }

    private val helper: HelperClient = mockk()
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
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

    /** [mask] is the raw answer of the mask fid at a virtual time (ms since the test began). */
    private fun TestScope.api(accepted: Boolean = true, mask: (Long) -> Long?): VehicleApiImpl {
        coEvery { helper.write(DEV, WRITE_FID, any()) } returns accepted
        coEvery { helper.writeStatus(DEV, WRITE_FID, any(), any()) } returns if (accepted) 1 else -1
        // The write-only fid itself always reads -10011 (late trace line).
        coEvery { helper.read(DEV, WRITE_FID) } returns SENTINEL
        coEvery { helper.read(DEV, MASK_FID) } coAnswers { mask(testScheduler.currentTime) }
        coEvery { autoservice.getIntRaw(DEV, MASK_FID) } coAnswers { mask(testScheduler.currentTime)?.toInt() }
        coEvery { autoservice.getInt(DEV, MASK_FID) } coAnswers {
            mask(testScheduler.currentTime)?.takeIf { it in 0L..1000L }?.toInt()
        }
        return VehicleApiImpl(autoservice, helper, allowlist, mockk<VehicleWriteLogDao>(relaxed = true), seatStore, windowStore)
            .also { it.readbackScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler)) }
    }

    private fun assertNotDone(result: Result<Unit>) {
        // ReadbackMismatch is the error that maps to the string auto_err_not_done.
        assertTrue("expected failure, got $result", result.isFailure)
        val err = result.exceptionOrNull()
        assertTrue("expected ReadbackMismatch, got $err", err is VehicleWriteError.ReadbackMismatch)
    }

    @Test fun `A1 hazard_on succeeds when the mask becomes 6`() = runTest {
        val api = api { t -> if (t < 300) 1L else 6L }
        assertTrue(api.doWrite("hazard_on", 1).isSuccess)
    }

    @Test fun `A2 hazard_on fails with not-done when the mask stays 1 for the whole window`() = runTest {
        val api = api { 1L }
        val result = api.doWrite("hazard_on", 1)
        assertNotDone(result)
        // The watch is bounded: about 1.5 s of virtual time, not a hang.
        assertTrue("watched for ${testScheduler.currentTime} ms", testScheduler.currentTime in 1_000L..2_000L)
    }

    @Test fun `A3 hazard_on succeeds when the mask is unreadable`() = runTest {
        val unreadable: List<(Long) -> Long?> = listOf({ _ -> null }, { _ -> SENTINEL }, { _ -> 65535L })
        for (mask in unreadable) {
            assertTrue(api(mask = mask).doWrite("hazard_on", 1).isSuccess)
        }
    }

    @Test fun `A3 hazard_on succeeds when the mask is outside 1 2 4 6`() = runTest {
        for (value in listOf(0L, 3L, 5L, 7L)) {
            assertTrue("mask=$value", api { value }.doWrite("hazard_on", 1).isSuccess)
        }
    }

    @Test fun `A4 hazard_off fails with not-done when the mask stays 6 for the whole window`() = runTest {
        val api = api { 6L }
        assertNotDone(api.doWrite("hazard_off", 0))
    }

    @Test fun `A4 hazard_off succeeds when the mask drops from 6`() = runTest {
        val api = api { t -> if (t < 300) 6L else 1L }
        assertTrue(api.doWrite("hazard_off", 0).isSuccess)
    }

    @Test fun `A4 hazard_off succeeds when the mask stays 1 or is unreadable`() = runTest {
        val masks: List<(Long) -> Long?> = listOf({ _ -> 1L }, { _ -> null }, { _ -> SENTINEL })
        for (mask in masks) {
            assertTrue(api(mask = mask).doWrite("hazard_off", 0).isSuccess)
        }
    }

    @Test fun `A5 a write the car rejects keeps today's result`() = runTest {
        for ((action, value) in listOf("hazard_on" to 1, "hazard_off" to 0)) {
            val result = api(accepted = false) { 1L }.doWrite(action, value)
            assertTrue(result.isFailure)
            val err = result.exceptionOrNull()
            assertTrue("got $err", err is VehicleWriteError.HelperUnreachable)
            assertEquals(action, (err as VehicleWriteError).action)
        }
    }
}
