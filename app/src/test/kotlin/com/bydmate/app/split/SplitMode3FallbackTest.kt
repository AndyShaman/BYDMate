package com.bydmate.app.split

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

class SplitMode3FallbackGateTest {

    @Test fun `applies only when sdk is 29 or below and native split was not confirmed`() {
        assertTrue(SplitMode3Fallback.applies(29, false))
        assertTrue(SplitMode3Fallback.applies(28, false))
        assertFalse(SplitMode3Fallback.applies(29, true))
        assertFalse(SplitMode3Fallback.applies(30, false))
        assertFalse(SplitMode3Fallback.applies(31, false))
        assertFalse(SplitMode3Fallback.applies(32, false))
    }
}

private class Mode3FakePreferences : SplitPreferences {
    private var saved: SplitPair? = null
    override fun getLastPair(): SplitPair? = saved
    override fun saveLastPair(pair: SplitPair) { saved = pair }
    override fun clearLastPair() { saved = null }
    override fun isFeatureEnabled(): Boolean = true
    override fun setFeatureEnabled(enabled: Boolean) { /* not used by these tests */ }
    override fun isNativeModeEnabled(): Boolean = true
}

private class Mode3FakeBackdrop : SplitBackdrop {
    override suspend fun show(): Boolean = true
    override fun hide() { /* not used by these tests */ }
}

/**
 * #385: when the BYD toggle broadcast leaves the wide task fullscreen on Android 10 (SDK <= 29),
 * SplitSessionManager falls back to docking the wide task into Android's split-screen-primary
 * (windowing mode 3) via HelperClient.setTaskSplitPrimary and launching the narrow app.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitSessionManagerMode3FallbackTest {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    private fun task(id: Int, mode: Int) = SplitTaskState(id, mode, 0, 84, 1280, 990)

    private data class NativeHarness(
        val mgr: SplitSessionManager,
        val prefs: Mode3FakePreferences,
        val launcher: NativeSplitLauncher,
    )

    private fun TestScope.nativeManager(
        helper: HelperClient,
        sdk: Int = 29,
    ): NativeHarness {
        val launcher = mockk<NativeSplitLauncher>(relaxed = true)
        coEvery { launcher.launch(any()) } returns true
        val prefs = Mode3FakePreferences()
        val mgr = SplitSessionManager(
            helper, prefs, Mode3FakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            nativeLauncher = launcher,
            platformSdk = sdk,
        )
        return NativeHarness(mgr, prefs, launcher)
    }

    @Test fun `sdk 29 unconfirmed toggle docks wide task in mode 3 and succeeds when recheck sees mode 3 and narrow`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        var docked = false
        coEvery { helper.getTaskState("pkg.wide") } answers {
            if (docked) task(7, 3) else task(7, 1)
        }
        coEvery { helper.getTaskState("pkg.narrow") } answers {
            if (docked) task(8, 4) else task(-1, 0)
        }
        coEvery { helper.setTaskSplitPrimary(7) } answers {
            docked = true
            true
        }
        coEvery { helper.launchApp("pkg.narrow") } returns true
        val h = nativeManager(helper, sdk = 29)

        assertEquals(SplitStartResult.OK, h.mgr.start(pair))
        assertFalse(h.mgr.freeformUnsupported())
        assertEquals(pair, h.prefs.getLastPair())
        coVerify(exactly = 1) { helper.setTaskSplitPrimary(7) }
    }

    @Test fun `sdk 29 unconfirmed toggle with setTaskSplitPrimary returning false stays unavailable and does not launch narrow`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState("pkg.wide") } returns task(7, 1)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(-1, 0)
        coEvery { helper.setTaskSplitPrimary(any()) } returns false
        coEvery { helper.launchApp(any()) } returns true
        val h = nativeManager(helper, sdk = 29)

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, h.mgr.start(pair))
        assertTrue(h.mgr.freeformUnsupported())
        assertNull(h.prefs.getLastPair())
        coVerify(exactly = 1) { helper.setTaskSplitPrimary(7) }
        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 1) { h.launcher.launch(any()) }
    }

    @Test fun `sdk 29 confirmed native toggle never calls setTaskSplitPrimary`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState("pkg.wide") } returns task(7, 3)
        coEvery { helper.getTaskState("pkg.narrow") } returns null
        val h = nativeManager(helper, sdk = 29)

        assertEquals(SplitStartResult.OK, h.mgr.start(pair))
        assertFalse(h.mgr.freeformUnsupported())
        assertEquals(pair, h.prefs.getLastPair())
        coVerify(exactly = 0) { helper.setTaskSplitPrimary(any()) }
    }

    @Test fun `sdk 32 unconfirmed native toggle never calls setTaskSplitPrimary and keeps today's result`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState("pkg.wide") } returns task(7, 1)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(8, 1)
        val h = nativeManager(helper, sdk = 32)

        assertEquals(SplitStartResult.OK, h.mgr.start(pair))
        assertFalse(h.mgr.freeformUnsupported())
        assertEquals(pair, h.prefs.getLastPair())
        coVerify(exactly = 0) { helper.setTaskSplitPrimary(any()) }
    }
}
