package com.bydmate.app.split

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.RaiseOutcome
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class VerdictFakePreferences : SplitPreferences {
    private var saved: SplitPair? = null
    private var enabled = true
    override fun getLastPair(): SplitPair? = saved
    override fun saveLastPair(pair: SplitPair) { saved = pair }
    override fun clearLastPair() { saved = null }
    override fun isFeatureEnabled(): Boolean = enabled
    override fun setFeatureEnabled(enabled: Boolean) { this.enabled = enabled }
}

private class VerdictFakeBackdrop : SplitBackdrop {
    var showCalls = 0
    override suspend fun show(): Boolean { showCalls++; return true }
    override fun hide() {}
}

private class RecordingJournal : SplitJournal {
    val lines = mutableListOf<String>()
    override fun append(payload: String) { lines += payload }
    override fun read(): List<String> = lines
}

/**
 * Once the firmware is proven to ignore the freeform flag (#139), every further widget tap must
 * be a no-op: the old behaviour force-stopped and relaunched the pane apps on each retry, so a
 * user who kept tapping kept killing their navigator for nothing.
 *
 * Two cases here cover the other end of the same chain (#147): a start re-asserts the freeform flag
 * itself, so a firmware never reaches that verdict just because the toggle-time write was lost.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitSessionManagerVerdictTest {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    /** Verdict latched through its own API, so the test depends on the real latch signature. */
    private fun latchedVerdict(): SplitFreeformVerdict {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("split_verdict_ssm_test", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putBoolean(ClusterProjectionManager.KEY_SPLIT_FREEFORM_REBOOT_PENDING, true).commit()
        var boot = 3
        val verdict = SplitFreeformVerdict(prefs, bootCount = { boot }, fingerprint = { "fp" })
        verdict.noteUnavailable()
        boot = 4
        verdict.noteUnavailable()
        return verdict
    }

    @Test fun `a latched verdict short-circuits start without touching any task`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = VerdictFakeBackdrop()
        val journal = RecordingJournal()
        val verdict = latchedVerdict()
        assertTrue("precondition: the verdict is latched", verdict.unsupported())
        // Spend this boot's self-healing retry, so the start under test meets a closed gate.
        assertFalse(verdict.suppressStart())

        val mgr = SplitSessionManager(
            helper, VerdictFakePreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
            verdict = verdict,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertTrue(mgr.freeformUnsupported())
        verify { helper wasNot Called }
        assertEquals("the backdrop must not even be shown", 0, backdrop.showCalls)
        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.contains("suppressed: freeform unsupported") },
        )
    }

    /**
     * The escape hatch: a latch that has not spent this boot's retry must not stop the start, or
     * a verdict set by mistake could never be disproved.
     */
    @Test fun `a latched verdict whose retry is unspent still attempts the start`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState(any()) } returns null
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE
        val backdrop = VerdictFakeBackdrop()
        val journal = RecordingJournal()
        val verdict = latchedVerdict()
        assertTrue("precondition: the verdict is latched", verdict.unsupported())

        val mgr = SplitSessionManager(
            helper, VerdictFakePreferences(), backdrop, backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
            verdict = verdict,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, result)
        coVerify { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals("the backdrop ran, so this was a real attempt", 1, backdrop.showCalls)
        assertFalse(
            "journal was ${journal.lines}",
            journal.lines.any { it.contains("suppressed: freeform unsupported") },
        )
        assertTrue("the retry is spent now", verdict.suppressStart())
    }

    /**
     * Firmware that ignores the freeform flag: the pane app's task is alive and fullscreen on the
     * split display, the gentle mode flip never lands, and every launch comes back UNAVAILABLE.
     * [calls] records the helper interactions in order.
     */
    private fun freeformDeafHelper(calls: MutableList<String>): HelperClient {
        val helper = mockk<HelperClient>(relaxed = true)
        val fullscreen = SplitTaskState(
            taskId = 11, windowingMode = 1, left = 0, top = 0, right = 1920, bottom = 1200,
            displayId = 0,
        )
        coEvery { helper.getTaskState(any()) } answers { calls += "getTaskState"; fullscreen }
        coEvery { helper.setTaskWindowingMode(any(), any(), any()) } answers { calls += "flip"; false }
        coEvery { helper.forceStop(any()) } answers { calls += "forceStop"; true }
        coEvery { helper.raiseFreeformTaskDetailed(any(), any(), any()) } answers {
            calls += "raise"; RaiseOutcome.REFUSED
        }
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } answers { calls += "launch"; FreeformLaunchResult.UNAVAILABLE }
        return helper
    }

    /**
     * The once-per-boot retry must be non-destructive end to end. Under a latched verdict the flip
     * provably cannot land, so neither the force-stop that normally follows it nor the relaunch
     * fallback behind a refused raise buys anything — both would only cost the user their navigator
     * guidance on every boot, to relaunch the app into the same wall.
     *
     * The retry still reaches the firmware (flip + raise): a raise that lands is what disproves a
     * verdict latched by mistake.
     */
    @Test fun `a latched retry never force-stops the pane app`() = runTest {
        val calls = mutableListOf<String>()
        val helper = freeformDeafHelper(calls)
        val journal = RecordingJournal()
        val verdict = latchedVerdict()
        assertTrue("precondition: the verdict is latched", verdict.unsupported())

        val mgr = SplitSessionManager(
            helper, VerdictFakePreferences(), VerdictFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
            verdict = verdict,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, result)
        assertTrue("the flip must have been tried, calls were $calls", calls.contains("flip"))
        assertTrue("the raise must have been tried, calls were $calls", calls.contains("raise"))
        assertFalse("nothing may be killed under a latched verdict, calls were $calls", calls.contains("forceStop"))
        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.contains("relaunch pkg.wide suppressed: freeform unsupported") },
        )
    }

    /** Without a latch the flip fallback keeps its historic behaviour — a reboot may still fix it. */
    @Test fun `an unlatched start still force-stops after a flip that did not land`() = runTest {
        val calls = mutableListOf<String>()
        val helper = freeformDeafHelper(calls)

        val mgr = SplitSessionManager(
            helper, VerdictFakePreferences(), VerdictFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = RecordingJournal(),
        )

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, mgr.start(pair))
        val firstForceStop = calls.indexOf("forceStop")
        assertTrue(
            "the fresh-task fallback must still run, calls were $calls",
            firstForceStop in 0 until calls.indexOf("raise"),
        )
    }

    /**
     * #147: the freeform flag is otherwise only written when a toggle is flipped, and that write is
     * lost without a trace when the daemon is unreachable at that moment. The flag then stays 0
     * through every reboot and the user walks straight into a verdict that blames the firmware. The
     * start path is where the write belongs — it runs before the probe that would report
     * UNAVAILABLE.
     */
    @Test fun `a start re-asserts the freeform flag before probing`() = runTest {
        val calls = mutableListOf<String>()
        val helper = freeformDeafHelper(calls)
        coEvery { helper.putGlobalSetting("enable_freeform_support", 1) } answers {
            calls += "putGlobal"; true
        }

        val mgr = SplitSessionManager(
            helper, VerdictFakePreferences(), VerdictFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = RecordingJournal(),
        )

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, mgr.start(pair))

        assertEquals(
            "the write must come before any probe, calls were $calls",
            0, calls.indexOf("putGlobal"),
        )
        coVerify(exactly = 1) { helper.putGlobalSetting("enable_freeform_support", 1) }
    }

    /**
     * The feature flag start() checked can be stale by the time the write happens: the implicit
     * teardown in between suspends on the outgoing session's task ops. A toggle-off landing in that
     * window has already written the flag back to 0 through the settings path, so re-asserting 1
     * here would leave system-wide freeform on against the user's last word.
     */
    @Test fun `a start that loses the feature during teardown neither writes the flag nor launches`() = runTest {
        val (wideBounds, narrowBounds) = boundsFor(SplitSide.RIGHT)
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.forceStop(any()) } returns true
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.OK
        coEvery { helper.getTaskState("pkg.wide") } returns SplitTaskState(
            taskId = 10, windowingMode = 5,
            left = wideBounds.left, top = wideBounds.top, right = wideBounds.right, bottom = wideBounds.bottom,
            displayId = 0,
        )
        coEvery { helper.getTaskState("pkg.narrow") } returns SplitTaskState(
            taskId = 11, windowingMode = 5,
            left = narrowBounds.left, top = narrowBounds.top, right = narrowBounds.right, bottom = narrowBounds.bottom,
            displayId = 0,
        )
        val prefs = VerdictFakePreferences()
        val backdrop = VerdictFakeBackdrop()
        val journal = RecordingJournal()

        val mgr = SplitSessionManager(
            helper, prefs, backdrop, backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
        )

        assertEquals(SplitStartResult.OK, mgr.start(pair))

        // A second start (different pair, so the double-tap guard is out of the picture) parks in
        // the implicit teardown of the session above.
        //
        // runCurrent(), NOT advanceUntilIdle(): the clock must stand still. Advancing it lets the
        // watchdog of the first session tick, take the mutex and eat this gate, so the start under
        // test only reaches start() after the toggle-off and bails at its top gate — the whole
        // point of the test (the re-read inside startLocked) then never runs.
        val gate = CompletableDeferred<Unit>()
        coEvery { helper.setTaskWindowingMode(any(), any(), any()) } coAnswers { gate.await(); true }
        val second = async { mgr.start(SplitPair("pkg.other", "pkg.wide", SplitSide.RIGHT)) }
        runCurrent()

        // Meanwhile the user turns split off: the settings path writes the flag back to 0.
        prefs.setFeatureEnabled(false)
        gate.complete(Unit)

        assertEquals(SplitStartResult.DISABLED, second.await())
        assertTrue(
            "the start must have passed its top gate and torn the old session down, " +
                "or it never reached the re-read under test; journal was ${journal.lines}",
            journal.lines.any { it.contains("end EXIT") },
        )
        coVerify(exactly = 1) { helper.putGlobalSetting("enable_freeform_support", 1) }
        assertEquals("the aborted start must not show a backdrop of its own", 1, backdrop.showCalls)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.contains("start aborted: feature disabled during teardown") },
        )
    }

    /** The write is best-effort: whatever it does, the attempt itself must still reach the firmware. */
    @Test fun `a failed flag write does not stop the start`() = runTest {
        val calls = mutableListOf<String>()
        val helper = freeformDeafHelper(calls)
        coEvery { helper.putGlobalSetting(any(), any()) } throws RuntimeException("daemon gone")
        val journal = RecordingJournal()

        val mgr = SplitSessionManager(
            helper, VerdictFakePreferences(), VerdictFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
        )

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, mgr.start(pair))

        assertTrue("the probe must still have run, calls were $calls", calls.contains("launch"))
        assertTrue(
            "the lost write is the whole diagnosis, journal was ${journal.lines}",
            journal.lines.any { it.contains("freeform flag write threw") },
        )
    }

    @Test fun `without a verdict the manager behaves as before`() = runTest {
        val mgr = SplitSessionManager(
            mockk(relaxed = true), VerdictFakePreferences(), VerdictFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
        )
        assertFalse(mgr.freeformUnsupported())
    }
}
