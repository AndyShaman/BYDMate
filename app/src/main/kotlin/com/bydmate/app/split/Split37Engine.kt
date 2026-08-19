package com.bydmate.app.split

import android.graphics.Rect
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.Split37AreaInfo
import com.bydmate.app.data.vehicle.Split37Root
import com.bydmate.app.data.vehicle.TopTaskInfo
import kotlinx.coroutines.delay

private const val TAG = "Split37Engine"

/** Firmware flag of the "platformized" UI (OTA V1.6): "1" = the native 3:7 split exists. */
private const val PROP_PLATFORMIZED = "ro.build.ui_platformized"

/**
 * Area modes of getScreenAreaInfoForMulti(): 1 = only the narrow container on screen, 2 = only the
 * wide one (the user pulled the divider to the edge, or [exit] did), 3 = the split is on screen,
 * 4 = plain fullscreen.
 */
private const val AREA_MODE_WIDE_ONLY = 2
private const val AREA_MODE_SPLIT = 3
private const val AREA_MODE_FULLSCREEN = 4

/** changeSplitScreenMode() argument that hands the whole screen to the wide (second) container. */
private const val CHANGE_MODE_WIDE_FULLSCREEN = 102

/**
 * Area ids of [HelperClient.split37TaskArea] — a different enumeration from the area modes above:
 * 1 = narrow pane, 2 = wide pane, 4 = the task is fullscreen, i.e. it escaped the split.
 */
private const val AREA_NARROW = 1
private const val AREA_WIDE = 2
private const val AREA_FULL = 4

/** The main screen. A task on any other display (the cluster projection) is not a pane of ours. */
private const val MAIN_DISPLAY = 0

/** WindowConfiguration.ACTIVITY_TYPE_STANDARD: an ordinary app, not home, recents or assistant. */
private const val ACTIVITY_TYPE_STANDARD = 1

/**
 * Firmware surfaces that come and go over the panes on their own (the vehicle widget, the two
 * launchers, the reversing camera, the recorder). Seeing one on top is not a user picking an app,
 * so none of them is ever adopted, whichever root it stands in: opening the app grid raises
 * com.byd.sr inside the narrow root itself (on-car 2026-08-19). Over a standing split the rest of
 * the firmware class is kept out by [isSystemPkg] on top of this list — the rear camera comes up
 * as an ordinary fullscreen STANDARD activity when the user selects reverse.
 */
private val ADOPT_EXCLUDED = setOf(
    "com.byd.sr", "com.android.launcher3", "com.byd.launchermap", "com.byd.avc", "com.byd.cdr",
)

/**
 * True for the firmware's own packages. One of them lying fullscreen over the panes is a surface
 * the firmware raised, not an app the user picked; a whitelisted BYD app the firmware does mean
 * for a pane arrives in the wide root instead (startSplitWindow), where this gate does not apply.
 */
