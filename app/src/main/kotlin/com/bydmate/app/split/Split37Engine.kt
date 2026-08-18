package com.bydmate.app.split

import android.graphics.Rect
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.Split37AreaInfo
import com.bydmate.app.data.vehicle.Split37Root
import kotlinx.coroutines.delay

private const val TAG = "Split37Engine"

/** Firmware flag of the "platformized" UI (OTA V1.6): "1" = the native 3:7 split exists. */
private const val PROP_PLATFORMIZED = "ro.build.ui_platformized"

/** Area modes of getScreenAreaInfoForMulti(): 3 = the split is on screen, 4 = plain fullscreen. */
private const val AREA_MODE_SPLIT = 3
private const val AREA_MODE_FULLSCREEN = 4

/**
 * Area ids of [HelperClient.split37TaskArea] — a different enumeration from the area modes above:
 * 1 = narrow pane, 2 = wide pane, 4 = the task is fullscreen, i.e. it escaped the split.
 */
private const val AREA_FULL = 4

/** The main screen. A task on any other display (the cluster projection) is not a pane of ours. */
private const val MAIN_DISPLAY = 0

/** [com.bydmate.app.data.vehicle.SplitTaskState.taskId] value meaning "no task for that package". */
private const val NO_TASK = -1

// Same waiting semantics as SplitSessionManager.confirmPaneTaskIdLocked (#139): a cold app start
// answers "no task" for a while, and the daemon channel can swallow a read under contention.
// Six reads 500 ms apart cover the observed cold-start window; a second silence is terminal.
private const val CONFIRM_TASK_APPEAR_ATTEMPTS = 6
private const val CONFIRM_READ_RETRY_DELAY_MS = 500L

// The enter verb answers with the area mode read the instant it returns, while the firmware is
// still raising its panes; these re-reads cover that animation before a start is called a failure.
private const val ENTER_SETTLE_READS = 3
private const val ENTER_SETTLE_DELAY_MS = 400L

/** How many times a pane may be dragged back out of fullscreen inside [ESCAPE_WINDOW_MS]. */
private const val ESCAPE_RETURN_LIMIT = 3
private const val ESCAPE_WINDOW_MS = 30_000L

/**
 * Drives the vehicle's OWN 3:7 split on "platformized" firmware (OTA V1.6, DiLink 5.1), where our
 * freeform layout cannot run: `enterSplitMode` raises the firmware's two roots and tasks are
 * reparented into them, so the panes are the firmware's, with its own divider and geometry.
 *
 * The engine only places a pair into those roots, drags an escaped app back, swaps the sides and
 * hands both tasks back to the fullscreen root. Everything around a session — verdicts, prefs, the
 * pill, the backdrop, force-stops — stays with SplitSessionManager; the engine has no Context and
 * talks to nothing but the helper daemon.
 *
 * Live probe on the car 2026-08-18 (`docs/research/2026-08-18-split-screen-ota-v16-ui7.md` §9):
 * a foreign app started by [HelperClient.launchApp] comes up fullscreen and only lands in a pane
 * after the move; a new Activity inside its task can throw it back to fullscreen while the panes
 * survive underneath, which is exactly what [tick] repairs.
 *
 * Every failure is a journal line, not an exception — a field dump is the only diagnostic channel
 * we have on someone else's car.
 */
