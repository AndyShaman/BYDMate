package com.bydmate.app.split

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import com.bydmate.app.data.vehicle.HelperClient
import io.mockk.Called
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class NativeRecordingJournal : SplitJournal {
    val lines = mutableListOf<String>()
    override fun append(payload: String) { lines += payload }
    override fun read(): List<String> = lines
}

/**
 * The native path is what a #139 car (freeform gated by the firmware) falls back to, so the two
 * mechanisms must be told apart from signals that actually differ between DiLink 5.0 and 5.1 —
 * and a failure must be visible in the journal, the only diagnostic channel on someone else's car.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class NativeSplitLauncherTest {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    private val pm = mockk<PackageManager>(relaxed = true)
    private val context = mockk<Context>(relaxed = true)
    private val journal = NativeRecordingJournal()
    private val started = mutableListOf<Intent>()
    private val broadcast = slot<Intent>()

    private fun launcher(props: Map<String, String> = emptyMap()) = NativeSplitLauncher(
        context = context,
        journal = journal,
        systemProperty = { props[it] },
    ).also {
        every { context.packageManager } returns pm
        every { context.startActivity(any()) } answers { started += firstArg<Intent>(); Unit }
        every { context.sendBroadcast(capture(broadcast)) } answers { }
    }

    private fun withLaunchIntents() {
        every { pm.getLaunchIntentForPackage("pkg.wide") } returns Intent("WIDE")
        every { pm.getLaunchIntentForPackage("pkg.narrow") } returns Intent("NARROW")
    }

    /** No BYD split category declared anywhere — the DiLink 5.0 answer (live probe 2026-07-27). */
    private fun noCategoryFilters() {
        every { pm.queryIntentActivities(any(), any<Int>()) } returns emptyList()
    }

    private fun withCategoryFilters() {
        every { pm.queryIntentActivities(any(), any<Int>()) } returns listOf(ResolveInfo())
    }

    @Test fun `firmware thirds property selects the THIRDS path`() = runTest {
        withLaunchIntents()
        noCategoryFilters()

        val ok = launcher(mapOf("ro.byd.ui.splitscreen" to "1")).launch(pair)

        assertTrue(ok)
        assertEquals("wide first, then narrow", listOf("WIDE", "NARROW"), started.map { it.action })
        val wide = started[0]
        val narrow = started[1]
        assertTrue(wide.categories.contains(NativeSplitLauncher.CATEGORY_TWO_THIRDS))
        assertTrue(narrow.categories.contains(NativeSplitLauncher.CATEGORY_ONE_THIRD))
        assertEquals(NativeSplitLauncher.THIRDS_FLAGS, wide.flags)
        assertEquals(NativeSplitLauncher.THIRDS_FLAGS, narrow.flags)
        verify(exactly = 0) { context.sendBroadcast(any()) }
        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.startsWith("native split: mode=THIRDS wide=pkg.wide narrow=pkg.narrow") },
        )
    }

    /** The second signal: an activity on the device declares the category in its manifest. */
    @Test fun `a declared split category also selects the THIRDS path`() = runTest {
        withLaunchIntents()
        withCategoryFilters()

        assertTrue(launcher().launch(pair))

        assertEquals(listOf("WIDE", "NARROW"), started.map { it.action })
        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.contains("mode=THIRDS") && it.contains("filters=1") },
        )
    }

    /**
     * A negative detection has to say WHICH signal was negative and how: a property reading 0, a
     * property that does not exist and a PackageManager that threw all pick TOGGLE_50_50, and only
     * the journal can tell the three apart on a car we cannot reach.
     */
    @Test fun `the journal carries the raw value of every detection signal`() = runTest {
        withLaunchIntents()
        every { pm.queryIntentActivities(any(), any<Int>()) } throws SecurityException("no query")

        assertTrue(launcher(mapOf("ro.byd.ui.splitscreen" to "0")).launch(pair))

        val line = journal.lines.first { it.contains("mode=") }
        assertTrue("journal line was '$line'", line.contains("prop[ro.byd.ui.splitscreen]=0"))
        assertTrue(
            "journal line was '$line'",
            line.contains("prop[ro.build.baios.supportsplitscreen]=unset"),
        )
        assertTrue("journal line was '$line'", line.contains("filters=err(SecurityException)"))
    }

    @Test fun `without any thirds signal the toggle path runs and never launches the narrow app`() =
        runTest {
            withLaunchIntents()
            noCategoryFilters()

            assertTrue(launcher(mapOf("ro.byd.ui.splitscreen" to "0")).launch(pair))

            assertEquals("only the wide app is launched", listOf("WIDE"), started.map { it.action })
            assertEquals(NativeSplitLauncher.ACTION_TOGGLE_SPLIT, broadcast.captured.action)
            verify(exactly = 0) { pm.getLaunchIntentForPackage("pkg.narrow") }
            assertTrue(
                "journal was ${journal.lines}",
                journal.lines.any { it.startsWith("native split: mode=TOGGLE_50_50 wide=pkg.wide") },
            )
        }

    @Test fun `a package with no launch intent fails without touching the screen`() = runTest {
        noCategoryFilters()
        every { pm.getLaunchIntentForPackage(any()) } returns null

        assertFalse(launcher().launch(pair))

        assertTrue("nothing may be launched, started=$started", started.isEmpty())
        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.startsWith("native split failed:") && it.contains("pkg.wide") },
        )
    }

    @Test fun `a throwing startActivity is reported as a failure`() = runTest {
        withLaunchIntents()
        noCategoryFilters()
        val l = launcher()
        every { context.startActivity(any()) } throws SecurityException("no BAL permission")

        assertFalse(l.launch(pair))

        assertTrue(
            "journal was ${journal.lines}",
            journal.lines.any { it.startsWith("native split failed:") && it.contains("SecurityException") },
        )
    }
}

