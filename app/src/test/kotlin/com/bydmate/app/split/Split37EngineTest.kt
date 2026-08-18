package com.bydmate.app.split

import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.Split37AreaInfo
import com.bydmate.app.data.vehicle.Split37Root
import com.bydmate.app.data.vehicle.SplitTaskState
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

    private val helper = mockk<HelperClient>(relaxed = true)
    private val journal = Split37RecordingJournal()
    private var now = 1_000L

    private fun engine(platformized: String? = "1") = Split37Engine(
        helper = helper,
        journal = journal,
        systemProperty = { name -> platformized.takeIf { name == "ro.build.ui_platformized" } },
        nowMs = { now },
    )

    private fun splitInfo(narrow: Split37Root? = narrowLeft, wide: Split37Root? = wideRight) =
        Split37AreaInfo(3, narrow, wide, fullRoot)

    private fun absent() = SplitTaskState(-1, 0, 0, 0, 0, 0)
    private fun task(id: Int) = SplitTaskState(id, 1, 0, 0, 0, 0)
    /** Same package, but its task stands on the cluster projection display. */
    private fun clusterTask(id: Int) = SplitTaskState(id, 1, 0, 0, 0, 0, displayId = 2)
    private fun fullscreenInfo() = Split37AreaInfo(4, narrowLeft, wideRight, fullRoot)

    private fun rectOf(root: Split37Root) = Rect(root.left, root.top, root.right, root.bottom)

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

    @Test fun `place reports Unavailable when the daemon does not answer the enter verb`() = runTest {
        // A silent area read says nothing (transport or non-OK status alike), so the verdict is
        // the enter verb's: no reply there = the mechanism is not available through this daemon.
        coEvery { helper.split37AreaInfo() } returns null
        coEvery { helper.split37Enter() } returns null

        val outcome = engine().place(SplitPair("pkg.narrow", "pkg.wide", SplitSide.LEFT))

        assertEquals(Split37Engine.PlaceOutcome.Unavailable, outcome)
        coVerify(exactly = 0) { helper.launchApp(any()) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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
        coEvery { helper.split37AreaInfo() } returns Split37AreaInfo(4, narrowLeft, wideRight, fullRoot)

        val outcome = engine().tick(session())

        assertTrue(outcome.sessionEnded)
        assertEquals(Split37Engine.PaneState.UNKNOWN, outcome.narrow)
        assertEquals(Split37Engine.PaneState.UNKNOWN, outcome.wide)
        coVerify(exactly = 0) { helper.getTaskState(any()) }
        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
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

    @Test fun `exit hands both tasks to the full root without a resize, narrow first`() = runTest {
        coEvery { helper.split37MoveTask(any(), any(), any()) } returns true

        assertTrue(engine().exit(session()))

        coVerifyOrder {
            helper.split37MoveTask(21, 100, null)
            helper.split37MoveTask(20, 100, null)
        }
    }

    @Test fun `exit still moves the wide task after the narrow move failed`() = runTest {
        coEvery { helper.split37MoveTask(21, 100, null) } returns false
        coEvery { helper.split37MoveTask(20, 100, null) } returns true

        assertFalse(engine().exit(session()))

        coVerify(exactly = 1) { helper.split37MoveTask(20, 100, null) }
        assertTrue(journal.lines.any { it.contains("split37 exit -> narrow=fail wide=ok") })
    }

    @Test fun `exit fails without a full root and moves nothing`() = runTest {
        assertFalse(engine().exit(session(fullRootId = null)))

        coVerify(exactly = 0) { helper.split37MoveTask(any(), any(), any()) }
    }
}
