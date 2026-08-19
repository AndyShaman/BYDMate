package com.bydmate.app.split

import app.cash.turbine.test
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.Split37Root
import com.bydmate.app.data.vehicle.SplitTaskState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// ─── Fakes (kept minimal; the freeform suite has its own copies) ──────────────

private class Fake37Preferences(featureEnabled: Boolean = true) : SplitPreferences {
    private var saved: SplitPair? = null
    private var featureEnabled = featureEnabled
    var saves = 0
        private set
    override fun getLastPair(): SplitPair? = saved
    override fun saveLastPair(pair: SplitPair) { saved = pair; saves++ }
    override fun clearLastPair() { saved = null }
    override fun isFeatureEnabled(): Boolean = featureEnabled
    override fun setFeatureEnabled(enabled: Boolean) { featureEnabled = enabled }
}

private class Fake37Journal : SplitJournal {
    val lines = mutableListOf<String>()
    override fun append(payload: String) { lines += payload }
    override fun read(): List<String> = lines
}

private class Fake37Backdrop : SplitBackdrop {
    var shown = false
    override suspend fun show(): Boolean { shown = true; return true }
    override fun hide() { shown = false }
}

/**
 * SplitSessionManager over a "platformized" firmware (OTA V1.6), where [Split37Engine] owns the
 * panes: the firmware draws them, so not one freeform operation of ours may reach a pane task, and
 * a session that the engine cannot start must leave the rest of the fleet's path untouched.
 *
 * The engine itself is covered by Split37EngineTest — here it is a mock, and what is asserted is
 * the wiring around it: which path a start takes, what the state says about who owns the panes,
 * and which helper calls do NOT happen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SplitSessionManager37Test {

    private val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT)

    // Live geometry from the car (2026-08-18): narrow pane left, wide right.
    private val narrowRoot = Split37Root(101, 18, 84, 642, 984)
    private val wideRoot = Split37Root(102, 660, 84, 1902, 984)

    private fun session(
        forPair: SplitPair = pair,
        narrowTaskId: Int = 11,
        wideTaskId: Int = 10,
    ) = Split37Engine.Session(forPair, narrowTaskId, wideTaskId, narrowRoot, wideRoot, 100)

    /** Engine that answers "this firmware has the native split" and nothing else. */
    private fun engineMock(applicable: Boolean = true): Split37Engine {
        val engine = mockk<Split37Engine>()
        every { engine.isApplicable() } returns applicable
        return engine
    }

    /** Stubs the freeform path so a fall-through start can succeed on the legacy branches. */
    private fun HelperClient.stubFreeformPath() {
        val (wide, narrow) = boundsFor(SplitSide.RIGHT)
        coEvery { forceStop(any()) } returns true
        coEvery { launchFreeform(any(), any(), any(), any(), any(), any(), any()) } returns
            FreeformLaunchResult.OK
        coEvery { getTaskState("pkg.wide") } returns
            SplitTaskState(10, 5, wide.left, wide.top, wide.right, wide.bottom)
        coEvery { getTaskState("pkg.narrow") } returns
            SplitTaskState(11, 5, narrow.left, narrow.top, narrow.right, narrow.bottom)
    }

    // ── start() ───────────────────────────────────────────────────────────────

    @Test fun `applicable engine places the pair and the panes belong to the firmware`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val backdrop = Fake37Backdrop()
        val engine = engineMock()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(session())

        val mgr = SplitSessionManager(
            helper, prefs, backdrop, backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(
            SplitSessionState.Active(pair, 11, 10, nativePanes = true),
            mgr.state.value,
        )
        assertEquals(pair, prefs.getLastPair())
        // Nothing of the freeform session may be set up over the firmware's own panes.
        assertEquals(false, backdrop.shown)
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { engine.place(pair) }
    }

    @Test fun `an unavailable engine falls through to the freeform path`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = Fake37Backdrop()
        val prefs = Fake37Preferences()
        val engine = engineMock()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Unavailable
        helper.stubFreeformPath()

        val mgr = SplitSessionManager(
            helper, prefs, backdrop, backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10, nativePanes = false), mgr.state.value)
        assertEquals(pair, prefs.getLastPair())
        assertEquals(true, backdrop.shown)
        coVerify { helper.launchFreeform("pkg.wide", any(), any(), any(), any(), any(), any()) }
        coVerify { helper.launchFreeform("pkg.narrow", any(), any(), any(), any(), any(), any()) }
    }

    @Test fun `firmware without the native split is never asked to place a pair`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock(applicable = false)
        helper.stubFreeformPath()

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 60_000, split37 = engine,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10, nativePanes = false), mgr.state.value)
        coVerify(exactly = 0) { engine.place(any()) }
    }

    @Test fun `a failed placement ends the start honestly`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = Fake37Backdrop()
        val prefs = Fake37Preferences()
        val engine = engineMock()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Failed("move narrow failed")

        val mgr = SplitSessionManager(
            helper, prefs, backdrop, backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertEquals(false, backdrop.shown)
        // A failure of the firmware's own split says nothing about freeform, so no fall-through.
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        assertEquals(null, prefs.getLastPair())
    }

    @Test fun `starting another pair over a native session replaces it in place`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val other = SplitPair("pkg.other", "pkg.wide", SplitSide.RIGHT)
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(session())
        coEvery { engine.place(other) } returns
            Split37Engine.PlaceOutcome.Placed(session(other, narrowTaskId = 21, wideTaskId = 10))

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            val result = mgr.start(other)
            assertEquals(SplitStartResult.OK, result)
            // The panes stay on screen through the swap — no session ends here.
            expectNoEvents()
        }
        assertEquals(
            SplitSessionState.Active(other, 21, 10, nativePanes = true),
            mgr.state.value,
        )
        assertEquals(other, prefs.getLastPair())
        coVerify(exactly = 0) { engine.exit(any()) }
    }

    // ── exit / mirror / swapApps / changeApp ──────────────────────────────────

    @Test fun `exit hands the panes back through the engine`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.exit(live) } returns true

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            mgr.exit()
            assertEquals(SplitEvent.SessionEnded(EndReason.EXIT), awaitItem())
        }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        coVerify(exactly = 1) { engine.exit(live) }
        // The freeform teardown must not run over the firmware's windows.
        coVerify(exactly = 0) { helper.setTaskWindowingMode(any(), any()) }
    }

    @Test fun `an exit the engine refused ends the session and says so in the journal`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val journal = Fake37Journal()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        // The firmware kept its panes on screen; the user still asked for the session to end.
        coEvery { engine.exit(live) } returns false

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 60_000, split37 = engine, journal = journal,
        )
        mgr.start(pair)

        mgr.exit()

        assertEquals(SplitSessionState.Idle, mgr.state.value)
        assertTrue(
            journal.lines.toString(),
            journal.lines.any { it.contains("exit split37: engine refused, session ended anyway") },
        )
    }

    @Test fun `mirror swaps the sides through the engine`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.swap(live) } returns live.copy(narrowRoot = wideRoot, wideRoot = narrowRoot)

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        mgr.mirror()

        val expected = pair.copy(narrowSide = SplitSide.LEFT)
        assertEquals(SplitSessionState.Active(expected, 11, 10, nativePanes = true), mgr.state.value)
        assertEquals(expected, prefs.getLastPair())
        coVerify(exactly = 1) { engine.swap(live) }
        coVerify(exactly = 0) { helper.setTaskBounds(any(), any(), any(), any(), any()) }
    }

    @Test fun `a refused swap leaves the session exactly as it was`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.swap(live) } returns null

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        mgr.mirror()

        assertEquals(SplitSessionState.Active(pair, 11, 10, nativePanes = true), mgr.state.value)
        assertEquals(pair, prefs.getLastPair())
    }

    @Test fun `mirror hands the engine a session whose pair followed the state`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        val ticked = mutableListOf<Split37Engine.Session>()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        // The engine returns the roots it read after the swap; the pair inside is the pre-swap one.
        coEvery { engine.swap(live) } returns live.copy(narrowRoot = wideRoot, wideRoot = narrowRoot)
        coEvery { engine.tick(capture(ticked)) } returns Split37Engine.TickOutcome(
            false, Split37Engine.PaneState.IN_PANE, Split37Engine.PaneState.IN_PANE, live,
        )

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)
        mgr.mirror()

        advanceTimeBy(150)
        runCurrent()

        assertTrue("the watchdog must have ticked", ticked.isNotEmpty())
        assertEquals(SplitSide.LEFT, ticked[0].pair.narrowSide)
    }

    @Test fun `swapApps re-places the reversed pair without ending the session`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val reversed = SplitPair(
            narrowPkg = "pkg.wide", widePkg = "pkg.narrow", narrowSide = SplitSide.RIGHT,
        )
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(session())
        coEvery { engine.place(reversed) } returns
            Split37Engine.PlaceOutcome.Placed(session(reversed, narrowTaskId = 10, wideTaskId = 11))

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            mgr.swapApps()
            expectNoEvents()
        }
        assertEquals(
            SplitSessionState.Active(reversed, 10, 11, nativePanes = true),
            mgr.state.value,
        )
        assertEquals(reversed, prefs.getLastPair())
        coVerify(exactly = 1) { engine.place(reversed) }
    }

    @Test fun `changeApp re-places the pair with the new package`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val replaced = pair.copy(widePkg = "pkg.new")
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(session())
        coEvery { engine.place(replaced) } returns
            Split37Engine.PlaceOutcome.Placed(session(replaced, narrowTaskId = 11, wideTaskId = 30))

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        val result = mgr.changeApp(Pane.WIDE, "pkg.new")

        assertEquals(SplitStartResult.OK, result)
        assertEquals(
            SplitSessionState.Active(replaced, 11, 30, nativePanes = true),
            mgr.state.value,
        )
        assertEquals(replaced, prefs.getLastPair())
        // No force-stop, no freeform launch, no dismissal of the replaced task.
        coVerify(exactly = 0) { helper.launchFreeform(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { helper.forceStop(any()) }
    }

    @Test fun `a failed changeApp leaves the pair standing`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val replaced = pair.copy(widePkg = "pkg.new")
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(session())
        coEvery { engine.place(replaced) } returns Split37Engine.PlaceOutcome.Failed("launch failed")

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope, tickDelayMs = 60_000, split37 = engine,
        )
        mgr.start(pair)

        val result = mgr.changeApp(Pane.WIDE, "pkg.new")

        assertEquals(SplitStartResult.LAUNCH_FAILED, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10, nativePanes = true), mgr.state.value)
        assertEquals(pair, prefs.getLastPair())
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    @Test fun `one tick reporting the split gone is not enough to end the session`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(any()) } returnsMany listOf(
            Split37Engine.TickOutcome(
                true, Split37Engine.PaneState.UNKNOWN, Split37Engine.PaneState.UNKNOWN, live,
            ),
            Split37Engine.TickOutcome(
                false, Split37Engine.PaneState.IN_PANE, Split37Engine.PaneState.IN_PANE, live,
            ),
        )

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            advanceTimeBy(250)
            runCurrent()
            expectNoEvents()
        }
        assertEquals(SplitSessionState.Active(pair, 11, 10, nativePanes = true), mgr.state.value)
    }

    @Test fun `two ticks in a row report the firmware left the split and the session ends`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(any()) } returns Split37Engine.TickOutcome(
            true, Split37Engine.PaneState.UNKNOWN, Split37Engine.PaneState.UNKNOWN, live,
        )

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            advanceTimeBy(250)
            runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.EXIT), awaitItem())
        }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
        // The panes are the user's business now — nothing is moved back.
        coVerify(exactly = 0) { engine.exit(any()) }
    }

    @Test fun `both panes gone ends the session`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(any()) } returns Split37Engine.TickOutcome(
            false, Split37Engine.PaneState.GONE, Split37Engine.PaneState.GONE, live,
        )

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            advanceTimeBy(150)
            runCurrent()
            assertEquals(SplitEvent.SessionEnded(EndReason.EXIT), awaitItem())
        }
        assertEquals(SplitSessionState.Idle, mgr.state.value)
    }

    @Test fun `one gone pane is left standing`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(any()) } returns Split37Engine.TickOutcome(
            false, Split37Engine.PaneState.GONE, Split37Engine.PaneState.IN_PANE, live,
        )

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        mgr.events.test {
            advanceTimeBy(250)
            runCurrent()
            // No picker over native panes: a dead pane produces no PaneClosed either.
            expectNoEvents()
        }
        assertEquals(SplitSessionState.Active(pair, 11, 10, nativePanes = true), mgr.state.value)
    }

    @Test fun `a recreated task is adopted and the next tick sees it`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val engine = engineMock()
        val live = session()
        val recreated = live.copy(narrowTaskId = 99)
        val ticked = mutableListOf<Split37Engine.Session>()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(capture(ticked)) } returnsMany listOf(
            Split37Engine.TickOutcome(
                false, Split37Engine.PaneState.IN_PANE, Split37Engine.PaneState.IN_PANE, recreated,
            ),
            Split37Engine.TickOutcome(
                false, Split37Engine.PaneState.IN_PANE, Split37Engine.PaneState.IN_PANE, recreated,
            ),
        )

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        advanceTimeBy(250)
        runCurrent()

        assertEquals(SplitSessionState.Active(pair, 99, 10, nativePanes = true), mgr.state.value)
        assertEquals("the first tick gets the session the placement produced", live, ticked[0])
        assertTrue("the second tick must carry the adopted task id", ticked.size >= 2)
        assertEquals(recreated, ticked[1])
    }


    @Test fun `an adopted pair becomes the pair of the session and the one a tap brings back`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val live = session()
        val adoptedPair = pair.copy(widePkg = "com.foreign")
        val adopted = live.copy(pair = adoptedPair, wideTaskId = 55, displacedWidePkg = "pkg.wide")
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(any()) } returns Split37Engine.TickOutcome(
            false, Split37Engine.PaneState.IN_PANE, Split37Engine.PaneState.IN_PANE, adopted,
            pairChanged = true,
        )

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        advanceTimeBy(150)
        runCurrent()

        assertEquals(
            SplitSessionState.Active(adoptedPair, 11, 55, nativePanes = true),
            mgr.state.value,
        )
        assertEquals(adoptedPair, prefs.getLastPair())
    }

    @Test fun `an ordinary tick does not rewrite the saved pair`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val prefs = Fake37Preferences()
        val engine = engineMock()
        val live = session()
        coEvery { engine.place(pair) } returns Split37Engine.PlaceOutcome.Placed(live)
        coEvery { engine.tick(any()) } returns Split37Engine.TickOutcome(
            false, Split37Engine.PaneState.IN_PANE, Split37Engine.PaneState.IN_PANE, live,
        )

        val mgr = SplitSessionManager(
            helper, prefs, Fake37Backdrop(), backgroundScope,
            tickDelayMs = 100, split37 = engine,
        )
        mgr.start(pair)

        advanceTimeBy(250)
        runCurrent()

        assertEquals("only the start saved the pair", 1, prefs.saves)
    }

    // ── No engine wired ───────────────────────────────────────────────────────

    @Test fun `without an engine the start takes the freeform path unchanged`() = runTest {
        val helper = mockk<HelperClient>(relaxed = true)
        val backdrop = Fake37Backdrop()
        helper.stubFreeformPath()

        val mgr = SplitSessionManager(
            helper, Fake37Preferences(), backdrop, backgroundScope, tickDelayMs = 60_000,
        )

        val result = mgr.start(pair)

        assertEquals(SplitStartResult.OK, result)
        assertEquals(SplitSessionState.Active(pair, 11, 10), mgr.state.value)
        assertEquals(true, backdrop.shown)
    }
}
