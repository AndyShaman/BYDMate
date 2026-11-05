package com.bydmate.app.split

import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
 * #385: on DiLink 3 and 4 (Android 10) neither our freeform split nor the firmware toggle has
 * ever worked, and the native path answered «Готово» after a broadcast nobody hears. Below
 * Android 12 a start must say the split is unavailable, without touching a single task.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitSessionManagerOldAndroidTest {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    @Test fun `android 10 freeform start is unavailable and touches nothing`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = OldAndroidFakeBackdrop()
        val mgr = SplitSessionManager(
            helper, OldAndroidFakePreferences(native = false), backdrop, backgroundScope,
            tickDelayMs = 60_000,
            platformSdk = 29,
        )

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, mgr.start(pair))
        assertTrue("the hint must say unsupported, not «after a reboot»", mgr.freeformUnsupported())
        verify { helper wasNot Called }
        assertEquals(0, backdrop.showCalls)
    }

    @Test fun `android 10 native mode does not report success`() = runTest {
        val launcher = mockk<NativeSplitLauncher>(relaxed = true)
        coEvery { launcher.launch(any()) } returns true
        val mgr = SplitSessionManager(
            mockk(relaxed = true), OldAndroidFakePreferences(native = true), OldAndroidFakeBackdrop(),
            backgroundScope,
            tickDelayMs = 60_000,
            nativeLauncher = launcher,
            platformSdk = 29,
        )

        assertEquals(SplitStartResult.FREEFORM_UNAVAILABLE, mgr.start(pair))
        coVerify(exactly = 0) { launcher.launch(any()) }
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
}