class Split37Engine(
    private val helper: HelperClient,
    private val journal: SplitJournal = NoSplitJournal,
    /** Reads a system property; null when it is unset or unreadable. Injectable for tests. */
    private val systemProperty: (String) -> String? = ::readSystemProperty,
    /** Monotonic time source in milliseconds; injectable for tests. */
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    /** Timestamps of the returns already spent per package, inside the sliding escape window. */
    private val returnTimes = mutableMapOf<String, MutableList<Long>>()

    /** Packages whose give-up line is already in the journal for the current window. */
    private val gaveUp = mutableSetOf<String>()

    /** Last (taskId, displayId) already journalled per package, so a tick does not repeat itself. */
    private val foreignDisplay = mutableMapOf<String, Pair<Int, Int>>()

    private val platformized: Boolean by lazy { isPlatformizedFirmware(systemProperty) }

    /** True only on firmware that carries the native 3:7 split surface. */
    fun isApplicable(): Boolean = platformized

    /** A live pair standing in the firmware's panes; SplitSessionManager keeps it in its state. */
    data class Session(
        val pair: SplitPair,
        val narrowTaskId: Int,
        val wideTaskId: Int,
        val narrowRoot: Split37Root,
        val wideRoot: Split37Root,
        /** null when the firmware reported no fullscreen root — [exit] then has nowhere to move to. */
        val fullRootId: Int?,
    )

    sealed class PlaceOutcome {
        data class Placed(val session: Session) : PlaceOutcome()

        /** The daemon did not answer the enter verb: too old, or firmware without the split. */
        object Unavailable : PlaceOutcome()

        data class Failed(val reason: String) : PlaceOutcome()
    }

    /** Raises the native split and puts [pair] into its two panes. */
    suspend fun place(pair: SplitPair): PlaceOutcome {
        // A fresh session must not inherit the escape budget of the previous one.
        returnTimes.clear()
        gaveUp.clear()
        foreignDisplay.clear()

        // A split that already stands must not be entered again — the verb toggles the firmware's
        // own surface. A silent area read is NOT a verdict (null covers both a dead channel and a
        // non-OK status), so it falls through to the enter verb exactly as before.
        val standing = helper.split37AreaInfo()
        var info = if (standing != null && standing.areaMode == AREA_MODE_SPLIT) {
            journal.append("split37 enter skipped: split already on screen")
            standing
        } else {
            val enter = helper.split37Enter()
            if (enter == null) {
                journal.append("split37 enter -> no reply (daemon outdated or no split surface)")
                return PlaceOutcome.Unavailable
            }
            if (enter == AREA_MODE_SPLIT) {
                helper.split37AreaInfo() ?: return failed("area info -> no reply")
            } else {
                val settled = awaitSplitMode()
                if (settled == null || settled.areaMode != AREA_MODE_SPLIT) {
                    return failed("enter areaMode=$enter, settled=${settled?.areaMode}")
                }
                settled
            }
        }
        var narrowRoot = info.narrow ?: return failed("area info: no narrow root")
        var wideRoot = info.wide ?: return failed("area info: no wide root")

        val narrowSide = if (narrowRoot.left < wideRoot.left) SplitSide.LEFT else SplitSide.RIGHT
        val swapNeeded = narrowSide != pair.narrowSide
        journal.append(
            "split37 side: narrow=$narrowSide wanted=${pair.narrowSide} swap=${if (swapNeeded) "yes" else "no"}"
        )
        if (swapNeeded) {
            if (!helper.split37Swap()) return failed("swap failed")
            // The roots keep their ids but not their bounds — everything below must use the new ones.
            info = helper.split37AreaInfo() ?: return failed("area info after swap -> no reply")
            narrowRoot = info.narrow ?: return failed("area info after swap: no narrow root")
            wideRoot = info.wide ?: return failed("area info after swap: no wide root")
        }

        // Wide first: it is the pane the user is looking at, and the narrow one settles on top of a
        // layout that is already final.
        val wideTaskId = when (val wide = placePane("wide", pair.widePkg, wideRoot)) {
            is PaneResult.Ok -> wide.taskId
            is PaneResult.Fail -> return failed(wide.reason)
        }
        val narrowTaskId = when (val narrow = placePane("narrow", pair.narrowPkg, narrowRoot)) {
            is PaneResult.Ok -> narrow.taskId
            is PaneResult.Fail -> return failed(narrow.reason)
        }

        return PlaceOutcome.Placed(
            Session(pair, narrowTaskId, wideTaskId, narrowRoot, wideRoot, info.full?.rootTaskId)
        )
    }

    enum class PaneState { IN_PANE, RETURNED, ESCAPED_GAVE_UP, GONE, UNKNOWN }

    data class TickOutcome(
        val sessionEnded: Boolean,
        val narrow: PaneState,
        val wide: PaneState,
        val session: Session,
    )

    /**
     * One watchdog pass: notices a split the user has closed, drags escaped apps back into their
     * pane and adopts a task id the app has recreated behind our back.
     */
    suspend fun tick(session: Session): TickOutcome {
        val info = helper.split37AreaInfo()
        if (info != null && info.areaMode == AREA_MODE_FULLSCREEN) {
            // Not our doing: the firmware's own divider ends the split, and reviving it here would
            // fight the user.
            journal.append("split37 tick: area mode 4 - split closed")
            return TickOutcome(true, PaneState.UNKNOWN, PaneState.UNKNOWN, session)
        }
        // A silent area read says nothing about the session, so it is not an end.

        // The user drags the firmware's own divider, so a return must land in today's bounds and
        // not in the ones the placement saw. A half-read (one root only) is left untouched.
        val freshNarrow = info?.narrow
        val freshWide = info?.wide
        val current = if (freshNarrow != null && freshWide != null) {
            session.copy(narrowRoot = freshNarrow, wideRoot = freshWide)
        } else {
            session
        }

        val narrow = tickPane(current.pair.narrowPkg, current.narrowTaskId, current.narrowRoot)
        val wide = tickPane(current.pair.widePkg, current.wideTaskId, current.wideRoot)
        val updated = current.copy(narrowTaskId = narrow.taskId, wideTaskId = wide.taskId)
        return TickOutcome(false, narrow.state, wide.state, updated)
    }

    /** Swaps the two sides, returning the session with the roots' new bounds; null on failure. */
    suspend fun swap(session: Session): Session? {
        if (!helper.split37Swap()) {
            journal.append("split37 swap failed")
            return null
        }
        val info = helper.split37AreaInfo()
        val narrow = info?.narrow
        val wide = info?.wide
        if (narrow == null || wide == null) {
            journal.append("split37 swap: no pane geometry after the swap")
            return null
        }
        val side = if (narrow.left < wide.left) SplitSide.LEFT else SplitSide.RIGHT
        journal.append("split37 swap -> narrow now $side")
        return session.copy(narrowRoot = narrow, wideRoot = wide)
    }

    /**
     * Ends the session by handing both tasks to the fullscreen root, without a resize — the
     * firmware sizes them itself. Wide goes last so it is the one left on top.
     */
    suspend fun exit(session: Session): Boolean {
        val fullRootId = session.fullRootId
        if (fullRootId == null) {
            journal.append("split37 exit: no full root - cannot leave the split")
            return false
        }
        val narrowOk = helper.split37MoveTask(session.narrowTaskId, fullRootId, null)
        // The wide move runs even after a failed narrow one: leaving one task in a pane and the
        // other in fullscreen is worse than trying both.
        val wideOk = helper.split37MoveTask(session.wideTaskId, fullRootId, null)
        journal.append("split37 exit -> narrow=${okText(narrowOk)} wide=${okText(wideOk)}")
        return narrowOk && wideOk
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private sealed class PaneResult {
        data class Ok(val taskId: Int) : PaneResult()
        data class Fail(val reason: String) : PaneResult()
    }

    private class PaneTick(val state: PaneState, val taskId: Int)

    /** Launches [pkg] if it is not running yet and reparents its task into [root]. */
    private suspend fun placePane(pane: String, pkg: String, root: Split37Root): PaneResult {
        val running = helper.getTaskState(pkg)?.takeIf { it.taskId != NO_TASK }?.takeIf { state ->
            val ours = state.displayId == MAIN_DISPLAY
            if (!ours) {
                // The daemon answers with the first task of the package on ANY display, and the
                // cluster projection keeps one there; adopting it would move somebody else's window.
                journal.append(
                    "split37 place: $pkg task=${state.taskId} lives on display " +
                        "${state.displayId} - not ours, launching"
                )
            }
            ours
        }
        val taskId = if (running != null) {
            running.taskId
        } else {
            if (!helper.launchApp(pkg)) return PaneResult.Fail("launch $pkg failed")
            when (val awaited = awaitTaskId(pane, pkg)) {
                is PaneResult.Ok -> awaited.taskId
                is PaneResult.Fail -> return awaited
            }
        }
        journal.append(
            "split37 place $pane $pkg task=$taskId -> root=${root.rootTaskId} " +
                "[${root.left},${root.top},${root.right},${root.bottom}] " +
                "launched=${if (running == null) "yes" else "no"}"
        )
        val moved = helper.split37MoveTask(
            taskId,
            root.rootTaskId,
            Rect(root.left, root.top, root.right, root.bottom),
        )
        if (!moved) return PaneResult.Fail("move $pkg -> root ${root.rootTaskId} failed")
        return PaneResult.Ok(taskId)
    }

    /**
     * Waits for the task of a just-launched [pkg], with the semantics of
     * SplitSessionManager.confirmPaneTaskIdLocked: `taskId == -1` is "not yet" and is polled,
     * a null is a silent channel and buys exactly one retry.
     */
    private suspend fun awaitTaskId(pane: String, pkg: String): PaneResult {
        var reads = 0
        var silenceRetried = false
        var lastAbsent = false
        var foreignDisplaySeen: Int? = null
        while (reads < CONFIRM_TASK_APPEAR_ATTEMPTS) {
            if (reads > 0) delay(CONFIRM_READ_RETRY_DELAY_MS)
            val state = helper.getTaskState(pkg)
            reads++
            if (state == null) {
                if (silenceRetried) {
                    val seen = if (lastAbsent) ", last answer task=-1" else ""
                    return PaneResult.Fail("no task state reply for $pane $pkg (2 silences in $reads reads$seen)")
                }
                silenceRetried = true
                continue
            }
            if (state.taskId != NO_TASK && state.displayId == MAIN_DISPLAY) {
                return PaneResult.Ok(state.taskId)
            }
            // A task on another display is the cluster projection's, not the pane we just launched.
            if (state.taskId != NO_TASK) foreignDisplaySeen = state.displayId
            lastAbsent = true
        }
        val where = foreignDisplaySeen?.let { ", last seen on display $it" } ?: ""
        return PaneResult.Fail("no task after launch: $pane $pkg (waited $reads reads$where)")
    }

    /** Resolves the state of one pane and returns its app to the pane when it escaped. */
    private suspend fun tickPane(pkg: String, knownTaskId: Int, root: Split37Root): PaneTick {
        val state = helper.getTaskState(pkg) ?: return PaneTick(PaneState.UNKNOWN, knownTaskId)
        if (state.taskId == NO_TASK) return PaneTick(PaneState.GONE, knownTaskId)
        if (state.displayId != MAIN_DISPLAY) {
            // Somebody else's screen (the cluster projection): not a pane of ours to repair, and its
            // area reading would be about a window the firmware never put in a root.
            noteForeignDisplay(pkg, state.taskId, state.displayId)
            return PaneTick(PaneState.UNKNOWN, knownTaskId)
        }
        foreignDisplay.remove(pkg)
        var taskId = knownTaskId
        if (state.taskId != knownTaskId) {
            // The app recreated its task (a new Activity, a restart); the pane is the new task now.
            journal.append("split37 tick: $pkg task changed $knownTaskId->${state.taskId}")
            taskId = state.taskId
        }
        // Only fullscreen matters: which of the two panes holds the task is the user's business.
        val area = helper.split37TaskArea(taskId) ?: return PaneTick(PaneState.UNKNOWN, taskId)
        if (area != AREA_FULL) return PaneTick(PaneState.IN_PANE, taskId)

        if (!allowReturn(pkg)) return PaneTick(PaneState.ESCAPED_GAVE_UP, taskId)
        val moved = helper.split37MoveTask(
            taskId,
            root.rootTaskId,
            Rect(root.left, root.top, root.right, root.bottom),
        )
        if (!moved) {
            journal.append("split37 tick: $pkg return to root ${root.rootTaskId} failed")
            return PaneTick(PaneState.UNKNOWN, taskId)
        }
        journal.append("split37 tick: $pkg escaped to fullscreen - returned to root ${root.rootTaskId}")
        return PaneTick(PaneState.RETURNED, taskId)
    }

    /**
     * Re-reads the area info while the firmware may still be raising its panes. Returns the last
     * read (null when the channel stayed silent through all of them).
     */
    private suspend fun awaitSplitMode(): Split37AreaInfo? {
        var last: Split37AreaInfo? = null
        repeat(ENTER_SETTLE_READS) {
            delay(ENTER_SETTLE_DELAY_MS)
            last = helper.split37AreaInfo()
            if (last?.areaMode == AREA_MODE_SPLIT) return last
        }
        return last
    }

    /** Journals a pane task found on a foreign display once, not on every tick. */
    private fun noteForeignDisplay(pkg: String, taskId: Int, displayId: Int) {
        val seen = taskId to displayId
        if (foreignDisplay.put(pkg, seen) == seen) return
        journal.append("split37 tick: $pkg task=$taskId lives on display $displayId - left alone")
    }

    /**
     * Spends one return from [pkg]'s budget. An app that keeps throwing itself back to fullscreen
     * is doing it on purpose (a video player, a settings screen) and a watchdog that keeps dragging
     * it back turns into a fight the user cannot win.
     */
    private fun allowReturn(pkg: String): Boolean {
        val now = nowMs()
        val times = returnTimes.getOrPut(pkg) { mutableListOf() }
        times.removeAll { now - it >= ESCAPE_WINDOW_MS }
        if (times.size >= ESCAPE_RETURN_LIMIT) {
            if (gaveUp.add(pkg)) {
                journal.append(
                    "split37 tick: $pkg escaped ${ESCAPE_RETURN_LIMIT + 1}x in " +
                        "${ESCAPE_WINDOW_MS / 1000}s - giving up returns"
                )
            }
            return false
        }
        times += now
        gaveUp.remove(pkg)
        return true
    }

    private fun failed(reason: String): PlaceOutcome.Failed {
        Log.w(TAG, "split37 place failed: $reason")
        journal.append("split37 place failed: $reason")
        return PlaceOutcome.Failed(reason)
    }

    private fun okText(ok: Boolean): String = if (ok) "ok" else "fail"

    companion object {
        /**
         * The one gate of the native 3:7 mechanism, shared with the settings screen so the UI
         * cannot describe a split the engine would not run.
         */
        fun isPlatformizedFirmware(
            systemProperty: (String) -> String? = ::readSystemProperty,
        ): Boolean = systemProperty(PROP_PLATFORMIZED)?.trim() == "1"
    }
}
