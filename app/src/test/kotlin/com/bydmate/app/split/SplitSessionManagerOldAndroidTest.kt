package com.bydmate.app.split

import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class OldAndroidFakePreferences(private val native: Boolean) : SplitPreferences {
    private var saved: SplitPair? = null
    override fun getLastPair(): SplitPair? = saved
    override fun saveLastPair(pair: SplitPair) { saved = pair }
    override fun clearLastPair() { saved = null }
    override fun isFeatureEnabled(): Boolean = true
    override fun setFeatureEnabled(enabled: Boolean) { /* not used by these tests */ }
    override fun isNativeModeEnabled(): Boolean = native
}

private class OldAndroidFakeBackdrop : SplitBackdrop {
    var showCalls = 0
    override suspend fun show(): Boolean { showCalls++; return true }
    override fun hide() { /* not used by these tests */ }
}

/**
 * #385 reversed: the split worked on DiLink 3/4 (Android 10) in 4.0.2, so a start below Android 12
 * is attempted again instead of being refused up front. Freeform mode runs the normal start and
 * only a real FREEFORM_UNAVAILABLE outcome makes the hint say «not supported». Native mode checks
 * the result for up to 3 seconds through the panes' windowing modes: a split (mode 3 or 4) or no
 * way to tell is OK, a wide pane that stays fullscreen is FREEFORM_UNAVAILABLE. Android 12+ is unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitSessionManagerOldAndroidTest {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    private fun task(id: Int, mode: Int) = SplitTaskState(id, mode, 0, 84, 1280, 990)

    private fun HelperClient.stubUnavailableLaunch() {
        coEvery { getTaskState(any()) } returns null
        coEvery {
            launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE
    }

    private fun TestScope.freeformManager(helper: HelperClient, sdk: Int) = SplitSessionManager(
        helper, OldAndroidFakePreferences(native = false), OldAndroidFakeBackdrop(), backgroundScope,
        tickDelayMs = 60_000,
        platformSdk = sdk,
    )

    private class Native(
        val mgr: SplitSessionManager,
        val prefs: OldAndroidFakePreferences,
        val launcher: NativeSplitLauncher,
    )

    private fun TestScope.nativeManager(
        helper: HelperClient,
        sdk: Int = 29,
        launched: Boolean = true,
    ): Native {
        val launcher = mockk<NativeSplitLauncher>(relaxed = true)
        coEvery { launcher.launch(any()) } returns launched
        val prefs = OldAndroidFakePreferences(native = true)
        val mgr = SplitSessionManager(
            helper, prefs, OldAndroidFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            nativeLauncher = launcher,
            platformSdk = sdk,
        )
        return Native(mgr, prefs, launcher)
    }

    private fun HelperClient.stubPanes(wide: SplitTaskState?, narrow: SplitTaskState?) {
        coEvery { getTaskState("pkg.wide") } returns wide
        coEvery { getTaskState("pkg.narrow") } returns narrow
    }

    // ── Freeform mode ─────────────────────────────────────────────────────────

    @Test fun `android 10 freeform start is attempted and ends as it does on android 12`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubUnavailableLaunch()
        val old = freeformManager(helper, 29)

        val oldResult = old.start(pair)

        coVerify(atLeast = 1) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        val newHelper = mockk<HelperClient>(relaxed = true)
        newHelper.stubUnavailableLaunch()
        assertEquals(freeformManager(newHelper, 31).start(pair), oldResult)
    }

    @Test fun `android 10 freeform hint is not marked unsupported before any start`() = runTest {
        val mgr = freeformManager(mockk(relaxed = true), 29)

        assertFalse(mgr.freeformUnsupported())
    }

    @Test fun `android 10 freeform start refused by the firmware marks it unsupported, android 12 does not`() = runTest {
        val oldHelper = mockk<HelperClient>(relaxed = true)
        oldHelper.stubUnavailableLaunch()
        val old = freeformManager(oldHelper, 29)
        val newHelper = mockk<HelperClient>(relaxed = true)
        newHelper.stubUnavailableLaunch()
        val new = freeformManager(newHelper, 31)

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, old.start(pair))
        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, new.start(pair))

        coVerify(atLeast = 1) { oldHelper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertTrue("the hint must say unsupported, not «after a reboot»", old.freeformUnsupported())
        assertFalse("android 12 without a verdict keeps today's behaviour", new.freeformUnsupported())
    }

    @Test fun `android 10 successful freeform start is OK and not marked unsupported`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        coEvery { helper.forceStop(any()) } returns true
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.OK
        coEvery { helper.getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom)
        coEvery { helper.getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
        val mgr = freeformManager(helper, 29)

        assertEquals(SplitStartResult.OK, mgr.start(pair))
        assertFalse(mgr.freeformUnsupported())
    }

    @Test fun `android 12 still attempts the start`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState(any()) } returns null
        coEvery {
            helper.launchFreeform(any(), any(), any(), any(), any(), any(), any())
        } returns FreeformLaunchResult.UNAVAILABLE
        val mgr = SplitSessionManager(
            helper, OldAndroidFakePreferences(native = false), OldAndroidFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            platformSdk = 31,
        )

        mgr.start(pair)
        coVerify(atLeast = 1) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
    }

    // ── Native mode ───────────────────────────────────────────────────────────

    @Test fun `android 10 native start with the wide pane in split mode 3 is OK`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(7, 3), narrow = null)
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
        coVerify(exactly = 1) { n.launcher.launch(any()) }
    }

    @Test fun `android 10 native start with only the narrow pane in split mode 4 is OK`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(7, 1), narrow = task(8, 4))
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
    }

    @Test fun `android 10 native start with both panes fullscreen is unavailable and can be retried`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(7, 1), narrow = task(8, 1))
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, n.mgr.start(pair))
        assertNull(n.prefs.getLastPair())
        assertTrue(n.mgr.freeformUnsupported())
        coVerify(exactly = 1) { n.launcher.launch(any()) }

        n.mgr.start(pair)
        coVerify(exactly = 2) { n.launcher.launch(any()) }
    }

    @Test fun `android 10 native start with no task answers is OK and not marked unsupported`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState(any()) } returns null
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
        assertFalse(n.mgr.freeformUnsupported())
    }

    @Test fun `android 10 native start with a missing wide task is OK`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(-1, 1), narrow = task(8, 1))
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
    }

    @Test fun `android 10 native start with the wide pane in an unknown mode is OK`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(7, 100), narrow = task(8, 1))
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
    }

    @Test fun `android 10 native split that shows up late is still confirmed`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val scheduler = testScheduler
        coEvery { helper.getTaskState("pkg.wide") } answers {
            task(7, if (scheduler.currentTime < 1500) 1 else 3)
        }
        coEvery { helper.getTaskState("pkg.narrow") } returns null
        val n = nativeManager(helper)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
    }

    @Test fun `android 10 native launch failure is LAUNCH_FAILED and reads no task`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val n = nativeManager(helper, launched = false)

        assertEquals(SplitStartResult.LAUNCH_FAILED, n.mgr.start(pair))
        assertNull(n.prefs.getLastPair())
        assertFalse(n.mgr.freeformUnsupported())
        coVerify(exactly = 0) { helper.getTaskState(any()) }
    }

    @Test fun `android 10 native start succeeds and clears the unsupported mark after an earlier refusal`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(7, 1), narrow = task(8, 1))
        val n = nativeManager(helper)
        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, n.mgr.start(pair))

        helper.stubPanes(wide = task(7, 3), narrow = task(8, 1))

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertFalse(n.mgr.freeformUnsupported())
    }

    @Test fun `android 12 native start does not read task states`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        helper.stubPanes(wide = task(7, 1), narrow = task(8, 1))
        val n = nativeManager(helper, sdk = 31)

        assertEquals(SplitStartResult.OK, n.mgr.start(pair))
        assertEquals(pair, n.prefs.getLastPair())
        coVerify(exactly = 0) { helper.getTaskState(any()) }
    }
}
