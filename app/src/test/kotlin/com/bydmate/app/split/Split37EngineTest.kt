package com.bydmate.app.split

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.Split37AreaInfo
import com.bydmate.app.data.vehicle.Split37Root
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.data.vehicle.TopTaskInfo
import android.graphics.Rect
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

private class Split37RecordingJournal : SplitJournal {
    val lines = mutableListOf<String>()
    override fun append(payload: String) { lines += payload }
    override fun read(): List<String> = lines
}

/**
 * The engine is the only thing standing between a "platformized" firmware's own panes and a pair of
 * foreign apps, and its whole failure surface is the daemon: an outdated one, a silent channel, a
 * task that has not come up yet, an app that throws itself back to fullscreen. Robolectric because
 * the pane geometry is asserted through real [Rect]s (a plain unit test gets an all-zero stub).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class Split37EngineTest {

    // Live geometry from the car (2026-08-18): narrow pane left, wide right, fullscreen root.
    private val narrowLeft = Split37Root(101, 18, 84, 642, 984)
    private val wideRight = Split37Root(102, 660, 84, 1902, 984)
    // After SwapSplitPosition the narrow root keeps its id and width and changes edge.
    private val narrowRight = Split37Root(101, 1278, 84, 1902, 984)
    private val wideLeft = Split37Root(102, 18, 84, 1260, 984)
    private val fullRoot = Split37Root(100, 0, 0, 1920, 1080)

    private val ourPkg = "com.bydmate.app"

    private val helper = mockk<HelperClient>(relaxed = true)
    private val journal = Split37RecordingJournal()
    private var now = 1_000L

    private fun engine(platformized: String? = "1") = Split37Engine(
        helper = helper,
        journal = journal,
        systemProperty = { name -> platformized.takeIf { name == "ro.build.ui_platformized" } },
        nowMs = { now },
    )

    /** Engine wired the way production is: every package is on a launcher, ours is known. */
    private fun adoptingEngine(isLauncherApp: (String) -> Boolean = { true }) = Split37Engine(
        helper = helper,
        journal = journal,
        systemProperty = { "1" },
        nowMs = { now },
        isLauncherApp = isLauncherApp,
        ownPackage = ourPkg,
    )

    private fun splitInfo(narrow: Split37Root? = narrowLeft, wide: Split37Root? = wideRight) =
        Split37AreaInfo(3, narrow, wide, fullRoot)

    private fun absent() = SplitTaskState(-1, 0, 0, 0, 0, 0)
    private fun task(id: Int) = SplitTaskState(id, 1, 0, 0, 0, 0)
    /** Same package, but its task stands on the cluster projection display. */
    private fun clusterTask(id: Int) = SplitTaskState(id, 1, 0, 0, 0, 0, displayId = 2)
    private fun fullscreenInfo() = Split37AreaInfo(4, narrowLeft, wideRight, fullRoot)

    private fun rectOf(root: Split37Root) = Rect(root.left, root.top, root.right, root.bottom)

    private fun topTask(
        pkg: String,
        taskId: Int,
        activityType: Int = 1,
        displayId: Int = 0,
        visible: Boolean? = true,
    ) = TopTaskInfo(pkg, taskId, 1, activityType, displayId, visible)

    /** A standing session with both panes readable, so a tick only has the adoption to do. */
    private fun stubPanesInPlace() {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 1
        coEvery { helper.split37TaskArea(20) } returns 2
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
    }

    /** Split is up, both moves and launches succeed; task states are stubbed per test. */
    private fun stubSplitUp(vararg info: Split37AreaInfo) {
        coEvery { helper.split37Enter() } returns 3
        if (info.size == 1) coEvery { helper.split37AreaInfo() } returns info.single()
        else coEvery { helper.split37AreaInfo() } returnsMany info.toList()
        coEvery { helper.split37Swap() } returns true
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.split37MoveTask(any(), any(), any()) } returns true
    }

    private fun session(narrowTaskId: Int = 21, wideTaskId: Int = 20, fullRootId: Int? = 100) =
        Split37Engine.Session(
            pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT),
            narrowTaskId = narrowTaskId,
            wideTaskId = wideTaskId,
            narrowRoot = narrowLeft,
            wideRoot = wideRight,
            fullRootId = fullRootId,
        )

    /** The session as it stands after an adoption: com.foreign in the wide pane, pkg.wide behind. */
    private fun adopted() = session().copy(
        pair = SplitPair("pkg.narrow", "com.foreign", SplitSide.LEFT),
        wideTaskId = 55,
        displacedWidePkg = "pkg.wide",
    )

    // ── isApplicable ──────────────────────────────────────────────────────────

    @Test fun `isApplicable follows the platformized property only`() {
        assertTrue(engine("1").isApplicable())
        assertTrue("a padded property value is still a 1", engine(" 1 ").isApplicable())
        assertFalse(engine("0").isApplicable())
        assertFalse("an unset property is a pre-OTA firmware", engine(null).isApplicable())
    }

    @Test fun `the settings gate is the same predicate as the engine gate`() {
        // SettingsScreen hides the mechanism chips through this helper; if the two drifted apart
        // the screen would describe a split the engine does not run.
        for (value in listOf("1", " 1 ", "0", "", null)) {
            assertEquals(
                "property=$value",
                engine(value).isApplicable(),
                Split37Engine.isPlatformizedFirmware { value },
            )
        }
    }

    // ── place() ───────────────────────────────────────────────────────────────

    @Test fun `place launches both apps and moves them into their roots, wide first`() = runTest {
        stubSplitUp(splitInfo())
        // Pre-launch read plus the confirming loop: the task appears on the second loop read.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany
            listOf(absent(), absent(), task(20))
        coEvery { helper.getTaskState("pkg.narrow") } returnsMany
            listOf(absent(), absent(), task(21))

        val pair = SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT)
        val outcome = engine().place(pair)

        val placed = outcome as Split37Engine.PlaceOutcome.Placed
        assertEquals(Split37Engine.Session(pair, 21, 20, narrowLeft, wideRight, 100), placed.session)
        coVerify(exactly = 1) { helper.launchApp("pkg.wide") }
        coVerify(exactly = 1) { helper.launchApp("pkg.narrow") }
        coVerify(exactly = 0) { helper.split37Swap() }
        coVerifyOrder {
            helper.split37MoveTask(20, 102, rectOf(wideRight))
            helper.split37MoveTask(21, 101, rectOf(narrowLeft))
        }
    }

    @Test fun `place swaps the sides once and then uses the post-swap bounds`() = runTest {
        stubSplitUp(splitInfo(), splitInfo(narrow = narrowRight, wide = wideLeft))
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.RIGHT))

        assertTrue(outcome is Split37Engine.PlaceOutcome.Placed)
        coVerify(exactly = 1) { helper.split37Swap() }
        coVerify { helper.split37MoveTask(20, 102, rectOf(wideLeft)) }
        coVerify { helper.split37MoveTask(21, 101, rectOf(narrowRight)) }
        assertTrue(journal.lines.any { it.contains("split37 side: narrow=LEFT wanted=RIGHT swap=yes") })
    }

    @Test fun `place does not relaunch an app that is already running`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertTrue(outcome is Split37Engine.PlaceOutcome.Placed)
        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 1) { helper.split37MoveTask(20, 102, rectOf(wideRight)) }
        coVerify(exactly = 1) { helper.split37MoveTask(21, 101, rectOf(narrowLeft)) }
    }

    @Test fun `place bounces a task that already stands in its target root`() = runTest {
        // A move into the root a task already lives in does not raise it, so the firmware's own
        // widget stays on top of our pane; the bounce through the fullscreen root gives it order.
        stubSplitUp(splitInfo())
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(20) } returns 2
        coEvery { helper.split37TaskArea(21) } returns 4

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertTrue(outcome is Split37Engine.PlaceOutcome.Placed)
        coVerifyOrder {
            helper.split37MoveTask(20, 100, null, false)
            helper.split37MoveTask(20, 102, rectOf(wideRight))
        }
        // The narrow task is out in fullscreen — the ordinary move already raises it.
        coVerify(exactly = 0) { helper.split37MoveTask(21, 100, null, false) }
        assertTrue(
            journal.lines.toString(),
            journal.lines.any {
                it.contains("split37 place wide pkg.wide task=20 already in root 102 - bounced")
            },
        )
    }

    @Test fun `place does not bounce a task it has just launched`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
        coEvery { helper.getTaskState("pkg.wide") } returnsMany listOf(absent(), task(20))
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(any()) } returns 2

        engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        // A freshly launched task cannot be sitting under the widget of a previous session.
        coVerify(exactly = 0) { helper.split37TaskArea(20) }
        coVerify(exactly = 0) { helper.split37MoveTask(20, 100, null, false) }
    }

    @Test fun `place does not bounce a task that stands in the other pane`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        // Both tasks are in the narrow area: only the narrow pane's own task is already home.
        coEvery { helper.split37TaskArea(any()) } returns 1

        engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        coVerify(exactly = 0) { helper.split37MoveTask(20, 100, null, false) }
        coVerify(exactly = 1) { helper.split37MoveTask(21, 100, null, false) }
    }

    @Test fun `place cannot bounce without a fullscreen root and moves the task anyway`() = runTest {
        stubSplitUp(Split37AreaInfo(3, narrowLeft, wideRight, null))
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(any()) } returns 2

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertTrue(outcome is Split37Engine.PlaceOutcome.Placed)
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), null, false) }
        coVerify(exactly = 1) { helper.split37MoveTask(20, 102, rectOf(wideRight)) }
        assertTrue(
            journal.lines.toString(),
            journal.lines.any {
                it.contains("split37 place wide pkg.wide task=20 already in root 102 - no full root, no bounce")
            },
        )
    }

    @Test fun `place still moves a task whose bounce the daemon refused`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
        coEvery { helper.split37MoveTask(20, 100, null, false) } returns false
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(20) } returns 2
        coEvery { helper.split37TaskArea(21) } returns 4

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertTrue(outcome is Split37Engine.PlaceOutcome.Placed)
        coVerify(exactly = 1) { helper.split37MoveTask(20, 102, rectOf(wideRight)) }
        assertTrue(journal.lines.any { it.contains("already in root 102 - bounce failed") })
    }

    @Test fun `place reports Unavailable when the daemon does not answer the enter verb`() = runTest {
        // A silent area read says nothing (transport or non-OK status alike), so the verdict is
        // the enter verb's: no reply there = the mechanism is not available through this daemon.
        coEvery { helper.split37AreaInfo() } returns null
        coEvery { helper.split37Enter() } returns null

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals(Split37Engine.PlaceOutcome.Unavailable, outcome)
        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
        assertTrue(journal.lines.any { it.contains("split37 enter -> no reply") })
    }

    @Test fun `place fails when the split did not come up`() = runTest {
        coEvery { helper.split37AreaInfo() } returns null
        coEvery { helper.split37Enter() } returns 4

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals(
            "enter areaMode=4, settled=null",
            (outcome as Split37Engine.PlaceOutcome.Failed).reason,
        )
        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `place waits out the enter animation instead of failing on the first reading`() = runTest {
        // The verb reads the mode the instant it returns, while the panes are still coming up.
        coEvery { helper.split37AreaInfo() } returnsMany
            listOf(fullscreenInfo(), fullscreenInfo(), splitInfo())
        coEvery { helper.split37Enter() } returns 4
        coEvery { helper.launchApp(any()) } returns true
        coEvery { helper.split37MoveTask(any(), any(), any()) } returns true
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertTrue(outcome.toString(), outcome is Split37Engine.PlaceOutcome.Placed)
        coVerify { helper.split37MoveTask(20, 102, rectOf(wideRight)) }
    }

    @Test fun `place does not enter a split that is already on screen`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertTrue(outcome is Split37Engine.PlaceOutcome.Placed)
        coVerify(exactly = 0) { helper.split37Enter() }
        assertTrue(journal.lines.any { it.contains("split37 enter skipped: split already on screen") })
    }

    @Test fun `place ignores a task of the package that lives on another display`() = runTest {
        stubSplitUp(splitInfo())
        // The cluster projection keeps a task of the same package on display 2; the pane must be a
        // freshly launched task of the main screen instead.
        coEvery { helper.getTaskState("pkg.wide") } returnsMany
            listOf(clusterTask(70), clusterTask(70), task(20))
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals(20, (outcome as Split37Engine.PlaceOutcome.Placed).session.wideTaskId)
        coVerify(exactly = 1) { helper.launchApp("pkg.wide") }
        coVerify(exactly = 0) { helper.split37MoveTask(70, any(), any()) }
        coVerify(exactly = 1) { helper.split37MoveTask(20, 102, rectOf(wideRight)) }
        assertTrue(
            journal.lines.toString(),
            journal.lines.any { it.contains("pkg.wide task=70 lives on display 2 - not ours") },
        )
    }

    @Test fun `place does not adopt a task that comes up on another display`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.getTaskState("pkg.wide") } returns clusterTask(70)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        val reason = (outcome as Split37Engine.PlaceOutcome.Failed).reason
        assertTrue(reason, reason.contains("no task after launch") && reason.contains("display 2"))
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `place fails on a silent area info read`() = runTest {
        coEvery { helper.split37Enter() } returns 3
        coEvery { helper.split37AreaInfo() } returns null

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals("area info -> no reply", (outcome as Split37Engine.PlaceOutcome.Failed).reason)
        coVerify(exactly = 0) { helper.launchApp(any()) }
    }

    @Test fun `place fails when the firmware reports no narrow root`() = runTest {
        coEvery { helper.split37Enter() } returns 3
        coEvery { helper.split37AreaInfo() } returns splitInfo(narrow = null)

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals("area info: no narrow root", (outcome as Split37Engine.PlaceOutcome.Failed).reason)
        coVerify(exactly = 0) { helper.launchApp(any()) }
    }

    @Test fun `place fails when the task never appears after the launch`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.getTaskState("pkg.wide") } returns absent()

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        val reason = (outcome as Split37Engine.PlaceOutcome.Failed).reason
        assertTrue(reason, reason.contains("no task after launch"))
        // One pre-launch read plus the six confirming ones.
        coVerify(exactly = 7) { helper.getTaskState("pkg.wide") }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `place fails after two silent task-state reads in a row`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.getTaskState("pkg.wide") } returns null

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        val reason = (outcome as Split37Engine.PlaceOutcome.Failed).reason
        assertTrue(reason, reason.contains("no task state reply"))
        // Pre-launch read plus the two silences the loop is allowed to spend.
        coVerify(exactly = 3) { helper.getTaskState("pkg.wide") }
    }

    // ── tick() ────────────────────────────────────────────────────────────────

    @Test fun `tick returns an escaped app to its pane`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 4
        coEvery { helper.split37TaskArea(20) } returns 1
        coEvery { helper.split37MoveTask(any(), any(), any()) } returns true

        val outcome = engine().tick(session())

        assertFalse(outcome.sessionEnded)
        assertEquals(Split37Engine.PaneState.RETURNED, outcome.narrow)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.wide)
        coVerify(exactly = 1) { helper.split37MoveTask(21, 101, rectOf(narrowLeft)) }
    }

    @Test fun `tick gives up after three returns in the window and resumes after it`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 4
        coEvery { helper.split37TaskArea(20) } returns 2
        coEvery { helper.split37MoveTask(any(), any(), any()) } returns true
        val engine = engine()

        repeat(3) {
            assertEquals(Split37Engine.PaneState.RETURNED, engine.tick(session()).narrow)
        }
        assertEquals(Split37Engine.PaneState.ESCAPED_GAVE_UP, engine.tick(session()).narrow)
        coVerify(exactly = 3) { helper.split37MoveTask(21, 101, rectOf(narrowLeft)) }
        assertEquals(
            1,
            journal.lines.count { it.contains("escaped 4x in 30s - giving up returns") },
        )

        now += 30_001
        assertEquals(Split37Engine.PaneState.RETURNED, engine.tick(session()).narrow)
        coVerify(exactly = 4) { helper.split37MoveTask(21, 101, rectOf(narrowLeft)) }
    }

    @Test fun `tick reports a pane whose app is gone and does not read its area`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTaskState("pkg.narrow") } returns absent()
        coEvery { helper.getTaskState("pkg.wide") } returns null

        val outcome = engine().tick(session())

        assertEquals(Split37Engine.PaneState.GONE, outcome.narrow)
        assertEquals(Split37Engine.PaneState.UNKNOWN, outcome.wide)
        coVerify(exactly = 0) { helper.split37TaskArea(any()) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `tick leaves a task that stands on another display alone`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTaskState("pkg.narrow") } returns clusterTask(70)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(20) } returns 2

        val engine = engine()
        val outcome = engine.tick(session())

        assertEquals(Split37Engine.PaneState.UNKNOWN, outcome.narrow)
        assertEquals(21, outcome.session.narrowTaskId)
        coVerify(exactly = 0) { helper.split37TaskArea(70) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
        // The line is evidence for a field dump, but a watchdog must not repeat it every tick.
        engine.tick(outcome.session)
        assertEquals(
            1,
            journal.lines.count { it.contains("pkg.narrow task=70 lives on display 2") },
        )
    }

    @Test fun `tick returns an escaped app into the bounds the divider has now`() = runTest {
        // The user dragged the firmware's own divider after the placement.
        val movedNarrow = narrowLeft.copy(right = 800)
        val movedWide = wideRight.copy(left = 818)
        coEvery { helper.split37AreaInfo() } returns splitInfo(narrow = movedNarrow, wide = movedWide)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 4
        coEvery { helper.split37TaskArea(20) } returns 2
        coEvery { helper.split37MoveTask(any(), any(), any()) } returns true

        val outcome = engine().tick(session())

        assertEquals(Split37Engine.PaneState.RETURNED, outcome.narrow)
        assertEquals(movedNarrow, outcome.session.narrowRoot)
        assertEquals(movedWide, outcome.session.wideRoot)
        coVerify(exactly = 1) { helper.split37MoveTask(21, 101, rectOf(movedNarrow)) }
    }

    @Test fun `a silent area read is not the end of the session`() = runTest {
        coEvery { helper.split37AreaInfo() } returns null
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(any()) } returns 1

        val outcome = engine().tick(session())

        assertFalse("a dead channel says nothing about the split", outcome.sessionEnded)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.narrow)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.wide)
        // Without a fresh reading the placement's own bounds stay in force.
        assertEquals(narrowLeft, outcome.session.narrowRoot)
    }

    @Test fun `a new placement gives the panes their returns back`() = runTest {
        stubSplitUp(splitInfo())
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 4
        coEvery { helper.split37TaskArea(20) } returns 2
        val engine = engine()
        repeat(3) { engine.tick(session()) }
        assertEquals(Split37Engine.PaneState.ESCAPED_GAVE_UP, engine.tick(session()).narrow)

        engine.place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals(Split37Engine.PaneState.RETURNED, engine.tick(session()).narrow)
    }

    @Test fun `tick adopts a task id the app recreated`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTaskState("pkg.narrow") } returns task(99)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(any()) } returns 1

        val outcome = engine().tick(session())

        assertEquals(99, outcome.session.narrowTaskId)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.narrow)
        coVerify { helper.split37TaskArea(99) }
        assertTrue(journal.lines.any { it.contains("pkg.narrow task changed 21->99") })
    }

    @Test fun `tick ends the session when the firmware left the split`() = runTest {
        // Area mode 4 with a firmware surface on top, and both pane tasks out of the pane roots:
        // nothing to adopt, nothing of ours escaped, nothing left standing.
        coEvery { helper.split37AreaInfo() } returns fullscreenInfo()
        coEvery { helper.getTopTask() } returns topTask("com.byd.avc", 70)
        coEvery { helper.split37TaskArea(21) } returns 4
        coEvery { helper.split37TaskArea(20) } returns -1

        val outcome = adoptingEngine().tick(session())

        assertTrue(outcome.sessionEnded)
        assertEquals(Split37Engine.PaneState.UNKNOWN, outcome.narrow)
        assertEquals(Split37Engine.PaneState.UNKNOWN, outcome.wide)
        assertTrue(journal.lines.any { it.contains("split37 tick: area mode 4 - split closed") })
        coVerify(exactly = 0) { helper.getTaskState(any()) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `tick ends the session when area mode 4 has no window of ours on top`() = runTest {
        coEvery { helper.split37AreaInfo() } returns fullscreenInfo()
        coEvery { helper.split37TaskArea(any()) } returns 4
        val engine = adoptingEngine()

        // No answer at all (a task list the daemon could not read).
        coEvery { helper.getTopTask() } returns null
        assertTrue(engine.tick(session()).sessionEnded)

        // Our own pane app, but the MRU task is not the window on screen.
        coEvery { helper.getTopTask() } returns topTask("pkg.wide", 20, visible = false)
        assertTrue(engine.tick(session()).sessionEnded)

        assertEquals(
            2,
            journal.lines.count { it.contains("split37 tick: area mode 4 - split closed") },
        )
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }


    // ── tick(): adoption ──────────────────────────────────────────────────────

    @Test fun `tick adopts a foreign app the firmware threw over the panes`() = runTest {
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        // Fullscreen when it is spotted, in the wide pane once it has been moved there.
        coEvery { helper.split37TaskArea(55) } returnsMany listOf(4, 2)
        coEvery { helper.getTaskState("com.foreign") } returns task(55)

        val outcome = adoptingEngine().tick(session())

        coVerify(exactly = 1) { helper.split37MoveTask(55, 102, rectOf(wideRight)) }
        assertTrue(outcome.pairChanged)
        assertEquals("com.foreign", outcome.session.pair.widePkg)
        assertEquals(55, outcome.session.wideTaskId)
        assertEquals("pkg.wide", outcome.session.displacedWidePkg)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.wide)
        assertTrue(
            journal.lines.any {
                it.contains("split37 adopt: com.foreign task=55 -> wide root 102 (moved), displaced pkg.wide")
            },
        )
    }

    @Test fun `tick adopts an app the firmware itself put in the wide pane, without moving it`() = runTest {
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        coEvery { helper.split37TaskArea(55) } returns 2
        coEvery { helper.getTaskState("com.foreign") } returns task(55)

        val outcome = adoptingEngine().tick(session())

        coVerify(exactly = 0) { helper.split37MoveTask(55, any(), any(), any()) }
        assertTrue(outcome.pairChanged)
        assertEquals("com.foreign", outcome.session.pair.widePkg)
        assertEquals(55, outcome.session.wideTaskId)
        assertEquals("pkg.wide", outcome.session.displacedWidePkg)
        assertTrue(
            journal.lines.any {
                it.contains("-> wide root 102 (already there), displaced pkg.wide")
            },
        )
        // The firmware's grid left com.byd.sr over our narrow task; the bounce puts it back on top.
        coVerifyOrder {
            helper.split37MoveTask(21, 100, null, false)
            helper.split37MoveTask(21, 101, rectOf(narrowLeft), true)
        }
        assertTrue(
            journal.lines.any { it.contains("split37 adopt: narrow pkg.narrow task=21 re-raised") },
        )
    }

    @Test fun `tick adopts the app the firmware started fullscreen over the standing panes`() = runTest {
        // Field dump 2026-08-19: a non-whitelisted app started from the firmware's own grid lands
        // fullscreen, the area reads 4 while both pane roots still hold our tasks, and the single
        // move into the wide root brings the split back by itself.
        coEvery { helper.split37AreaInfo() } returnsMany listOf(fullscreenInfo(), splitInfo())
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        coEvery { helper.split37TaskArea(55) } returns 4
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(21) } returns 1
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true

        val outcome = adoptingEngine().tick(session())

        assertFalse("the split came back, so the session stands", outcome.sessionEnded)
        assertTrue(outcome.pairChanged)
        assertEquals("com.foreign", outcome.session.pair.widePkg)
        assertEquals(55, outcome.session.wideTaskId)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.wide)
        assertTrue(
            journal.lines.toString(),
            journal.lines.any {
                it.contains(
                    "split37 adopt: com.foreign task=55 -> wide root 102 (moved, split back), " +
                        "displaced pkg.wide"
                )
            },
        )
        coVerify(exactly = 1) { helper.split37MoveTask(55, 102, rectOf(wideRight), true) }
        coVerifyOrder {
            helper.split37MoveTask(21, 100, null, false)
            helper.split37MoveTask(21, 101, rectOf(narrowLeft), true)
        }
    }

    @Test fun `an adoption that did not bring the split back ends the session and is not retried`() = runTest {
        coEvery { helper.split37AreaInfo() } returns fullscreenInfo()
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        coEvery { helper.split37TaskArea(any()) } returns 4
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true
        val engine = adoptingEngine()

        val outcome = engine.tick(session())

        assertTrue(outcome.sessionEnded)
        assertFalse(outcome.pairChanged)
        assertTrue(
            journal.lines.toString(),
            journal.lines.any {
                it.contains(
                    "split37 adopt: com.foreign task=55 moved to wide root 102 but split did " +
                        "not come back (area=4)"
                )
            },
        )
        // The same doomed move must not run again on the tick after it.
        journal.lines.clear()
        assertTrue(engine.tick(session()).sessionEnded)
        coVerify(exactly = 1) { helper.split37MoveTask(55, 102, rectOf(wideRight), true) }
        assertTrue(journal.lines.none { it.contains("adopt") })
    }

    @Test fun `a fullscreen lid over standing panes does not end the session`() = runTest {
        // The user opened BYDMate itself over the split (to save the journal): the area reads 4,
        // but both pane tasks are still in their roots and the split comes back when it closes.
        coEvery { helper.split37AreaInfo() } returnsMany listOf(
            fullscreenInfo(), fullscreenInfo(), fullscreenInfo(), splitInfo(), fullscreenInfo(),
        )
        coEvery { helper.getTopTask() } returns topTask(ourPkg, 60)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 1
        coEvery { helper.split37TaskArea(20) } returns 2
        val engine = adoptingEngine()

        repeat(3) {
            val outcome = engine.tick(session())
            assertFalse("tick $it", outcome.sessionEnded)
            assertEquals(Split37Engine.PaneState.IN_PANE, outcome.narrow)
            assertEquals(Split37Engine.PaneState.IN_PANE, outcome.wide)
        }

        val line = "split37 tick: area mode 4 - covered by $ourPkg, panes standing"
        assertEquals(
            journal.lines.toString(),
            1,
            journal.lines.count { it.contains(line) },
        )
        assertTrue(journal.lines.none { it.contains("split closed") })

        // The lid is gone: the episode is over and a new one is journalled again.
        assertFalse(engine.tick(session()).sessionEnded)
        assertEquals(1, journal.lines.count { it.contains(line) })
        assertFalse(engine.tick(session()).sessionEnded)
        assertEquals(2, journal.lines.count { it.contains(line) })

        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `a fullscreen window over panes that left their roots ends the session`() = runTest {
        coEvery { helper.split37AreaInfo() } returns fullscreenInfo()
        coEvery { helper.getTopTask() } returns topTask("com.byd.avc", 70)
        // The narrow task went fullscreen with the lid, the wide one has no readable area at all.
        coEvery { helper.split37TaskArea(21) } returns 4
        coEvery { helper.split37TaskArea(20) } returns -1

        val outcome = adoptingEngine().tick(session())

        assertTrue(outcome.sessionEnded)
        assertTrue(journal.lines.any { it.contains("split37 tick: area mode 4 - split closed") })
        assertTrue(journal.lines.none { it.contains("panes standing") })
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }

    @Test fun `area mode 4 with our own pane app on top is an escape, not a closed split`() = runTest {
        coEvery { helper.split37AreaInfo() } returns fullscreenInfo()
        coEvery { helper.getTopTask() } returns topTask("pkg.wide", 20)
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(21) } returns 1
        coEvery { helper.split37TaskArea(20) } returns 4
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true

        val outcome = adoptingEngine().tick(session())

        assertFalse(outcome.sessionEnded)
        assertEquals(Split37Engine.PaneState.RETURNED, outcome.wide)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.narrow)
        coVerify(exactly = 1) { helper.split37MoveTask(20, 102, rectOf(wideRight), true) }
        assertTrue(
            journal.lines.any {
                it.contains("split37 tick: pkg.wide escaped to fullscreen - returned to root 102")
            },
        )
        assertTrue(journal.lines.none { it.contains("split closed") })
    }

    @Test fun `tick adopts neither the panes' own apps, nor ours, nor a firmware surface`() = runTest {
        // Fullscreen over the panes: the whole firmware class stays out, not the named surfaces
        // alone - one of its windows on top is never the user picking an app.
        stubPanesInPlace()
        coEvery { helper.split37TaskArea(55) } returns 4
        val engine = adoptingEngine()

        for (pkg in listOf(
            "pkg.narrow", "pkg.wide", ourPkg, "com.byd.sr", "com.android.launcher3",
            "com.byd.launchermap",
            // The whole firmware class, not the named surfaces alone: the reversing camera comes
            // up as an ordinary fullscreen app when the user selects reverse.
            "com.byd.avc", "com.byd.cdr", "com.byd.carsettings", "com.android.settings",
        )) {
            coEvery { helper.getTopTask() } returns topTask(pkg, 55)

            val outcome = engine.tick(session())

            assertFalse(pkg, outcome.pairChanged)
            assertEquals(pkg, "pkg.wide", outcome.session.pair.widePkg)
        }
        coVerify(exactly = 0) { helper.split37MoveTask(55, any(), any(), any()) }
    }

    @Test fun `tick adopts the BYD app the firmware itself put into the wide root`() = runTest {
        // Field dump 2026-08-19: starting a whitelisted BYD app from the grid runs startSplitWindow,
        // so it arrives in the wide root already. The firmware has decided it is a pane app; the
        // launcher and system-package gates of the fullscreen path do not apply to it.
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.byd.bluetoothcall", 55)
        coEvery { helper.split37TaskArea(55) } returns 2
        coEvery { helper.getTaskState("com.byd.bluetoothcall") } returns task(55)

        val outcome = adoptingEngine(isLauncherApp = { false }).tick(session())

        assertTrue(outcome.pairChanged)
        assertEquals("com.byd.bluetoothcall", outcome.session.pair.widePkg)
        assertEquals(55, outcome.session.wideTaskId)
        assertEquals("pkg.wide", outcome.session.displacedWidePkg)
        coVerify(exactly = 0) { helper.split37MoveTask(55, any(), any(), any()) }
        assertTrue(
            journal.lines.toString(),
            journal.lines.any {
                it.contains(
                    "split37 adopt: com.byd.bluetoothcall task=55 -> wide root 102 " +
                        "(already there), displaced pkg.wide"
                )
            },
        )
        coVerifyOrder {
            helper.split37MoveTask(21, 100, null, false)
            helper.split37MoveTask(21, 101, rectOf(narrowLeft), true)
        }
    }

    @Test fun `a BYD app lying fullscreen over the panes is not adopted`() = runTest {
        // The same package the wide root would hand us, but on top of the split it is the camera
        // class: a surface the firmware raised, not an app the user put there.
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.byd.bluetoothcall", 55)
        coEvery { helper.split37TaskArea(55) } returns 4

        val outcome = adoptingEngine().tick(session())

        assertFalse(outcome.pairChanged)
        assertEquals("pkg.wide", outcome.session.pair.widePkg)
        coVerify(exactly = 0) { helper.split37MoveTask(55, any(), any(), any()) }
    }

    @Test fun `the split's own scaffolding is not adopted out of the wide root either`() = runTest {
        stubPanesInPlace()
        coEvery { helper.split37TaskArea(55) } returns 2
        val engine = adoptingEngine()

        for (pkg in listOf(
            // The app grid raises com.byd.sr over the narrow pane on every launch.
            "com.byd.sr", "com.android.launcher3", "com.byd.launchermap", "com.byd.avc",
            "com.byd.cdr", "pkg.narrow", "pkg.wide", ourPkg,
        )) {
            coEvery { helper.getTopTask() } returns topTask(pkg, 55)

            val outcome = engine.tick(session())

            assertFalse(pkg, outcome.pairChanged)
            assertEquals(pkg, "pkg.wide", outcome.session.pair.widePkg)
        }
        assertTrue(journal.lines.none { it.contains("adopt") })
    }

    @Test fun `tick adopts nothing that is not a launchable app of the main screen`() = runTest {
        stubPanesInPlace()
        coEvery { helper.split37TaskArea(55) } returns 4

        // Home, recents and the like: activityType != STANDARD.
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55, activityType = 2)
        assertFalse(adoptingEngine().tick(session()).pairChanged)

        // Somebody else's screen (the cluster projection).
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55, displayId = 2)
        assertFalse(adoptingEngine().tick(session()).pairChanged)

        // A service-only package the user could not have started from a launcher.
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        assertFalse(adoptingEngine(isLauncherApp = { false }).tick(session()).pairChanged)

        // The daemon reports the MRU task: this one is not on screen, so nobody picked it.
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55, visible = false)
        assertFalse(adoptingEngine().tick(session()).pairChanged)

        // A daemon too old to report visibility at all: unknown is not "visible" (fail-safe).
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55, visible = null)
        assertFalse(adoptingEngine().tick(session()).pairChanged)

        // Already in the narrow pane: not ours to take.
        coEvery { helper.split37TaskArea(55) } returns 1
        assertFalse(adoptingEngine().tick(session()).pairChanged)

        coVerify(exactly = 0) { helper.split37MoveTask(55, any(), any(), any()) }
    }

    @Test fun `a foreign app that could not be moved into the pane is not adopted`() = runTest {
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        coEvery { helper.split37TaskArea(55) } returns 4
        coEvery { helper.split37MoveTask(55, 102, rectOf(wideRight)) } returns false

        val outcome = adoptingEngine().tick(session())

        assertFalse(outcome.pairChanged)
        assertEquals("pkg.wide", outcome.session.pair.widePkg)
        assertNull(outcome.session.displacedWidePkg)
        assertTrue(
            journal.lines.any {
                it.contains("split37 adopt: com.foreign task=55 move to wide root 102 failed")
            },
        )
    }

    @Test fun `an adoption the firmware refused is not tried again on the next tick`() = runTest {
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        coEvery { helper.split37TaskArea(55) } returns 4
        coEvery { helper.split37MoveTask(55, 102, rectOf(wideRight)) } returns false
        val engine = adoptingEngine()

        engine.tick(session())
        journal.lines.clear()
        val second = engine.tick(session())

        assertFalse(second.pairChanged)
        coVerify(exactly = 1) { helper.split37MoveTask(55, 102, rectOf(wideRight)) }
        assertTrue("a tick a second says nothing new", journal.lines.none { it.contains("adopt") })
    }

    @Test fun `the tick that adopts an app spends no escape return on its pane`() = runTest {
        stubPanesInPlace()
        coEvery { helper.getTopTask() } returns topTask("com.foreign", 55)
        // The move is asynchronous: the area read of this very tick can still answer "fullscreen".
        coEvery { helper.split37TaskArea(55) } returns 4
        coEvery { helper.getTaskState("com.foreign") } returns task(55)
        val engine = adoptingEngine()

        val adoption = engine.tick(session())

        assertEquals(Split37Engine.PaneState.IN_PANE, adoption.wide)
        coVerify(exactly = 1) { helper.split37MoveTask(55, 102, rectOf(wideRight)) }

        // The budget is untouched: three real escapes are still returned, the fourth gives up.
        var live = adoption.session
        repeat(3) {
            val tick = engine.tick(live)
            assertEquals(Split37Engine.PaneState.RETURNED, tick.wide)
            live = tick.session
        }
        assertEquals(Split37Engine.PaneState.ESCAPED_GAVE_UP, engine.tick(live).wide)
        coVerify(exactly = 4) { helper.split37MoveTask(55, 102, rectOf(wideRight)) }
    }

    @Test fun `the wide pane goes back to the app an adoption displaced`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(21) } returns 1
        coEvery { helper.getTaskState("com.foreign") } returns absent()
        coEvery { helper.getTaskState("pkg.wide") } returns task(20)
        coEvery { helper.split37TaskArea(20) } returns 2

        val outcome = adoptingEngine().tick(adopted())

        assertTrue(outcome.pairChanged)
        assertEquals(Split37Engine.PaneState.IN_PANE, outcome.wide)
        assertEquals("pkg.wide", outcome.session.pair.widePkg)
        assertEquals(20, outcome.session.wideTaskId)
        assertNull(outcome.session.displacedWidePkg)
        assertTrue(
            journal.lines.any { it.contains("split37 adopt: com.foreign gone, wide back to pkg.wide") },
        )
    }

    @Test fun `an adopted app that is gone leaves the pane gone when the displaced one died too`() = runTest {
        coEvery { helper.split37AreaInfo() } returns splitInfo()
        coEvery { helper.getTopTask() } returns null
        coEvery { helper.getTaskState("pkg.narrow") } returns task(21)
        coEvery { helper.split37TaskArea(21) } returns 1
        coEvery { helper.getTaskState("com.foreign") } returns absent()
        coEvery { helper.getTaskState("pkg.wide") } returns absent()

        val outcome = adoptingEngine().tick(adopted())

        assertFalse(outcome.pairChanged)
        assertEquals(Split37Engine.PaneState.GONE, outcome.wide)
        assertEquals("com.foreign", outcome.session.pair.widePkg)
        assertNull("the one-level memory is spent", outcome.session.displacedWidePkg)
    }

    // ── swap() ────────────────────────────────────────────────────────────────

    @Test fun `swap returns the session with the roots' new bounds`() = runTest {
        coEvery { helper.split37Swap() } returns true
        coEvery { helper.split37AreaInfo() } returns splitInfo(narrow = narrowRight, wide = wideLeft)

        val swapped = engine().swap(session())

        assertEquals(narrowRight, swapped?.narrowRoot)
        assertEquals(wideLeft, swapped?.wideRoot)
        assertEquals(21, swapped?.narrowTaskId)
        assertTrue(journal.lines.any { it.contains("split37 swap -> narrow now RIGHT") })
    }

    @Test fun `swap returns null when the firmware refused it`() = runTest {
        coEvery { helper.split37Swap() } returns false

        assertNull(engine().swap(session()))
        coVerify(exactly = 0) { helper.split37AreaInfo() }
    }

    // ── exit() ────────────────────────────────────────────────────────────────

    @Test fun `exit leaves the split through the change-mode verb, touching no task`() = runTest {
        coEvery { helper.split37ChangeMode(102) } returns 2

        assertTrue(engine().exit(session()))

        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
        assertTrue(journal.lines.any { it.contains("split37 exit -> mode 102 area=2") })
    }

    @Test fun `exit accepts a plain fullscreen reading as having left the split`() = runTest {
        coEvery { helper.split37ChangeMode(102) } returns 4

        assertTrue(engine().exit(session()))

        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
        assertTrue(journal.lines.any { it.contains("split37 exit -> mode 102 area=4") })
    }

    @Test fun `exit waits out the change-mode animation instead of calling it a refusal`() = runTest {
        // The verb reads the mode the instant it returns, while the container is still growing.
        coEvery { helper.split37ChangeMode(102) } returns 3
        coEvery { helper.split37AreaInfo() } returnsMany
            listOf(splitInfo(), Split37AreaInfo(2, narrowLeft, wideRight, fullRoot))

        assertTrue(engine().exit(session()))

        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
        assertTrue(journal.lines.any { it.contains("split37 exit -> mode 102 area=2") })
    }

    @Test fun `exit reports a refusal instead of moving the tasks out by hand`() = runTest {
        // The split is still on screen after the settle reads: the hand-move path is exactly what
        // left a pane empty, so a refusal must not fall through to it.
        coEvery { helper.split37ChangeMode(102) } returns 3
        coEvery { helper.split37AreaInfo() } returns splitInfo()

        assertFalse(engine().exit(session()))

        coVerify(exactly = 3) { helper.split37AreaInfo() }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
        assertTrue(journal.lines.any { it.contains("split37 exit -> mode 102 refused, area=3") })
    }

    @Test fun `exit falls back to the full root moves on a daemon without the verb`() = runTest {
        coEvery { helper.split37ChangeMode(102) } returns null
        coEvery { helper.split37MoveTask(any(), any(), any(), any()) } returns true

        assertTrue(engine().exit(session()))

        coVerifyOrder {
            helper.split37MoveTask(21, 100, null)
            helper.split37MoveTask(20, 100, null)
        }
        assertTrue(journal.lines.any { it.contains("split37 exit fallback (no change-mode verb)") })
    }

    @Test fun `exit still moves the wide task after the narrow move failed`() = runTest {
        coEvery { helper.split37ChangeMode(102) } returns null
        coEvery { helper.split37MoveTask(21, 100, null) } returns false
        coEvery { helper.split37MoveTask(20, 100, null) } returns true

        assertFalse(engine().exit(session()))

        coVerify(exactly = 1) { helper.split37MoveTask(20, 100, null) }
        assertTrue(
            journal.lines.any {
                it.contains("split37 exit fallback (no change-mode verb) -> narrow=fail wide=ok")
            },
        )
    }

    @Test fun `exit fails without a full root and moves nothing`() = runTest {
        coEvery { helper.split37ChangeMode(102) } returns null

        assertFalse(engine().exit(session(fullRootId = null)))

        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any(), any()) }
    }
}