/**
 * Native mode replaces the whole freeform machinery, so a start under it must not reach the helper
 * daemon at all — and with the switch off nothing about the freeform path may change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitSessionManagerNativeModeTest {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    private class NativeModeFakePreferences(private val native: Boolean) : SplitPreferences {
        var saved: SplitPair? = null
        override fun getLastPair(): SplitPair? = saved
        override fun saveLastPair(pair: SplitPair) { saved = pair }
        override fun clearLastPair() { saved = null }
        override fun isFeatureEnabled(): Boolean = true
        override fun setFeatureEnabled(enabled: Boolean) {}
        override fun isNativeModeEnabled(): Boolean = native
        override fun setNativeModeEnabled(enabled: Boolean) {}
    }

    private class NativeModeFakeBackdrop : SplitBackdrop {
        var showCalls = 0
        override suspend fun show(): Boolean { showCalls++; return true }
        override fun hide() {}
    }

    @Test fun `native mode delegates the start and leaves the freeform path untouched`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = NativeModeFakeBackdrop()
        val prefs = NativeModeFakePreferences(native = true)
        val journal = NativeRecordingJournal()
        val launcher = mockk<NativeSplitLauncher>()
        coEvery { launcher.launch(pair) } returns true

        val mgr = SplitSessionManager(
            helper, prefs, backdrop, backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
            nativeLauncher = launcher,
        )

        assertEquals(SplitStartResult.OK, mgr.start(pair))

        assertEquals("no session of ours exists", SplitSessionState.Idle, mgr.state.value)
        verify { helper wasNot Called }
        assertEquals("the backdrop belongs to the freeform layout", 0, backdrop.showCalls)
        assertEquals("the widget must still be able to restore the pair", pair, prefs.saved)
        assertTrue("journal was ${journal.lines}", journal.lines.any { it.contains("-> native OK") })
    }

    @Test fun `a failed native launch reports LAUNCH_FAILED`() = runTest {
        val prefs = NativeModeFakePreferences(native = true)
        val journal = NativeRecordingJournal()
        val launcher = mockk<NativeSplitLauncher>()
        coEvery { launcher.launch(pair) } returns false

        val mgr = SplitSessionManager(
            mockk(relaxed = true), prefs, NativeModeFakeBackdrop(), backgroundScope,
            tickDelayMs = 60_000,
            journal = journal,
            nativeLauncher = launcher,
        )

        assertEquals(SplitStartResult.LAUNCH_FAILED, mgr.start(pair))
        assertEquals("a pair that did not launch is not remembered", null, prefs.saved)
        assertTrue("journal was ${journal.lines}", journal.lines.any { it.contains("-> native failed") })
    }

    @Test fun `with native mode off the launcher is never consulted`() = runTest {
        val launcher = mockk<NativeSplitLauncher>()
        val helper = mockk<HelperClient>(relaxed = true)
        coEvery { helper.getTaskState(any()) } returns null

        val mgr = SplitSessionManager(
            helper, NativeModeFakePreferences(native = false), NativeModeFakeBackdrop(),
            backgroundScope,
            tickDelayMs = 60_000,
            nativeLauncher = launcher,
        )

        mgr.start(pair)

        verify { launcher wasNot Called }
        // The freeform path always asks the daemon for the pane's task state first.
        coVerify { helper.getTaskState(any()) }
    }
}