private fun isSystemPkg(pkg: String): Boolean =
    pkg.startsWith("com.byd.") || pkg.startsWith("com.android.")

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
 * The engine only places a pair into those roots, drags an escaped app back, adopts the foreign
 * app the firmware throws over the panes, swaps the sides and ends the split by handing the whole
 * screen to the wide container (changeSplitScreenMode, [CHANGE_MODE_WIDE_FULLSCREEN]); moving the
 * tasks into the fullscreen root by hand is only the fallback for a daemon that lacks the verb.
 * Everything around a session — verdicts, prefs, the pill, the backdrop, force-stops — stays with
 * SplitSessionManager; the engine has no Context and talks to nothing but the helper daemon.
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
    /**
     * True when [pkg] is an app the user can start from a launcher — the only kind [tick] adopts
     * into a pane. The default adopts nothing, so a caller that does not wire it keeps the pair it
     * placed.
     */
    private val isLauncherApp: (String) -> Boolean = { false },
    /** Our own package: BYDMate's own windows over the panes are not a pane app of the pair. */
    private val ownPackage: String = "",
) {

    /** Timestamps of the returns already spent per package, inside the sliding escape window. */
    private val returnTimes = mutableMapOf<String, MutableList<Long>>()

    /** Packages whose give-up line is already in the journal for the current window. */
    private val gaveUp = mutableSetOf<String>()

    /**
     * (package, taskId) pairs whose move into the wide root failed once. The firmware refuses some
     * tasks for good, and a tick runs every second: without this the same failure line would fill
     * the journal and the same doomed move would be retried forever.
     */
    private val adoptFailed = mutableSetOf<Pair<String, Int>>()

    /** Last (taskId, displayId) already journalled per package, so a tick does not repeat itself. */
    private val foreignDisplay = mutableMapOf<String, Pair<Int, Int>>()

    /**
     * The package of the fullscreen lid currently covering the standing panes, so the line about
     * it goes into the journal once per episode instead of once a second.
     */
    private var coveredBy: String? = null

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
        /** The wide app an adoption pushed aside, remembered one level deep for the rollback. */
        val displacedWidePkg: String? = null,
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
        adoptFailed.clear()
        foreignDisplay.clear()
        coveredBy = null

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
                val settled = awaitAreaMode { it == AREA_MODE_SPLIT }
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

        val fullRootId = info.full?.rootTaskId
        // Wide first: it is the pane the user is looking at, and the narrow one settles on top of a
        // layout that is already final.
        val wideTaskId = when (
            val wide = placePane("wide", pair.widePkg, wideRoot, AREA_WIDE, fullRootId)
        ) {
            is PaneResult.Ok -> wide.taskId
            is PaneResult.Fail -> return failed(wide.reason)
        }
        val narrowTaskId = when (
            val narrow = placePane("narrow", pair.narrowPkg, narrowRoot, AREA_NARROW, fullRootId)
        ) {
            is PaneResult.Ok -> narrow.taskId
            is PaneResult.Fail -> return failed(narrow.reason)
        }

        return PlaceOutcome.Placed(
            Session(pair, narrowTaskId, wideTaskId, narrowRoot, wideRoot, fullRootId)
        )
    }

    enum class PaneState { IN_PANE, RETURNED, ESCAPED_GAVE_UP, GONE, UNKNOWN }

    data class TickOutcome(
        val sessionEnded: Boolean,
        val narrow: PaneState,
        val wide: PaneState,
        val session: Session,
        /** The session stands on a different pair than it did before this tick (an adoption). */
        val pairChanged: Boolean = false,
    )

    /**
     * One watchdog pass: notices a split the user has closed, drags escaped apps back into their
     * pane, adopts a task id the app has recreated behind our back and takes a foreign app the
     * firmware threw over the panes into the wide one.
     *
     * Area mode 4 alone is not the end: the firmware reads 4 both when it closed the split and
     * when a fullscreen task lies over panes that are still standing — a foreign app the firmware
     * started from its own grid (adopted into the wide pane, which brings the split back by
     * itself), a pane app of ours that escaped (dragged back by [tickPane]), or a plain lid the
     * user will close again (our own window, the reversing camera, a settings screen), under which
     * the firmware shows the split once more. The only honest test of a closed split is where our
     * pane tasks live: while either of them is still in a pane root, the session stands. Modes 1
     * and 2 never end it either: the user pulled the firmware's slider to an edge, so one
     * container fills the screen while the pair is still standing behind it.
     */
    suspend fun tick(session: Session): TickOutcome {
        val info = helper.split37AreaInfo()
        // A silent area read says nothing about the session, so it is not an end.
        val fullscreen = info != null && info.areaMode == AREA_MODE_FULLSCREEN
        // A reading of any other mode ends the lid episode; a silent one is no reading at all.
        if (info != null && !fullscreen) coveredBy = null
        // One read of the window on top per tick: the adoption, the escape check and the line
        // about a lid all ask about the very same task.
        val top = helper.getTopTask()

        // The user drags the firmware's own divider, so a return must land in today's bounds and
        // not in the ones the placement saw. A half-read (one root only) is left untouched.
        val freshNarrow = info?.narrow
        val freshWide = info?.wide
        val current = if (freshNarrow != null && freshWide != null) {
            session.copy(narrowRoot = freshNarrow, wideRoot = freshWide)
        } else {
            session
        }

        // Runs before the panes are resolved, so the rest of the tick already works on the pair
        // the adopted app belongs to.
        val adopted = adoptForeignTop(current, top, fullscreen)
        if (fullscreen && adopted == null && !ownPaneOnTop(current, top)) {
            if (panesStillInRoots(current)) {
                noteCovered(top?.pkg)
            } else {
                // The pane tasks have left their roots: the split is gone for good, and reviving
                // it here would fight the user.
                journal.append("split37 tick: area mode 4 - split closed")
                return TickOutcome(true, PaneState.UNKNOWN, PaneState.UNKNOWN, session)
            }
        }
        val active = adopted ?: current

        val narrow = tickPane(active.pair.narrowPkg, active.narrowTaskId, active.narrowRoot)
        // `am stack move-task` is asynchronous: on the tick that adopted an app the area read can
        // still answer "fullscreen", which tickPane would spend an escape return on. The pane is
        // known to be in place — it was just put there.
        val wide = if (adopted != null) {
            PaneTick(PaneState.IN_PANE, active.wideTaskId)
        } else {
            tickPane(active.pair.widePkg, active.wideTaskId, active.wideRoot)
        }
        var updated = active.copy(narrowTaskId = narrow.taskId, wideTaskId = wide.taskId)
        var wideState = wide.state
        var pairChanged = adopted != null
        if (wideState == PaneState.GONE && updated.displacedWidePkg != null) {
            val rolledBack = rollbackAdoption(updated)
            if (rolledBack != null) {
                updated = rolledBack
                wideState = PaneState.IN_PANE
                pairChanged = true
            } else {
                // The app that was pushed aside is not there to go back to; the memory is spent.
                updated = updated.copy(displacedWidePkg = null)
            }
        }
        return TickOutcome(false, narrow.state, wideState, updated, pairChanged)
    }

    /**
     * Takes the app the firmware started over the standing panes into the wide pane, making it the
     * wide app of the pair. On UI7 the native "Application" grid throws anything outside its own
     * whitelist fullscreen on top of the split and puts a whitelisted BYD app straight into the
     * wide root itself, and there is no way to change a pane's app by hand — so either way the app
     * the user just picked becomes a pane instead of a lid.
     *
     * Returns null when there is nothing to adopt (the ordinary case, every tick). The escape
     * budget of [allowReturn] is deliberately untouched: this is not an app fighting its pane, it
     * is a new pane app, and its later escapes are counted like anybody else's.
     *
     * [top] is the window on top read once by [tick]. [splitGone] tells the two screens apart in
     * the journal: with it the whole split was off the screen (area mode 4) and the move is what
     * brought it back.
     */
    private suspend fun adoptForeignTop(
        session: Session,
        top: TopTaskInfo?,
        splitGone: Boolean,
    ): Session? {
        if (top == null) return null
        if (top.displayId != MAIN_DISPLAY || top.activityType != ACTIVITY_TYPE_STANDARD) return null
        // The daemon answers with the MRU task, which is not necessarily the one on screen. Only a
        // window the user is actually looking at is an app they just picked; an unknown visibility
        // (a daemon too old to report it) is treated as "not visible" so nothing is adopted blindly.
        if (top.visible != true) return null
        val pair = session.pair
        if (top.pkg == pair.narrowPkg || top.pkg == pair.widePkg || top.pkg == ownPackage) return null
        // The split's own scaffolding is not a pane app in either root.
        if (top.pkg in ADOPT_EXCLUDED) return null
        if ((top.pkg to top.taskId) in adoptFailed) return null

        val root = session.wideRoot
        val placement = when (helper.split37TaskArea(top.taskId)) {
            AREA_FULL -> {
                // Fullscreen is also how the firmware's own surfaces come up, so from there only
                // an app the user could have started from a launcher is taken.
                if (isSystemPkg(top.pkg) || !isLauncherApp(top.pkg)) return null
                val moved = helper.split37MoveTask(
                    top.taskId,
                    root.rootTaskId,
                    Rect(root.left, root.top, root.right, root.bottom),
                )
                if (!moved) {
                    adoptFailed += top.pkg to top.taskId
                    journal.append(
                        "split37 adopt: ${top.pkg} task=${top.taskId} move to wide root " +
                            "${root.rootTaskId} failed"
                    )
                    return null
                }
                // The move out of fullscreen is also what puts the firmware's split back on the
                // screen (on-car 2026-08-19), and it takes a moment: until the area reads 3 again
                // the app is not standing in a pane and there is nothing to adopt it into.
                val back = awaitAreaMode { it == AREA_MODE_SPLIT }
                if (back?.areaMode != AREA_MODE_SPLIT) {
                    adoptFailed += top.pkg to top.taskId
                    journal.append(
                        "split37 adopt: ${top.pkg} task=${top.taskId} moved to wide root " +
                            "${root.rootTaskId} but split did not come back (area=${back?.areaMode})"
                    )
                    return null
                }
                if (splitGone) "moved, split back" else "moved"
            }
            // The firmware put it in the wide root itself (startSplitWindow, how it starts the BYD
            // apps of its own whitelist): it has already decided this is a pane app, so neither the
            // system-package nor the launcher gate applies here, and there is nothing to move.
            AREA_WIDE -> "already there"
            // The narrow pane, an unreadable area: not ours to take.
            else -> return null
        }
        journal.append(
            "split37 adopt: ${top.pkg} task=${top.taskId} -> wide root ${root.rootTaskId} " +
                "($placement), displaced ${pair.widePkg}"
        )
        bounceNarrow(session)
        return session.copy(
            pair = pair.copy(widePkg = top.pkg),
            wideTaskId = top.taskId,
            displacedWidePkg = pair.widePkg,
        )
    }

    /**
     * True when the fullscreen task over the panes is a pane app of the pair: it escaped its pane
     * and [tickPane] drags it back, so a split that reads "gone" is still ours to keep.
     */
    private fun ownPaneOnTop(session: Session, top: TopTaskInfo?): Boolean {
        if (top == null || top.visible != true) return false
        return top.pkg == session.pair.narrowPkg || top.pkg == session.pair.widePkg
    }

    /**
     * True while either pane task is still in a pane root. The firmware answers area mode 4 for
     * every fullscreen window over the split, so this is what tells a lid the user will close
     * (BYDMate itself, the reversing camera, a settings screen — the split comes back underneath)
     * from a split the firmware really took down.
     */
    private suspend fun panesStillInRoots(session: Session): Boolean =
        inAPane(helper.split37TaskArea(session.narrowTaskId)) ||
            inAPane(helper.split37TaskArea(session.wideTaskId))

    private fun inAPane(area: Int?): Boolean = area == AREA_NARROW || area == AREA_WIDE

    /** Journals the lid over the panes once per episode, not on every tick under it. */
    private fun noteCovered(topPkg: String?) {
        val by = topPkg ?: "unknown"
        if (coveredBy == by) return
        coveredBy = by
        journal.append("split37 tick: area mode 4 - covered by $by, panes standing")
    }

    /**
     * Re-raises the narrow pane's task after an adoption. Opening the firmware's own app grid
     * raises com.byd.sr over our task inside the narrow root (on-car 2026-08-19), and it stays
     * there once the split is back, because a move into the root a task already lives in does not
     * reorder it — so the task goes out to the fullscreen root without being raised and comes
     * back on top. Not load-bearing: a failed bounce only leaves the firmware's surface over the
     * pane, and a move that lands halfway is repaired by the next tick's return.
     */
    private suspend fun bounceNarrow(session: Session) {
        val fullRootId = session.fullRootId ?: return
        val root = session.narrowRoot
        val out = helper.split37MoveTask(session.narrowTaskId, fullRootId, null, toTop = false)
        val back = out && helper.split37MoveTask(
            session.narrowTaskId,
            root.rootTaskId,
            Rect(root.left, root.top, root.right, root.bottom),
        )
        journal.append(
            "split37 adopt: narrow ${session.pair.narrowPkg} task=${session.narrowTaskId} " +
                if (back) "re-raised" else "re-raise failed"
        )
    }

    /**
     * Gives the wide pane back to the app an adoption pushed aside, once the adopted one is gone
     * and the old one is still standing in that very pane underneath it. Null when there is
     * nothing to go back to — the caller then reports the pane as gone, as it did before.
     */
    private suspend fun rollbackAdoption(session: Session): Session? {
        val displaced = session.displacedWidePkg ?: return null
        val state = helper.getTaskState(displaced) ?: return null
        if (state.taskId == NO_TASK || state.displayId != MAIN_DISPLAY) return null
        if (helper.split37TaskArea(state.taskId) != AREA_WIDE) return null
        journal.append("split37 adopt: ${session.pair.widePkg} gone, wide back to $displaced")
        return session.copy(
            pair = session.pair.copy(widePkg = displaced),
            wideTaskId = state.taskId,
            displacedWidePkg = null,
        )
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
     * Ends the session by telling the firmware to give the whole screen to the wide container
     * ([CHANGE_MODE_WIDE_FULLSCREEN]): the split leaves the screen and the wide app of the pair
     * stays on it, with the firmware's own slider handle at the edge. Moving the two tasks out by
     * hand does NOT leave the split (field dump 2026-08-19: the next start read "split already on
     * screen" while one pane stood empty), so that path is only the fallback for an old daemon.
     */
    suspend fun exit(session: Session): Boolean {
        val answered = helper.split37ChangeMode(CHANGE_MODE_WIDE_FULLSCREEN)
            ?: return exitByMove(session)
        // The verb reads the mode the instant it returns, while the firmware is still animating the
        // container to full width — the same settle window the enter path needs.
        val areaMode = if (leftTheSplit(answered)) {
            answered
        } else {
            awaitAreaMode(::leftTheSplit)?.areaMode ?: answered
        }
        if (!leftTheSplit(areaMode)) {
            // The firmware answered but stayed in the split; the hand-move fallback is exactly
            // what leaves a pane empty, so a refusal is reported instead of papered over.
            journal.append("split37 exit -> mode $CHANGE_MODE_WIDE_FULLSCREEN refused, area=$areaMode")
            return false
        }
        journal.append("split37 exit -> mode $CHANGE_MODE_WIDE_FULLSCREEN area=$areaMode")
        return true
    }

    /** True for the area modes that mean one container owns the screen, i.e. the split is gone. */
    private fun leftTheSplit(areaMode: Int): Boolean =
        areaMode == AREA_MODE_WIDE_ONLY || areaMode == AREA_MODE_FULLSCREEN

    /**
     * Pre-change-mode exit, kept for a daemon too old to know the verb: hands both tasks to the
     * fullscreen root without a resize — the firmware sizes them itself. Wide goes last so it is
     * the one left on top.
     */
    private suspend fun exitByMove(session: Session): Boolean {
        val fullRootId = session.fullRootId
        if (fullRootId == null) {
            journal.append("split37 exit: no full root - cannot leave the split")
            return false
        }
        val narrowOk = helper.split37MoveTask(session.narrowTaskId, fullRootId, null)
        // The wide move runs even after a failed narrow one: leaving one task in a pane and the
        // other in fullscreen is worse than trying both.
        val wideOk = helper.split37MoveTask(session.wideTaskId, fullRootId, null)
        journal.append(
            "split37 exit fallback (no change-mode verb) -> " +
                "narrow=${okText(narrowOk)} wide=${okText(wideOk)}"
        )
        return narrowOk && wideOk
    }

    // ── internals ─────────────────────────────────────────────────────────────

    private sealed class PaneResult {
        data class Ok(val taskId: Int) : PaneResult()
        data class Fail(val reason: String) : PaneResult()
    }

    private class PaneTick(val state: PaneState, val taskId: Int)

    /**
     * Launches [pkg] if it is not running yet and reparents its task into [root]. [targetArea] is
     * the area id that root stands for (see [AREA_NARROW] / [AREA_WIDE]) and [fullRootId] the
     * fullscreen root — the two together enable the bounce of a task that is already in the root.
     */
    private suspend fun placePane(
        pane: String,
        pkg: String,
        root: Split37Root,
        targetArea: Int,
        fullRootId: Int?,
    ): PaneResult {
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
        if (running != null) bounceIfAlreadyInRoot(pane, pkg, taskId, targetArea, root, fullRootId)
        val moved = helper.split37MoveTask(
            taskId,
            root.rootTaskId,
            Rect(root.left, root.top, root.right, root.bottom),
        )
        if (!moved) return PaneResult.Fail("move $pkg -> root ${root.rootTaskId} failed")
        return PaneResult.Ok(taskId)
    }

    /**
     * Moves [taskId] out to the fullscreen root WITHOUT raising it when it already lives in the
     * area of [root]. A move into the root a task is already in does not change its order (on-car
     * 2026-08-19), so a pane left standing from the previous session keeps whatever the firmware
     * put on top of it — the vehicle widget over our narrow app. The bounce is not load-bearing:
     * when it fails the ordinary move still runs, at worst leaving the old order. The reverse case
     * is rare and left alone: a bounce that landed while the move after it failed leaves the task
     * in the fullscreen root, and the next tick (or the next tap) puts it back into its pane.
     */
    private suspend fun bounceIfAlreadyInRoot(
        pane: String,
        pkg: String,
        taskId: Int,
        targetArea: Int,
        root: Split37Root,
        fullRootId: Int?,
    ) {
        if (helper.split37TaskArea(taskId) != targetArea) return
        val where = "split37 place $pane $pkg task=$taskId already in root ${root.rootTaskId}"
        if (fullRootId == null) {
            journal.append("$where - no full root, no bounce")
            return
        }
        val bounced = helper.split37MoveTask(taskId, fullRootId, null, toTop = false)
        journal.append(if (bounced) "$where - bounced" else "$where - bounce failed")
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
     * Re-reads the area info while the firmware may still be animating its containers, until
     * [accept] takes the area mode. Returns the last read (null when the channel stayed silent
     * through all of them).
     */
    private suspend fun awaitAreaMode(accept: (Int) -> Boolean): Split37AreaInfo? {
        var last: Split37AreaInfo? = null
        repeat(ENTER_SETTLE_READS) {
            delay(ENTER_SETTLE_DELAY_MS)
            last = helper.split37AreaInfo()
            val mode = last?.areaMode
            if (mode != null && accept(mode)) return last
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
