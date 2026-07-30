package com.bydmate.app.split

import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.vehicle.FreeformLaunchResult
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.RaiseOutcome
import com.bydmate.app.data.vehicle.SplitTaskState
import com.bydmate.app.data.vehicle.TopTaskInfo
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "SplitSessionMgr"
// Package name of this app — excluded from COVERED detection so BYDMate's own windows
// (e.g. the Settings overlay launched by the user) do not collapse the split session.
private val OUR_PACKAGE get() = BuildConfig.APPLICATION_ID

// ─── Geometry constants (validated live on the car, screen 1920×1080) ────────

private const val SCREEN_W = 1920
private const val Y_TOP = 84
private const val Y_BOT = 990

/** Narrow pane on the right: junction at x=1280 (one-third from the right). */
private const val X_JUNCTION_NARROW_RIGHT = 1280

/** Narrow pane on the left: junction at x=640 (one-third from the left). */
private const val X_JUNCTION_NARROW_LEFT = 640

private const val WINDOWING_FREEFORM = 5
private const val WINDOWING_FULLSCREEN = 1
// Native (system shell) split-screen halves. Our freeform panes are never in these modes,
// so seeing one means the user started the head unit's own split over our session (W6-F3).
private const val WINDOWING_SPLIT_PRIMARY = 3
private const val WINDOWING_SPLIT_SECONDARY = 4
private val NATIVE_SPLIT_MODES = setOf(WINDOWING_SPLIT_PRIMARY, WINDOWING_SPLIT_SECONDARY)
private const val DISPLAY_SPLIT = 0

/** Suppress same-pair restarts that arrive within this many ms of a successful start. */
private const val DOUBLE_TAP_WINDOW_MS = 1500L

/**
 * Bounded raise attempts on the restart path (W6-F1) before the pane is relaunched from scratch.
 *
 * Two attempts mirror the daemon's own retry budget (launchFreeformCore phase 1). More attempts
 * only add binder round-trips inside the session mutex: if `am start` ran without error twice and
 * the live task state still does not show freeform on the split display, the task is beyond a
 * raise and needs a fresh launch.
 */
private const val PANE_RAISE_MAX_ATTEMPTS = 2

/**
 * Bounded re-reads of a pane's task state when the daemon reports Display.INVALID_DISPLAY (-1).
 *
 * -1 is a mid-reparent transient, not a placement: classifying on it would either send a task that
 * is really on the cluster down the raise path, or force-stop a task that was about to settle on
 * the main display. Two re-reads cover the reparent window observed on-car without adding a
 * noticeable delay to the widget tap (both re-reads run inside the session mutex).
 */
private const val PANE_DISPLAY_REREADS = 2
private const val PANE_DISPLAY_REREAD_DELAY_MS = 150L

/**
 * Retry delay for failed setFocusedTask calls inside [SplitSessionManager.dismissReplacedTask].
 *
 * setTaskWindowingMode(oldTaskId, FULLSCREEN) triggers the daemon's am-stack-remove + am-start
 * compat path, which holds the WM lock while ATMS processes the relaunch. The two setFocusedTask
 * calls that follow can time out indistinguishably from a genuine ATMS rejection during this
 * window. One bounded retry after this pause gives the WM lock time to release so a transient
 * timeout does not incorrectly trigger the both-fail backdrop fallback.
 */
private const val FOCUS_RETRY_DELAY_MS = 300L

/**
 * Grace window for a BYDMate-initiated cluster send (Q1 / F-1).
 *
 * WHY TERMINAL OUTCOMES CANNOT ARRIVE LATE: each VD-path step is a blocking binder transact
 * (HelperClient.transactParsed). Coroutine cancellation only fires at suspension points — the
 * sole suspension before the transact is HelperClient.mutex.lock(). Once the transact is in
 * flight it runs to completion regardless of the coroutine timeout, so the client observes the
 * terminal outcome (return value or exception) only AFTER the daemon returns. A "late task move
 * after grace expires" is therefore unreachable: launchAndForce and the watchdog's getTaskState
 * are serialized by HelperClient.mutex (HelperClient:593), so the watchdog cannot observe
 * fullscreen@display0 while launchAndForce is in flight; once launchAndForce returns, the
 * watchdog's departed-branch fires first (SSM:1148) and resolves grace normally — or if the
 * daemon did not move the task, the failure return triggers endClusterSend, clearing grace early.
 *
 * DERIVATION: this deadline is the backstop for a hypothetically missed terminal, not a hard
 * wall-clock ceiling. Its value is derived from the dominant worst-case step (launchAndForce =
 * FORCE_TIMEOUT_MS = 15 000 ms in HelperClient) plus a watchdog-tick buffer (1 000 ms) and a
 * safety margin (2 000 ms). If FORCE_TIMEOUT_MS or any other per-step client timeout grows past
 * DEPARTURE_GRACE_MS, this constant must grow with it.
 *
 * Lengthening the deadline only extends the suppression window under the hypothetical missed-
 * terminal scenario; it has no effect on normal behaviour where grace is cleared deterministically
 * by the terminal-outcome callers of endClusterSend (see CPM for all five call sites).
 */
private const val DEPARTURE_GRACE_MS = 18_000L
/** Post-departure noise window: stale fullscreen@display0 readings after departure are suppressed
 *  for this many ms. After the window, fullscreen@display0 is a genuine return → MAXIMIZED. */
private const val POST_DEPARTURE_NOISE_MS = 3_000L

// ─── Backdrop interface ───────────────────────────────────────────────────────

/**
 * Controls the split-screen backdrop overlay (dim layer + drag grip).
 *
 * [show] is suspend so that [SplitSessionManager.startLocked] can await the backdrop's
 * onResume before launching freeform windows — preventing the z-order race where the
 * backdrop task reorders-to-front AFTER the app windows are already placed.
 * Returns true when the backdrop confirmed resumed, false on timeout or start failure.
 * On false [startLocked] still launches the windows (degraded but functional).
 */
interface SplitBackdrop {
    suspend fun show(): Boolean
    fun hide()
}

// ─── State and event types ────────────────────────────────────────────────────

sealed class SplitSessionState {
    object Idle : SplitSessionState()
    data class Active(
        val pair: SplitPair,
        val narrowTaskId: Int,
        val wideTaskId: Int,
    ) : SplitSessionState()
}

sealed class SplitEvent {
    data class PaneClosed(val pane: Pane) : SplitEvent()
    data class SessionEnded(val reason: EndReason) : SplitEvent()
}

enum class Pane { NARROW, WIDE }
enum class EndReason { EXIT, MAXIMIZED, COVERED, NATIVE_SPLIT }
enum class SplitStartResult { OK, FREEFORM_UNAVAILABLE, LAUNCH_FAILED, DISABLED }

// ─── Geometry helpers ─────────────────────────────────────────────────────────

/** Axis-aligned rectangle used for task bounds operations. */
data class SplitBounds(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * Returns (wideBounds, narrowBounds) for the requested [side].
 *
 *   narrow-right → wide [0,84,1280,990]   narrow [1280,84,1920,990]
 *   narrow-left  → wide [640,84,1920,990]  narrow [0,84,640,990]
 */
internal fun boundsFor(side: SplitSide): Pair<SplitBounds, SplitBounds> = when (side) {
    SplitSide.RIGHT ->
        SplitBounds(0, Y_TOP, X_JUNCTION_NARROW_RIGHT, Y_BOT) to
            SplitBounds(X_JUNCTION_NARROW_RIGHT, Y_TOP, SCREEN_W, Y_BOT)
    SplitSide.LEFT ->
        SplitBounds(X_JUNCTION_NARROW_LEFT, Y_TOP, SCREEN_W, Y_BOT) to
            SplitBounds(0, Y_TOP, X_JUNCTION_NARROW_LEFT, Y_BOT)
}

private fun SplitTaskState.toBounds() = SplitBounds(left, top, right, bottom)

private suspend fun HelperClient.setTaskBounds(taskId: Int, b: SplitBounds) =
    setTaskBounds(taskId, b.left, b.top, b.right, b.bottom)

// ─── Session manager ──────────────────────────────────────────────────────────

/**
 * Manages the lifecycle of a split-screen session: launching two apps in
 * freeform windows, monitoring their state, snapping drifted bounds back,
 * and reacting to maximize / close events.
 *
 * Thread-safety: [mutex] serialises all state transitions between the public API
 * and the watchdog tick, preventing races such as exit() vs a MAXIMIZED tick
 * double-emitting [SplitEvent.SessionEnded].
 *
 * @param scope            CoroutineScope that owns the watchdog coroutine AND the execution of
 *                         [start] / [changeApp] (so caller-scope cancellation cannot leave a
 *                         half-started session). Pass a test scope so virtual time controls
 *                         the tick in tests.
 * @param tickDelayMs      Watchdog poll interval in milliseconds (default 1 s).
 *                         Override in tests to speed up watchdog verification.
 * @param nowMs            Monotonic time source in milliseconds; injectable for tests.
 * @param mediaSource      Optional media-session source for the media-key reroute guard.
 *                         Null (default) disables the feature — zero cost, no poll.
 * @param mediaPollDelayMs Period of the mediacenter foreground poll (ms). Override in tests.
 * @param applyCalibratedBounds Optional hook called ONCE when a pane's task departs to the
 *                         cluster display. Receives (taskId, taskDisplayId). Passes through to
 *                         ClusterProjectionManager.applyCalibratedBoundsToTask in production,
 *                         which no-ops unless direct projection is active on that display.
 *                         Null (default) is a no-op — keeps existing tests unchanged.
 */
class SplitSessionManager(
    private val helper: HelperClient,
    private val prefs: SplitPreferences,
    private val backdrop: SplitBackdrop,
    private val scope: CoroutineScope,
    private val tickDelayMs: Long = 1000L,
    /**
     * Called when freeform windows are confirmed live (both launchFreeform calls OK and task ids
     * resolved). The production binding clears KEY_SPLIT_FREEFORM_REBOOT_PENDING so the settings
     * hint dismisses after the first successful start post-reboot. Default no-op keeps existing
     * tests unchanged.
     */
    private val onFreeformLive: () -> Unit = {},
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val mediaSource: MediaSessionSource? = null,
    private val mediaPollDelayMs: Long = MEDIA_POLL_MS,
    private val applyCalibratedBounds: (suspend (taskId: Int, taskDisplayId: Int) -> Boolean)? = null,
    /**
     * Optional hook called by [placePaneLocked] when a pane app's task is found on a non-main
     * display at session start (W6-F1 FIX-B). The production binding forwards to
     * ClusterProjectionManager.endProjectionForPkg, which ends OUR projection of that package
     * through the ordinary OFF sequence and returns true; false means the projection belongs to
     * another package (or none is running) and nothing was torn down.
     *
     * **Lock order:** invoked while holding [mutex], and the production implementation takes
     * ClusterProjectionManager.mutex — the sanctioned SSM → CPM direction, identical to
     * [applyCalibratedBounds]. Never wire a callback here that already holds CPM's mutex.
     *
     * Null (default) is a no-op — keeps existing tests and cluster-less builds unchanged.
     */
    private val endClusterProjection: (suspend (pkg: String) -> Boolean)? = null,
) {
    private val _state = MutableStateFlow<SplitSessionState>(SplitSessionState.Idle)
    val state: StateFlow<SplitSessionState> = _state.asStateFlow()

    /**
     * Zero-replay event bus. Collectors must subscribe **before** calling [start]
     * to receive all events; events emitted with no active collector are dropped.
     * [extraBufferCapacity] prevents emit() from suspending while holding [mutex].
     */
    private val _events = MutableSharedFlow<SplitEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<SplitEvent> = _events.asSharedFlow()

    /** Serialises all state transitions between the public API and the watchdog tick. */
    internal val mutex = Mutex()
    private var watchdogJob: Job? = null

    // Media-key reroute poll. Runs ONLY while Active; null when idle or feature disabled.
    // All accesses happen inside [mutex] (cancel/assign) or are initiated from a locked context.
    private var mediaPollJob: Job? = null

    // Edge-trigger flags — true while a [PaneClosed] event has been emitted for that pane.
    // Reset when the pane is confirmed alive again (task observed with id > 0) or on
    // start() / changeApp(). All accesses happen inside [mutex].
    private var narrowPaneClosedEmitted = false
    private var widePaneClosedEmitted = false

    // Parallel edge-trigger flags for the "departed" path (Task M): set once when a pane's task
    // is observed on a non-zero display (e.g. the native "show on cluster" button was pressed).
    // Cleared when the pane task is next seen alive on display 0 (user returned it to the main
    // screen), or on start() / changeApp(). All WRITES happen inside [mutex]; @Volatile only so
    // the advisory UI read in [departedPanePkgs] sees a recent value from the main thread.
    @Volatile private var narrowPaneDepartedEmitted = false
    @Volatile private var widePaneDepartedEmitted = false

    // Departure-grace deadlines keyed by package name (C-1 / Q1 / F-1): written synchronously by
    // [beginClusterSend] (no mutex — see its KDoc) and read under [mutex] by [handlePaneLocked].
    // Keyed by package so [swapApps] cannot transfer a grace window from one role to the other:
    // after a swap the same package lands in the opposite slot but its deadline is still reachable
    // by the correct key.
    // @Volatile on the Map reference ensures the write is visible to the watchdog thread.
    // emptyMap() = no active grace; at most 2 entries (one per pane) at any time.
    //
    // All mutations go through [graceLock] to prevent the lost-update race (D-2):
    // beginClusterSend() runs outside [mutex] (lock-order constraint), so a concurrent
    // map = map + entry from beginClusterSend can be silently overwritten by a map = map - key
    // inside [mutex] that read the old map reference. [graceLock] is a leaf lock — no other
    // locks or coroutine suspensions happen inside its critical section — so it cannot deadlock
    // with [mutex] regardless of acquisition order. Reads remain @Volatile snapshots (no lock).
    // @VisibleForTesting: accessed by D-2 functional test to verify the map after changeApp.
    @Volatile internal var departureGraceDeadlines: Map<String, Long> = emptyMap()
        private set

    /** Leaf lock that serialises all mutations of [departureGraceDeadlines]. See its KDoc. */
    private val graceLock = Any()

    // Packages whose applyCalibratedBounds returned false on the current departed-pane tick
    // (D-1-R1): the hook signals an IPC failure via false instead of throwing; SSM marks the
    // package here so the next watchdog tick retries. Also gates swapApps/mirror — the pane's
    // cluster geometry is unconfirmed while the set is non-empty. Protected by [graceLock].
    @Volatile private var calibrationPendingPkgs: Set<String> = emptySet()

    // Post-departure noise deadlines (Q1 fix-round): set to nowMs() + POST_DEPARTURE_NOISE_MS when
    // the departed branch first confirms a pane on the cluster. Fullscreen@display0 within this
    // window is stale-cache noise; after the window the pane genuinely returned → MAXIMIZED.
    // All accesses under [mutex] (departed branch and MAXIMIZED branch run inside [tickLocked]).
    @Volatile private var narrowPostDepartureNoiseDeadlineMs: Long = Long.MIN_VALUE
    @Volatile private var widePostDepartureNoiseDeadlineMs: Long = Long.MIN_VALUE

    // Time-window double-tap guard. All accesses inside [mutex].
    private var lastStartedPair: SplitPair? = null
    private var lastStartMs: Long = Long.MIN_VALUE

    // Media-key reroute state. [rerouteCount] tracks reroutes in this session (for logging);
    // [rerouteStoodDown] is the stand-down latch — set after REROUTE_MAX_COUNT reroutes and
    // cleared on every session start/exit/MAXIMIZED. All accesses inside [mutex].
    private var rerouteCount = 0
    private var rerouteStoodDown = false

    // COVERED detection (Q3 / F-3): consecutive ticks where a foreign fullscreen task is on top
    // of the split. Requires 2 consecutive ticks to suppress flicker. Reset on start/exit/COVERED.
    // All accesses inside [mutex].
    private var coveredTickCount = 0

    // Native split-screen detection (W6-F3): consecutive ticks where one of our panes is observed
    // in a system split-screen windowing mode. Same 2-tick anti-flap gate as COVERED.
    // All accesses inside [mutex].
    private var nativeSplitTickCount = 0

    // ── Public API ────────────────────────────────────────────────────────────
    // beginClusterSend is lock-free (@Volatile writes, no mutex acquired — see its KDoc).
    // All other public API acquires the mutex.

    /**
     * Arms a [DEPARTURE_GRACE_MS] departure-grace window for the pane identified by [pkg].
     * Must be called by [ClusterProjectionManager.onBeforeClusterSend] synchronously — BEFORE
     * [helper.launchFreeform] starts the REMOVE+RELAUNCH — so the deadline is guaranteed to be
     * visible to the watchdog before any transient dead/fullscreen observation reaches it.
     *
     * **Lock-free by design.** Reads [_state].value (StateFlow — thread-safe without a lock) and
     * writes [@Volatile][departureGraceDeadlines] directly, with no [mutex] acquisition.
     * Acquiring [mutex] here would create a lock-order cycle: the caller
     * ([ClusterProjectionManager.tryDirectProjection]) holds [ClusterProjectionManager.mutex],
     * and the watchdog path holds [mutex] when it calls [applyCalibratedBounds] →
     * [ClusterProjectionManager.applyCalibratedBoundsToTask] → [ClusterProjectionManager.mutex].
     * The only safe order is [mutex] → [CPM.mutex] (watchdog direction).
     * @Volatile reference write is visible to [handlePaneLocked] on its next [mutex] acquire.
     *
     * No-op when no session is active or [pkg] is not a pane of the current pair.
     */
    fun beginClusterSend(pkg: String) {
        val deadline = nowMs() + DEPARTURE_GRACE_MS
        // E-4: read current pair AND write grace inside the same synchronized(graceLock) block.
        // Without this, changeApp can publish a new pair and clean up the old pkg's grace (inside
        // graceLock) BETWEEN our pair-check and our write, and a delayed beginClusterSend (from a
        // concurrent CPM thread) then re-inserts the replaced pkg — blocking mirror/swapApps for
        // up to DEPARTURE_GRACE_MS on a stale entry. Moving both operations inside graceLock
        // serializes them: whichever wins the lock either sees the old pair (and changeApp will
        // clear the grace it writes) or sees the new pair (and skips). Both orderings are safe.
        // C-1: package-keyed Map so swapApps cannot misalign grace.
        // D-2: same graceLock prevents lost-update with concurrent map readers under mutex.
        synchronized(graceLock) {
            val current = _state.value as? SplitSessionState.Active
            if (current == null) {
                Log.d(TAG, "beginClusterSend: no active session; grace skipped for $pkg")
                return
            }
            val role = when {
                current.pair.narrowPkg == pkg -> "narrow"
                current.pair.widePkg == pkg -> "wide"
                else -> {
                    Log.d(TAG, "beginClusterSend: $pkg is not a pane of the active session; grace skipped")
                    return
                }
            }
            departureGraceDeadlines = departureGraceDeadlines + (pkg to deadline)
            Log.i(TAG, "beginClusterSend: departure grace started for $role pane ($pkg)")
        }
    }

    /**
     * Clears the departure-grace window for [pkg] when the entire projection pipeline terminates
     * without placing the task on the cluster (terminal VD failures: surface timeout, createVd
     * fail, launchAndForce=false, or an exception in [ClusterProjectionManager.project]).
     * Without this, the grace window would needlessly block [swapApps] and suppress COVERED
     * detection even though the task never left the main display.
     *
     * UNAVAILABLE and FAILED results from [tryDirectProjection] do NOT call this function — those
     * paths fall through to the VD pipeline, so grace must remain active until the VD pipeline
     * either succeeds or terminates.
     *
     * **Lock-free by the same argument as [beginClusterSend]:** called from
     * [ClusterProjectionManager.project] while holding [ClusterProjectionManager.mutex],
     * so acquiring [mutex] here would invert the SSM → CPM lock order used by the watchdog path.
     * Uses [graceLock] (leaf lock, no suspensions inside) to prevent the D-2 lost-update race.
     */
    fun endClusterSend(pkg: String) {
        synchronized(graceLock) {
            if (pkg in departureGraceDeadlines) {
                departureGraceDeadlines = departureGraceDeadlines - pkg
                Log.d(TAG, "endClusterSend: grace cleared early (send failed) for $pkg")
            }
        }
    }

    /**
     * Packages of the current session whose pane has departed to another display (W6-F2).
     *
     * A departed app owns a single task that lives on the cluster; offering it in the pane picker
     * produces a black pane (the task cannot be in two places) while the cluster copy stays put.
     * The picker uses this set to hide such apps until the watchdog sees the task back on
     * display 0 and clears the flag.
     *
     * Advisory read for UI code: intentionally NOT taken under [mutex], because it is called from
     * the main thread while the watchdog may hold the lock for a whole IPC round trip. Consistency
     * is best-effort — the flags are @Volatile, so at worst the picker is one tick stale and
     * self-heals on the next list sync. Empty when no session is active.
     */
    fun departedPanePkgs(): Set<String> {
        val active = _state.value as? SplitSessionState.Active ?: return emptySet()
        return buildSet {
            if (narrowPaneDepartedEmitted) add(active.pair.narrowPkg)
            if (widePaneDepartedEmitted) add(active.pair.widePkg)
        }
    }

    /**
     * Re-raises both panes after the backdrop resurfaced above them (hotfix 390-2).
     *
     * Opening a fullscreen Activity of our own package (e.g. BYDMate from the widget) hides the
     * freeform panes; on Back, ATMS brings the BACKDROP's task to front instead of the panes, so
     * the user is left looking at the black backdrop plus the pill while both panes are alive
     * behind it. The COVERED watchdog legitimately stays silent — the task on top belongs to us.
     *
     * This is a cosmetic re-raise, NOT a start: nothing is force-stopped or relaunched, and
     * [placePaneLocked] is deliberately not reused — its cluster branch would end the projection
     * of a departed pane and drag it off the instrument cluster, which is wrong here (a departed
     * pane lives on the cluster legitimately). A pane is touched only when its task is alive on
     * the split display and it has not departed; everything else is skipped. A failed raise is
     * logged and skipped too — the session is not torn down, and the next user interaction
     * self-heals it.
     *
     * Order: wide first, then narrow, so narrow ends up on top exactly as at start.
     *
     * A healthy split never triggers this: the backdrop is not stopped while the session runs, so
     * the multi-resume ticks of an untouched session do not reach here (see
     * [shouldReassertPanes]).
     */
    suspend fun onBackdropResurfaced() = mutex.withLock {
        val current = _state.value as? SplitSessionState.Active ?: return@withLock
        val (wideBounds, narrowBounds) = boundsFor(current.pair.narrowSide)
        reassertPaneLocked(current.pair.widePkg, wideBounds, widePaneDepartedEmitted)
        reassertPaneLocked(current.pair.narrowPkg, narrowBounds, narrowPaneDepartedEmitted)
    }

    /** Single-pane half of [onBackdropResurfaced]. Must hold [mutex]. */
    private suspend fun reassertPaneLocked(pkg: String, bounds: SplitBounds, departed: Boolean) {
        if (departed) {
            Log.d(TAG, "reassertPanes: $pkg has departed — leaving it on its display")
            return
        }
        val state = helper.getTaskState(pkg)
        if (state == null || state.taskId == -1 || state.displayId != DISPLAY_SPLIT) {
            Log.d(TAG, "reassertPanes: skipping $pkg (state=$state)")
            return
        }
        // A pane the user dragged into the NATIVE split (mode 3/4) is no longer ours: the tick
        // detection is about to tear the session down, and a raise from this lifecycle edge would
        // fight it. Only a still-freeform pane is reasserted.
        if (state.windowingMode != WINDOWING_FREEFORM) {
            Log.d(TAG, "reassertPanes: $pkg is in mode ${state.windowingMode}, not ours — skipping")
            return
        }
        if (helper.raiseFreeformTaskDetailed(pkg, DISPLAY_SPLIT, HelperBinderProtocol.PANE_TYPE_STANDARD)
            != RaiseOutcome.RAISED
        ) {
            Log.w(TAG, "reassertPanes: raise did not land for $pkg — leaving the session as is")
            return
        }
        // The raise may have RECREATED the task: a live task cannot be re-typed in place in either
        // direction of the STANDARD/RECENTS pair (the daemon removes it and relaunches), so the
        // bounds go to the id read AFTER the raise, never the one read before it.
        val raised = helper.getTaskState(pkg)
        if (raised == null || raised.taskId == -1) {
            Log.w(TAG, "reassertPanes: $pkg has no readable task after the raise — bounds skipped")
            return
        }
        helper.setTaskBounds(raised.taskId, bounds)
    }

    /**
     * Launches [pair] into split-screen freeform windows on display 0.
     *
     * If a session is already active, it is cleanly exited first (emits
     * [SplitEvent.SessionEnded] with [EndReason.EXIT]) before the new pair launches.
     *
     * Sequence: implicit exit → backdrop.show() → launchFreeform(wide) →
     * launchFreeform(narrow) (narrow last = on top / focused) → resolve task ids
     * → save pair → Active.
     *
     * Returns [SplitStartResult.FREEFORM_UNAVAILABLE] if the head unit has not
     * yet activated freeform mode (reboot required after enabling the flag).
     */
    suspend fun start(pair: SplitPair): SplitStartResult =
        // Run in the manager's own scope so caller-scope cancellation (e.g. WidgetController
        // dataScope dying when the backdrop brings the home screen forward) stops only the
        // caller's await() — the underlying start continues to completion.
        scope.async {
            mutex.withLock {
                if (!prefs.isFeatureEnabled()) return@withLock SplitStartResult.DISABLED
                val now = nowMs()
                // Time-window guard: suppress same-pair restart within DOUBLE_TAP_WINDOW_MS of
                // the last successful start. Unlike a state-keyed guard, this expires on its own
                // — dead panes (which the watchdog deliberately keeps Active) can still be
                // relaunched once the window passes.
                // Range check (not <) rejects a negative delta from a backward NTP/GPS clock jump
                // so a jumped clock cannot permanently lock the guard.
                if (pair == lastStartedPair && now - lastStartMs in 0 until DOUBLE_TAP_WINDOW_MS) {
                    return@withLock SplitStartResult.OK
                }
                // Implicit exit of any running session so old freeform tasks are dismissed.
                val existing = _state.value as? SplitSessionState.Active
                if (existing != null) tearDownLocked(existing, emitExit = true)
                // Reset pane-close and departed edge triggers for the fresh session.
                narrowPaneClosedEmitted = false
                widePaneClosedEmitted = false
                narrowPaneDepartedEmitted = false
                widePaneDepartedEmitted = false
                // Clear departure-grace and post-departure noise markers from the previous session (Q1).
                // D-2: graceLock protects against a concurrent beginClusterSend write.
                // D-1-R3: calibrationPendingPkgs must also be reset so a new session never
                // inherits a stale retry marker that would silently block swapApps/mirror.
                synchronized(graceLock) { departureGraceDeadlines = emptyMap(); calibrationPendingPkgs = emptySet() }
                narrowPostDepartureNoiseDeadlineMs = Long.MIN_VALUE
                widePostDepartureNoiseDeadlineMs = Long.MIN_VALUE
                // Clear the media-key reroute state for the fresh session.
                rerouteCount = 0
                rerouteStoodDown = false
                coveredTickCount = 0
                nativeSplitTickCount = 0
                val result = startLocked(pair)
                if (result == SplitStartResult.OK) {
                    lastStartedPair = pair
                    // Fresh read AFTER startLocked completes: backdrop.show() can take up to 2 s
                    // on-car; recording the initiation timestamp would let a queued second tap
                    // sail through the already-expired window.
                    lastStartMs = nowMs()
                } else {
                    // W6-F1: end the attempt honestly instead of leaving a shell on screen.
                    // startLocked already hid the backdrop and left the state Idle, but the pill
                    // may still be up — either from the session this start implicitly exited, or
                    // from a COVERED teardown whose pane apps are still alive in the background
                    // (exactly the case a failed restart lands in). SessionEnded makes the overlay
                    // controller drop the pill and the picker.
                    _events.emit(SplitEvent.SessionEnded(EndReason.EXIT))
                }
                result
            }
        }.also { deferred ->
            deferred.invokeOnCompletion { e ->
                if (e != null && e !is CancellationException) {
                    Log.w(TAG, "start failed:", e)
                }
            }
        }.await()

    /**
     * Restores the last saved pair.
     * Returns [SplitStartResult.DISABLED] when the feature is off.
     * Returns null when no pair has been saved yet (nothing to restore).
     */
    suspend fun startLastPair(): SplitStartResult? {
        if (!prefs.isFeatureEnabled()) return SplitStartResult.DISABLED
        val pair = prefs.getLastPair() ?: return null
        return start(pair)
    }

    /**
     * Flips the narrow pane to the opposite side, recomputes both bounds,
     * and issues [setTaskBounds] for each task.
     */
    suspend fun mirror() = mutex.withLock {
        val current = _state.value as? SplitSessionState.Active ?: return@withLock
        // Full no-op while any departure grace is active OR calibration is pending (D-1-R1):
        // the departing task is mid-REMOVE+RELAUNCH or its cluster geometry is unconfirmed;
        // stamping main-screen bounds onto the wrong task id would corrupt calibration.
        // E-2: gate is a TOCTOU-check — beginClusterSend() can arm grace between this read and
        // the bounds writes below (graceLock is intentionally not held here to avoid the
        // lock-order cycle documented on beginClusterSend). The re-check below narrows the race
        // window from ~ms (Binder latency) to ~ns (two volatile reads). Residual window
        // self-heals: E-1 guarantees calibration retry continues until success, so any incorrect
        // main-display bounds will be overwritten by the next successful calibration tick.
        // This is an acknowledged bounded risk — no tryLock or timeout needed.
        if (departureGraceDeadlines.values.any { nowMs() < it } || calibrationPendingPkgs.isNotEmpty()) return@withLock
        // Full no-op when any pane is on the cluster. mirror() assumes both panes are on the
        // main display: stamping main-screen bounds onto a departed (cluster-calibrated) task
        // would corrupt its calibration with no recovery path (the one-shot departed flag is
        // already set, so the watchdog will not re-apply cluster bounds).
        if (narrowPaneDepartedEmitted || widePaneDepartedEmitted) return@withLock
        val newSide = if (current.pair.narrowSide == SplitSide.LEFT) SplitSide.RIGHT else SplitSide.LEFT
        val (wideBounds, narrowBounds) = boundsFor(newSide)
        // E-2: re-check grace/pending immediately before the bounds writes to minimise the TOCTOU
        // window (see comment on the initial gate above).
        if (departureGraceDeadlines.values.any { nowMs() < it } || calibrationPendingPkgs.isNotEmpty()) return@withLock
        helper.setTaskBounds(current.narrowTaskId, narrowBounds)
        helper.setTaskBounds(current.wideTaskId, wideBounds)
        val newPair = current.pair.copy(narrowSide = newSide)
        prefs.saveLastPair(newPair)
        _state.value = current.copy(pair = newPair)
    }

    /**
     * Swaps which app occupies the narrow / wide role.
     * Task ids remain with their apps; only bounds and pair roles swap.
     * After swapping: the former narrow task gets wide bounds and vice-versa.
     */
    suspend fun swapApps() = mutex.withLock {
        val current = _state.value as? SplitSessionState.Active ?: return@withLock
        // Full no-op when a departure is in flight (grace active), calibration is pending
        // (D-1-R1), or already confirmed. Swapping during grace/pending: the departing task
        // is mid-REMOVE+RELAUNCH or its cluster geometry is unconfirmed; setTaskBounds on
        // a dead task id is harmless but calibratedBounds would apply to the WRONG slot.
        // Swapping after departure: same misalignment of the departed flag.
        // E-2: same TOCTOU rationale as mirror() — see its gate comment for details.
        if (departureGraceDeadlines.values.any { nowMs() < it } || calibrationPendingPkgs.isNotEmpty()) return@withLock
        if (narrowPaneDepartedEmitted || widePaneDepartedEmitted) return@withLock
        val (wideBounds, narrowBounds) = boundsFor(current.pair.narrowSide)
        // E-2: re-check immediately before writing (same rationale as mirror).
        if (departureGraceDeadlines.values.any { nowMs() < it } || calibrationPendingPkgs.isNotEmpty()) return@withLock
        // Former narrow task → wide bounds; former wide task → narrow bounds.
        helper.setTaskBounds(current.narrowTaskId, wideBounds)
        helper.setTaskBounds(current.wideTaskId, narrowBounds)
        val newPair = SplitPair(
            narrowPkg = current.pair.widePkg,
            widePkg = current.pair.narrowPkg,
            narrowSide = current.pair.narrowSide,
        )
        prefs.saveLastPair(newPair)
        // Former wide task id is now the narrow one and vice-versa.
        _state.value = current.copy(
            pair = newPair,
            narrowTaskId = current.wideTaskId,
            wideTaskId = current.narrowTaskId,
        )
    }

    /**
     * Replaces one pane with [newPkg]: launches it in the pane's bounds, then
     * moves the replaced task to fullscreen (effectively dismissing it from split).
     * Clears the pane-closed edge-trigger flag for the replaced pane.
     * On any launch failure the existing session is unchanged.
     *
     * When the replaced task id differs from the new one, [dismissReplacedTask] re-asserts
     * Z-order so the old app's fullscreen flip cannot surface it above the split backdrop.
     */
    suspend fun changeApp(pane: Pane, newPkg: String): SplitStartResult =
        // Same pattern as start(): run in manager scope so caller cancellation mid-await
        // (e.g. forceStopIfNeeded or launchFreeform suspend) does not leave a half-replaced pane.
        scope.async {
            mutex.withLock {
                val current = _state.value as? SplitSessionState.Active
                    ?: return@withLock SplitStartResult.LAUNCH_FAILED
                val (wideBounds, narrowBounds) = boundsFor(current.pair.narrowSide)
                val paneBounds = if (pane == Pane.NARROW) narrowBounds else wideBounds
                val oldTaskId = if (pane == Pane.NARROW) current.narrowTaskId else current.wideTaskId

                // Force-stop any stale fullscreen task before launching in freeform (same fix as startLocked).
                forceStopIfNeeded(newPkg)

                val result = helper.launchFreeform(
                    newPkg, DISPLAY_SPLIT,
                    paneBounds.left, paneBounds.top, paneBounds.right, paneBounds.bottom,
                    HelperBinderProtocol.PANE_TYPE_STANDARD,
                )
                if (result == FreeformLaunchResult.UNAVAILABLE) return@withLock SplitStartResult.FREEFORM_UNAVAILABLE
                if (result == FreeformLaunchResult.FAILED) return@withLock SplitStartResult.LAUNCH_FAILED

                val newTaskId = helper.getTaskState(newPkg)?.taskId?.takeIf { it != -1 }
                    ?: return@withLock SplitStartResult.LAUNCH_FAILED

                val newNarrowTaskId = if (pane == Pane.NARROW) newTaskId else current.narrowTaskId
                val newWideTaskId = if (pane == Pane.WIDE) newTaskId else current.wideTaskId

                // Skip dismissal when the same task won the slot (e.g. restore after PaneClosed
                // when the app was still alive): calling dismissReplacedTask with oldTaskId ==
                // newTaskId would flip the task to FULLSCREEN, triggering a MAXIMIZED watchdog
                // tick → SessionEnded, collapsing the split.
                //
                // Also skip when the replaced task lives on another display (displayId != 0):
                // dismissReplacedTask flips it to FULLSCREEN, which the daemon's compat path
                // resolves to am-stack-remove + am-start on display 0 — yanking the departed
                // task off the cluster the moment the user picks a replacement app.
                val oldPkg = if (pane == Pane.NARROW) current.pair.narrowPkg else current.pair.widePkg
                val oldState = if (oldTaskId != newTaskId) helper.getTaskState(oldPkg) else null
                if (oldTaskId != newTaskId && (oldState == null || oldState.displayId == 0)) {
                    val newNarrowPkg = if (pane == Pane.NARROW) newPkg else current.pair.narrowPkg
                    val newWidePkg = if (pane == Pane.WIDE) newPkg else current.pair.widePkg
                    dismissReplacedTask(oldTaskId, newNarrowPkg, newWidePkg, newNarrowTaskId, newWideTaskId)
                }

                val newPair = if (pane == Pane.NARROW) {
                    current.pair.copy(narrowPkg = newPkg)
                } else {
                    current.pair.copy(widePkg = newPkg)
                }
                prefs.saveLastPair(newPair)
                // Ordering is load-bearing for the E-4 guarantee: state must be published BEFORE the graceLock cleanup below (see structural note in tests).
                _state.value = SplitSessionState.Active(newPair, newNarrowTaskId, newWideTaskId)
                // New app is live — clear any stale closed, departed, grace, noise and COVERED-tick
                // flags for this pane. coveredTickCount is also cleared: changeApp replaces a pane
                // mid-session, and the partial tick count from before the swap could allow COVERED
                // to fire after only one more tick (breaking the 2-tick invariant).
                coveredTickCount = 0
                when (pane) {
                    Pane.NARROW -> { narrowPaneClosedEmitted = false; narrowPaneDepartedEmitted = false; narrowPostDepartureNoiseDeadlineMs = Long.MIN_VALUE }
                    Pane.WIDE -> { widePaneClosedEmitted = false; widePaneDepartedEmitted = false; widePostDepartureNoiseDeadlineMs = Long.MIN_VALUE }
                }
                // C-1: clear by package, not by role. D-2: graceLock against concurrent beginClusterSend.
                // D-1-R3: also clear any pending calibration marker for the replaced package so
                // swapApps/mirror are not left permanently blocked when a pane is hot-swapped.
                synchronized(graceLock) { departureGraceDeadlines = departureGraceDeadlines - oldPkg; calibrationPendingPkgs = calibrationPendingPkgs - oldPkg }
                SplitStartResult.OK
            }
        }.also { deferred ->
            deferred.invokeOnCompletion { e ->
                if (e != null && e !is CancellationException) {
                    Log.w(TAG, "changeApp failed:", e)
                }
            }
        }.await()

    /**
     * Tears down the split session: both tasks return to fullscreen windowing
     * mode, the backdrop hides, and state reverts to [SplitSessionState.Idle].
     * The last saved pair is preserved for [startLastPair].
     */
    suspend fun exit() = mutex.withLock {
        val current = _state.value as? SplitSessionState.Active ?: return@withLock
        tearDownLocked(current, emitExit = true)
    }

    // ── Private locked helpers (all called from within mutex.withLock) ─────────

    /**
     * Prepares [pkg] for a freeform launch when its task is alive but NOT already in freeform.
     * On DiLink 5, pressing Home leaves the app's task in fullscreen/invisible state, and
     * launchFreeform's `am start --windowingMode 5` cannot coerce it.
     *
     * Two-step strategy (preserves the app process when possible so media playback survives):
     * 1. Gentle flip: call setTaskWindowingMode(taskId, 5). On DiLink 5 this resolves to the
     *    shell path `am start --windowingMode 5 --display 0 -n <cmp>` — low-cost, process-safe.
     *    Re-query getTaskState: if mode is now 5, the flip landed → proceed, no force-stop.
     * 2. Fallback: if the flip did not land (mode still != 5 or re-query failed) → forceStop(pkg)
     *    so launchFreeform gets a fresh task that accepts freeform windowing.
     *
     * Fast-path (no flip, no force-stop): task does not exist (taskId == -1) OR is already
     * freeform (windowingMode == 5) OR the task does not live on the split display. Fail-soft:
     * all errors swallowed so launch still proceeds.
     *
     * [splitDisplayOnly] is set by the [startLocked] call sites only, where [placePaneLocked] runs
     * right after and owns the non-split-display cases. [changeApp] keeps the historic behaviour
     * (no gate): there the user explicitly picked the app for the pane and there is no placePane
     * classification behind it to fall back on.
     *
     * The display gate is load-bearing (FIX round 4). The gentle flip resolves to
     * `am start --windowingMode 5 --display 0` in the daemon's setWindowingModeCompat, which drags
     * a task home from wherever it was. Running that before [placePaneLocked] destroyed the two
     * branches that exist for non-split displays: a task on the cluster arrived at
     * [placePaneLocked] already reporting display 0, so the [endClusterProjection] hook never ran
     * and our own projection was left reporting FULLSCREEN with an orphaned overlay/VirtualDisplay;
     * and an INVALID_DISPLAY (-1) task never reached its bounded re-read. Those cases are handled
     * correctly one call later, so they are left alone here.
     */
    private suspend fun forceStopIfNeeded(pkg: String, splitDisplayOnly: Boolean = false) {
        runCatching {
            val state = helper.getTaskState(pkg) ?: return@runCatching
            if (state.taskId == -1 || state.windowingMode == WINDOWING_FREEFORM) return@runCatching
            // Not on the split display (cluster, or INVALID mid-reparent): leave it to placePane.
            if (splitDisplayOnly && state.displayId != DISPLAY_SPLIT) return@runCatching
            // Step 1: gentle mode flip — preserves the app process (media session survives).
            // STANDARD, like every other pane placement: a task flipped into freeform must end up
            // with its own root task, otherwise it lands as a leaf under the shared root and the
            // pane stays touch-dead.
            helper.setTaskWindowingMode(state.taskId, WINDOWING_FREEFORM, HelperBinderProtocol.PANE_TYPE_STANDARD)
            val afterFlip = helper.getTaskState(pkg)
            if (afterFlip != null && afterFlip.windowingMode == WINDOWING_FREEFORM) return@runCatching
            // Step 2: flip did not land — force-stop so launchFreeform creates a fresh task.
            helper.forceStop(pkg)
        }
    }

    /**
     * Places [pkg] into [bounds] as a freeform pane on the split display.
     *
     * Restart path (W6-F1): after a COVERED teardown the pane apps keep running in the background.
     * On the next start [HelperClient.launchFreeform] resolves that live task id and hands it to
     * the daemon's moveRootTaskToDisplay + setFocusedRootTask reflection. Up to 391 pane tasks were
     * recents-typed leaves under a shared freeform root (Task L/N), so ATMS rejected the move
     * ("moveRootTaskToTaskDisplayArea: Unknown rootTaskId", on-car 389) and setFocusedRootTask on
     * a leaf id was a silent no-op — yet the final state still read freeform@display0, so the
     * daemon reported OK. Result: backdrop up, panes left behind it (black backdrop + pill).
     * From 392 on panes are STANDARD with their own root tasks, yet a live task is still never
     * handed to the plain launch path: the raise below stays the restart mechanism.
     *
     * Branches, by the live state of [pkg]'s task:
     *
     * 1. **No task** (state null or taskId -1) — clean start. Untouched [HelperClient.launchFreeform]
     *    path: the daemon cold-launches the app in freeform, STANDARD-typed since 392.
     *
     * 2. **Task on the split display** — restart path, [raisePaneLocked]: the Task N mechanism
     *    (`am start --windowingMode 5 --display 0 -n <component>` — STANDARD needs no
     *    `--activityType`, it is what am assigns by default), which Android treats as "bring this
     *    task to front" regardless of task nesting. Anything other than a
     *    confirmed raise ends in force-stop + fresh launch — see [raisePaneLocked].
     *
     * 3. **Task on another display** (displayId > 0, i.e. on the cluster) — first ask
     *    [endClusterProjection] to end our own projection of this package; on success the task
     *    state is re-read and, when the app is back on the split display, the normal raise path
     *    runs. Otherwise force-stop + fresh launch. raiseFreeformTask is never used on a task that
     *    is still on the cluster: `am start --display 0` would yank it off. Plain launchFreeform is
     *    not safe either — its phase 2 moves the task by leaf id, the move throws, and the daemon's
     *    failure path resolves to `am stack remove` + relaunch, so the task leaves the cluster
     *    anyway, just non-deterministically. Force-stop makes that deterministic. Aborting the start
     *    instead would leave the widget tap dead even though the user explicitly asked for a split
     *    with this app; navigators restore their own guidance session after a relaunch (see
     *    setWindowingModeCompat KDoc in HelperDaemon).
     *
     * 4. **Display.INVALID_DISPLAY (-1)** — mid-reparent transient (FIX-C). Re-read the task state
     *    up to [PANE_DISPLAY_REREADS] times, [PANE_DISPLAY_REREAD_DELAY_MS] apart. If it resolves,
     *    the matching branch above runs. If it is still unresolved (or became unreadable) the pane
     *    is force-stopped and relaunched — never handed to launchFreeform as a live task.
     *
     * **Accepted bounded risk (single snapshot, E-2 pattern):** the branch is chosen from a state
     * snapshot, so a task created by an external actor between the snapshot and the launch takes
     * the clean-start path and can still hit the false-OK described above. Closing it would require
     * serialising task creation with the daemon, which this codebase does not do anywhere. It needs
     * a third-party launch inside a ~ms window and self-heals on the next widget tap — by then the
     * task exists and the snapshot puts it on the raise path.
     *
     * **Accepted bounded risk (cluster hook not linearised with CPM):** the interval
     * endClusterProjection → task re-read → raise → Active is NOT serialised against
     * ClusterProjectionManager.setMode. A projection command issued concurrently with the split
     * start can win or lose non-deterministically. This is a race between two contradicting user
     * commands (start the split vs. project this app), the semantics are last-command-wins, and the
     * outcome is bounded and recoverable: CPM's recoverStaleCompositor/recoverStaleDirectTask paths
     * heal the leftover state. A lease/gate over the whole interval was considered and rejected —
     * before this fix there was no coordination here at all (so no regression), and a cross-manager
     * lease would add a new deadlock surface to a point fix.
     *
     * Must be called from within [mutex].
     */
    private suspend fun placePaneLocked(pkg: String, bounds: SplitBounds): FreeformLaunchResult {
        var existing = helper.getTaskState(pkg)
        // Branch 1: no task at all — clean start, untouched launch path.
        if (existing == null || existing.taskId == -1) return launchPaneLocked(pkg, bounds)
        // Branch 4: INVALID_DISPLAY — bounded re-read before classifying.
        var reReads = 0
        while (reReads < PANE_DISPLAY_REREADS &&
            existing != null && existing.taskId != -1 && existing.displayId < DISPLAY_SPLIT
        ) {
            reReads++
            Log.d(TAG, "placePane: $pkg reports display ${existing.displayId} — re-reading ($reReads)")
            delay(PANE_DISPLAY_REREAD_DELAY_MS)
            existing = helper.getTaskState(pkg)
        }
        // Still unresolved (or the task vanished / became unreadable) after the re-reads.
        if (existing == null || existing.taskId == -1 || existing.displayId < DISPLAY_SPLIT) {
            return relaunchPaneLocked(pkg, bounds, "display still unresolved")
        }
        // Branch 3: the pane app is on the cluster.
        if (existing.displayId > DISPLAY_SPLIT) {
            // Ask ClusterProjectionManager to end its own projection of this package first, so it
            // is not left reporting FULLSCREEN with an orphaned overlay/VirtualDisplay. Fail-soft:
            // a null hook (split without cluster wiring), false (not our projection) or a throw all
            // fall through to the deterministic relaunch below.
            val ended = runCatching { endClusterProjection?.invoke(pkg) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull() == true
            if (ended) {
                val after = helper.getTaskState(pkg)
                if (after != null && after.taskId != -1 && after.displayId == DISPLAY_SPLIT) {
                    // The projection teardown pulled the task home — treat it as a normal restart.
                    return raisePaneLocked(pkg, bounds)
                }
            }
            return relaunchPaneLocked(pkg, bounds, "task lives on display ${existing.displayId}")
        }
        // Branch 2: task on the split display — raise it.
        return raisePaneLocked(pkg, bounds)
    }

    /** Plain freeform launch of [pkg] into [bounds] on the split display. Must hold [mutex]. */
    private suspend fun launchPaneLocked(pkg: String, bounds: SplitBounds): FreeformLaunchResult =
        helper.launchFreeform(
            pkg, DISPLAY_SPLIT, bounds.left, bounds.top, bounds.right, bounds.bottom,
            HelperBinderProtocol.PANE_TYPE_STANDARD,
        )

    /**
     * Force-stops [pkg] and launches it fresh. Used wherever the existing task cannot be trusted to
     * come to front: handing a live task to launchFreeform is exactly the path that reports a false
     * OK while the pane stays behind the backdrop (on 391 the live task was a recents leaf under a
     * shared root; the false OK is a property of the move path, not of the leaf typing).
     *
     * The force-stop result is load-bearing (FIX round 3). On the [RaiseOutcome.NO_REPLY] path the
     * transport is already unreliable, so forceStop can fail while the very next launchFreeform
     * reconnects to a revived daemon, meets the SAME live task and reproduces the silent
     * false OK. So: a failed forceStop is followed by one authoritative re-read; if the task is
     * still alive the launch is NOT attempted and the pane fails honestly (the caller already ends
     * the session and hides the backdrop). Deliberately no second forceStop attempt — a failed
     * forceStop means the channel itself is down, the re-read is the decisive check either way, and
     * a bounded retry would only add latency to a path that ends in a user-retryable failure.
     *
     * The re-read must be CONFIRMED (FIX round 4): per [HelperClient.getTaskState], null means the
     * daemon was unreachable or the call failed, and only `taskId == -1` means "no task". Treating
     * null as "the task is gone" would relaunch on exactly the unreachable-daemon scenario this
     * guard exists for, so null is terminal too — the launch proceeds only on a snapshot that
     * positively reports no task.
     *
     * Must hold [mutex].
     */
    private suspend fun relaunchPaneLocked(pkg: String, bounds: SplitBounds, reason: String): FreeformLaunchResult {
        Log.w(TAG, "placePane: $reason for $pkg — force-stopping and relaunching")
        if (!helper.forceStop(pkg)) {
            // Only a snapshot that positively reports "no task" clears the way for a fresh launch.
            val survivor = helper.getTaskState(pkg)
            if (survivor == null || survivor.taskId != -1) {
                Log.w(TAG, "placePane: forceStop failed and $pkg task not confirmed gone (state=$survivor) — giving up")
                return FreeformLaunchResult.FAILED
            }
        }
        return launchPaneLocked(pkg, bounds)
    }

    /**
     * Raises [pkg]'s existing task to front via the Task N mechanism and stamps [bounds].
     *
     * Only [RaiseOutcome.RAISED] followed by a live state showing freeform on the split display
     * counts as success. Every other outcome ends in [relaunchPaneLocked] (FIX-A):
     *  - [RaiseOutcome.REFUSED] — the daemon handled the TX but the raise did not run cleanly;
     *  - [RaiseOutcome.NO_REPLY] — no usable reply: unknown TX (daemon predating Task N), timeout
     *    or dead binder. The earlier "old daemon → plain launchFreeform for 386 parity" branch was
     *    removed deliberately: that parity preserved exactly the broken path (launchFreeform on a
     *    live task = silent false OK). Force-stop + launch works on every daemon
     *    generation; the cost is killing the app process in the rare stale-daemon window.
     *  - the raise ran but the pane never came up within [PANE_RAISE_MAX_ATTEMPTS] attempts.
     *
     * Must be called from within [mutex].
     */
    private suspend fun raisePaneLocked(pkg: String, bounds: SplitBounds): FreeformLaunchResult {
        repeat(PANE_RAISE_MAX_ATTEMPTS) { index ->
            when (helper.raiseFreeformTaskDetailed(pkg, DISPLAY_SPLIT, HelperBinderProtocol.PANE_TYPE_STANDARD)) {
                RaiseOutcome.RAISED -> {
                    val raised = helper.getTaskState(pkg)
                    if (raised != null && raised.taskId != -1 &&
                        raised.windowingMode == WINDOWING_FREEFORM && raised.displayId == DISPLAY_SPLIT
                    ) {
                        helper.setTaskBounds(raised.taskId, bounds)
                        return FreeformLaunchResult.OK
                    }
                    Log.w(TAG, "placePane: raise attempt ${index + 1} did not land for $pkg (state=$raised)")
                }
                RaiseOutcome.REFUSED -> return relaunchPaneLocked(pkg, bounds, "daemon refused the raise")
                RaiseOutcome.NO_REPLY -> return relaunchPaneLocked(pkg, bounds, "no reply to the raise")
            }
        }
        return relaunchPaneLocked(pkg, bounds, "raise did not land in $PANE_RAISE_MAX_ATTEMPTS attempts")
    }

    private suspend fun startLocked(pair: SplitPair): SplitStartResult {
        val (wideBounds, narrowBounds) = boundsFor(pair.narrowSide)
        // try/finally starts HERE — before backdrop.show() — so that cancellation landing
        // inside show()'s suspend (the field-confirmed widget bug vector) is also covered.
        // hide() is a regular function and executes safely in a finally during cancellation.
        try {
            val backdropOk = backdrop.show()
            if (!backdropOk) {
                Log.w(TAG, "startLocked: backdrop resume timed out — launching windows without z-order guarantee")
            }
            // Force-stop any stale fullscreen task before launching in freeform (Bug A fix).
            forceStopIfNeeded(pair.widePkg, splitDisplayOnly = true)

            // Wide pane launches first.
            val wideResult = placePaneLocked(pair.widePkg, wideBounds)
            if (wideResult == FreeformLaunchResult.UNAVAILABLE) return SplitStartResult.FREEFORM_UNAVAILABLE
            if (wideResult == FreeformLaunchResult.FAILED) return SplitStartResult.LAUNCH_FAILED

            // Force-stop any stale fullscreen task for the narrow pane before launching (Bug A fix).
            forceStopIfNeeded(pair.narrowPkg, splitDisplayOnly = true)

            // Narrow pane launches second. Wide launched OK — revert it on any subsequent failure.
            val narrowResult = placePaneLocked(pair.narrowPkg, narrowBounds)
            if (narrowResult == FreeformLaunchResult.UNAVAILABLE) {
                revertFreeformToFullscreen(pair.widePkg)
                return SplitStartResult.FREEFORM_UNAVAILABLE
            }
            if (narrowResult == FreeformLaunchResult.FAILED) {
                revertFreeformToFullscreen(pair.widePkg)
                return SplitStartResult.LAUNCH_FAILED
            }

            // Both launched OK. Resolve task ids; revert both on failure.
            val wideTaskId = helper.getTaskState(pair.widePkg)?.taskId?.takeIf { it != -1 }
                ?: run {
                    revertFreeformToFullscreen(pair.widePkg)
                    revertFreeformToFullscreen(pair.narrowPkg)
                    return SplitStartResult.LAUNCH_FAILED
                }
            val narrowTaskId = helper.getTaskState(pair.narrowPkg)?.taskId?.takeIf { it != -1 }
                ?: run {
                    runCatching { helper.setTaskWindowingMode(wideTaskId, WINDOWING_FULLSCREEN) }
                    revertFreeformToFullscreen(pair.narrowPkg)
                    return SplitStartResult.LAUNCH_FAILED
                }

            prefs.saveLastPair(pair)
            _state.value = SplitSessionState.Active(pair, narrowTaskId, wideTaskId)
            startWatchdogLocked()
            // Freeform is confirmed live: clear the reboot hint in settings.
            onFreeformLive()
            return SplitStartResult.OK
        } finally {
            // Belt: hide backdrop if the session did not reach Active — covers explicit
            // failure returns above, unexpected exceptions, and cancellation of the
            // manager-scope job (the field-confirmed widget bug vector).
            if (_state.value !is SplitSessionState.Active) backdrop.hide()
        }
    }

    /**
     * Best-effort revert of a freeform window to fullscreen windowing mode.
     * Uses [HelperClient.getTaskState] to look up the task id at call time (the id may not be
     * known yet when launch has just succeeded). Fail-soft: any exception is swallowed.
     */
    private suspend fun revertFreeformToFullscreen(pkg: String) {
        runCatching {
            val taskId = helper.getTaskState(pkg)?.taskId ?: return@runCatching
            if (taskId != -1) helper.setTaskWindowingMode(taskId, WINDOWING_FULLSCREEN)
        }
    }

    private fun startWatchdogLocked() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch { watchdogLoop() }
        startMediaPollLocked()
    }

    private fun startMediaPollLocked() {
        mediaPollJob?.cancel()
        mediaPollJob = if (mediaSource != null) scope.launch { mediaPollLoop() } else null
    }

    /**
     * Performs a full session teardown: cancels the watchdog, returns both tasks to
     * fullscreen, hides the backdrop, sets state to Idle, and optionally emits EXIT.
     *
     * Must be called from within [mutex].
     *
     * NOTE: when called from WITHIN the watchdog coroutine (e.g. the MAXIMIZED path
     * in [handlePaneLocked]) do NOT use this helper — do the teardown steps directly
     * to avoid cancelling the currently-executing job from inside itself.
     */
    private suspend fun tearDownLocked(current: SplitSessionState.Active, emitExit: Boolean) {
        watchdogJob?.cancel()
        watchdogJob = null
        mediaPollJob?.cancel()
        mediaPollJob = null
        rerouteCount = 0
        rerouteStoodDown = false
        // Clear double-tap guard so an immediate re-start after exit is not silently suppressed.
        lastStartedPair = null
        lastStartMs = Long.MIN_VALUE
        // Clear departure-grace, calibration-pending, and post-departure noise markers (Q1).
        // D-2-R1: graceLock guards both maps so a concurrent beginClusterSend cannot interleave.
        synchronized(graceLock) { departureGraceDeadlines = emptyMap(); calibrationPendingPkgs = emptySet() }
        narrowPostDepartureNoiseDeadlineMs = Long.MIN_VALUE
        widePostDepartureNoiseDeadlineMs = Long.MIN_VALUE
        coveredTickCount = 0
        nativeSplitTickCount = 0
        // W6-F1: the pane tasks may already be gone (zombie session — the apps were killed, or a
        // COVERED teardown left them as background tasks that have since died). A throwing task op
        // must NOT abort the cleanup below: without this the backdrop stays up, the state stays
        // Active, and every later exit() repeats the same throw — an unclosable session.
        // Each op is wrapped separately so one dead task id cannot skip the other two.
        // CancellationException is rethrown so coroutine cancellation is never swallowed.
        runCatching { helper.setTaskWindowingMode(current.narrowTaskId, WINDOWING_FULLSCREEN) }
            .onFailure { if (it is CancellationException) throw it }
        runCatching { helper.setTaskWindowingMode(current.wideTaskId, WINDOWING_FULLSCREEN) }
            .onFailure { if (it is CancellationException) throw it }
        runCatching { helper.setFocusedTask(current.wideTaskId) }
            .onFailure { if (it is CancellationException) throw it }
        backdrop.hide()
        _state.value = SplitSessionState.Idle
        if (emitExit) _events.emit(SplitEvent.SessionEnded(EndReason.EXIT))
    }

    /**
     * Minimises the replaced task and refocuses both remaining session tasks.
     *
     * Flips [oldTaskId] to FULLSCREEN then calls [reAssertSplitZOrder] to bring the backdrop
     * and both panes back to the front.
     *
     * Must be called from within [mutex].
     */
    private suspend fun dismissReplacedTask(
        oldTaskId: Int,
        narrowPkg: String,
        widePkg: String,
        narrowTaskId: Int,
        wideTaskId: Int,
    ) {
        helper.setTaskWindowingMode(oldTaskId, WINDOWING_FULLSCREEN)
        reAssertSplitZOrder(narrowPkg, widePkg, narrowTaskId, wideTaskId, "dismissReplacedTask")
    }

    /**
     * Re-asserts the Z-order of an active split: brings the backdrop to front, then raises
     * both pane tasks above it via [raiseFreeformTask].
     *
     * Used both by [dismissReplacedTask] (after a fullscreen flip of the replaced task) and by
     * the media-key reroute guard (when the media center becomes the foreground top-of-stack
     * task during an active split — no windowing-mode flip is needed).
     *
     * Task-N root cause: up to 391 Task L's `--activityType 3` made pane tasks type=recents, which
     * nested as leaf tasks under a shared freeform root task. setFocusedRootTask on a leaf id is a
     * silent no-op, leaving the backdrop covering both panes. Fix: use
     * `am start --windowingMode 5 --display 0 -n <component>` (TX_RAISE_FREEFORM_TASK) — no
     * `--activityType` flag, since STANDARD is the type am assigns at task creation by default —
     * which Android recognises as "bring existing task to front" regardless of task nesting.
     * From 392 on panes are STANDARD with their own root tasks, so setFocusedRootTask would work on
     * them again — but the raise stays the primary path (it also restores a stopped pane), and
     * setFocusedTask below remains the fallback for daemons that predate TX_RAISE_FREEFORM_TASK.
     *
     * Old-daemon compatibility: if raiseFreeformTask returns false (TX unrecognised by an older
     * daemon that predates Task N), fall back to setFocusedTask — behavior then equals 386-era
     * code. The task ids are kept as fallback parameters for this purpose.
     *
     * If both raise and fallback fail for both panes, the backdrop is hidden — hiding a
     * never-fronted backdrop is harmless (SplitStageActivity's finishInstance is a no-op when
     * the instance is null). This trades an unrecoverable black screen for the cosmetic
     * pre-Task-G symptom until the next changeApp or exit.
     *
     * Must be called from within [mutex].
     */
    private suspend fun reAssertSplitZOrder(
        narrowPkg: String,
        widePkg: String,
        narrowTaskId: Int,
        wideTaskId: Int,
        caller: String,
    ) {
        // Backdrop must come to front BEFORE the pane raises — raising panes first then
        // showing backdrop would cover them again.
        val backdropOk = backdrop.show()
        if (!backdropOk) {
            Log.w(TAG, "$caller: backdrop resume timed out — z-order re-assert may be incomplete")
        }
        // Departed panes (on the cluster display, displayId > 0) must NOT be raised via
        // am start --display 0 — that would yank them off the cluster back to the main screen.
        // Short-circuit: when narrowDeparted=true the || skips raiseFreeformTask entirely.
        val narrowDeparted = narrowPaneDepartedEmitted
        val wideDeparted = widePaneDepartedEmitted
        // Try raise via TX_RAISE_FREEFORM_TASK; fall back to setFocusedTask when the daemon
        // is too old to handle the new TX (returns false) or the raise itself fails.
        val raise1 = narrowDeparted ||
            helper.raiseFreeformTask(narrowPkg, activityType = HelperBinderProtocol.PANE_TYPE_STANDARD)
        val raise2 = wideDeparted ||
            helper.raiseFreeformTask(widePkg, activityType = HelperBinderProtocol.PANE_TYPE_STANDARD)
        // Log raw raise outcomes BEFORE the fallback masks them — critical for on-car diagnosis
        // if the raise stops working (component resolution fails, am start errors, binder timeout).
        // A departed pane has raise=true by the departed flag; that path is never logged here.
        if (!raise1 || !raise2) {
            Log.w(TAG, "$caller: raiseFreeformTask failed (narrow=$raise1 wide=$raise2) — falling back to setFocusedTask")
        }
        val effective1 = raise1 || helper.setFocusedTask(narrowTaskId)
        val effective2 = raise2 || helper.setFocusedTask(wideTaskId)
        if (!effective1 || !effective2) {
            Log.w(TAG, "$caller: pane raise failed (narrow=$effective1 wide=$effective2)")
        }
        if (!effective1 && !effective2) {
            Log.w(TAG, "$caller: hiding backdrop — both pane raise calls failed")
            backdrop.hide()
        }
    }

    // ── Media-key reroute poll ─────────────────────────────────────────────────

    /**
     * Poll loop that detects when the BYD stock mediacenter surfaces fullscreen over an active
     * split (triggered by the rotary play/pause knob via BYD's MediaKeyHandler in system_server,
     * which bypasses the standard media-button-session stack and routes directly to
     * com.byd.mediacenter). Runs ONLY while the session is Active; launched by
     * [startMediaPollLocked] and cancelled by [tearDownLocked] / the MAXIMIZED watchdog path.
     *
     * Concurrency: the foreground-package pre-check ([helper.getTopTaskPackageOrSkip]) runs WITHOUT
     * holding [mutex] — it returns null immediately if HelperClient's own mutex is busy, so the
     * 10 Hz poll never queues behind [helper.setFocusedTask]'s 2 s Binder timeout. The expensive
     * [activeSessionPackages] call and all state mutation happen INSIDE [mutex] only on candidate
     * ticks. A second [helper.getTopTaskPackage] call is made inside the lock to guard against
     * stale candidates (see KDoc of [reactToMediaCenterLocked]).
     *
     * Worst-case latency breakdown (per term, so this does not regress):
     *   - [MEDIA_POLL_MS] (100 ms): fixed poll cadence.
     *   - [mutex] wait (0 – ~10.3 s): the [mutex].isLocked gate is a RACY pre-filter, not a
     *     guarantee — the poll can pass the check and then block in `mutex.withLock` for the full
     *     duration of a concurrent changeApp (~10.3 s). This term exists because the Kotlin Mutex
     *     is fair-FIFO and the poll enqueues behind any op already in the queue.
     *   - [helper.getTopTaskPackage] inside the lock (0 – 2 s): the in-lock re-read waits for
     *     HelperClient's own mutex. 19 injection sites (TrackingService sensor readBatch, 5 s budget;
     *     etc.) share that mutex. In the typical case this is near-instant; 2 s is the binder timeout.
     *   - Reaction cost under the lock (~4 s worst): backdrop.show() 2 s + raiseFreeformTask×2 1 s
     *     each (am start RTT, component lookup). setFocusedTask fallback adds 2 s per pane when raise
     *     fails (old daemon); both-fail case adds a hide() but no extra wait.
     *   Total worst case: 100 ms + 10.3 s (mutex wait) + 2 s (in-lock helper) + 4 s (reaction)
     *   ≈ 16.4 s. Typical fast path: ~200 ms (one poll + raise RTT).
     *
     * [helper.getTopTaskPackageOrSkip] pre-check guarantees the HelperClient TX does not queue
     * behind a concurrent setFocusedTask BEFORE we acquire the session mutex, eliminating one
     * contention source. The in-lock re-read still uses the queuing [helper.getTopTaskPackage]
     * because a spurious skip there (missed reroute) is worse than a slow one.
     */
    private suspend fun mediaPollLoop() {
        while (true) {
            delay(mediaPollDelayMs)
            runCatching {
                // Optimisation gate: skip the tick if the manager mutex is already held by a
                // session op (changeApp, exit, watchdog tick, or a previous reaction). Without
                // this, getTopTaskPackage() would queue on HelperClient's own mutex behind
                // setFocusedTask's 2 s budget, starving it and causing false Task G fallbacks.
                // The check is deliberately racy — a lost race costs one 100 ms tick at worst.
                if (mutex.isLocked) return@runCatching
                // Fast exit WITHOUT the mutex: skip the expensive activeSessionPackages() call
                // on every tick where mediacenter is not even the foreground task.
                // getTopTaskPackageOrSkip() returns null immediately if HelperClient's mutex is
                // busy, preventing the poll from queuing behind setFocusedTask's 2 s timeout.
                val topPkg = helper.getTopTaskPackageOrSkip()
                if (topPkg != MEDIACENTER_PKG) return@runCatching
                // Mediacenter is a candidate — acquire the mutex for the full reaction.
                mutex.withLock { reactToMediaCenterLocked() }
            }.onFailure { e ->
                // Rethrow CancellationException so coroutine cancellation is not swallowed.
                if (e is kotlinx.coroutines.CancellationException) throw e
                Log.w(TAG, "mediaPollLoop: unexpected error — skipping tick", e)
            }
        }
    }

    /**
     * Called under [mutex]: checks the trigger condition and fires the reroute reaction if met.
     *
     * Re-reads the foreground package INSIDE the lock as the first action. The outer check
     * in [mediaPollLoop] runs without the lock, so by the time we acquire it a long watchdog tick
     * or changeApp may have elapsed. Without this re-read, a stale mediacenter candidate acquired
     * before the lock wait could fire a spurious play/pause into the user's music. One extra
     * Binder call per candidate tick only (not every 100 ms).
     *
     * Counter/latch are updated BEFORE the reaction so that a throwing reaction (crash in
     * dispatchPlayPause, unexpected IPC error in reAssertSplitZOrder) does not leave the stand-down
     * latch unapplied, which would cause 10 Hz re-assertion loops until the session ends.
     */
    private suspend fun reactToMediaCenterLocked() {
        val current = _state.value as? SplitSessionState.Active ?: return

        // Re-validate inside the lock using the queuing variant (getTopTaskPackage, not OrSkip):
        // if HelperClient's mutex is contended (e.g. a 5 s readBatch from TrackingService), the
        // OrSkip path would return null and silently discard a confirmed reroute candidate. A slow
        // reroute is acceptable; a missed one fires a spurious play/pause on the next open.
        val topPkgNow = helper.getTopTaskPackage()
        if (topPkgNow != MEDIACENTER_PKG) return

        val panePkgs = setOf(current.pair.narrowPkg, current.pair.widePkg)
        // Permission-revoked or system error → empty set → trigger condition fails → no reroute.
        val sessionPkgs = runCatching { mediaSource!!.activeSessionPackages() }.getOrElse { return }

        if (!shouldReroute(
                topPkg = topPkgNow,
                splitActive = true,
                panePkgs = panePkgs,
                sessionPkgs = sessionPkgs,
                stoodDown = rerouteStoodDown,
            )
        ) return

        val controller = runCatching { mediaSource!!.findController(panePkgs) }.getOrElse { null }
        if (controller == null) return

        // Update counter/latch BEFORE the reaction so a throwing reaction still stands down.
        val reroutes = rerouteCount + 1
        rerouteCount = reroutes
        if (rerouteCount >= REROUTE_MAX_COUNT) rerouteStoodDown = true

        // Accepted limitation: a deliberate launcher-open of the stock media center during a split
        // will be bounced on the first (and possibly second) open. Logged per brief.
        Log.i(
            TAG,
            "MediaKeyRerouter: media center is foreground during active split — " +
                "rerouting to ${controller.packageName} (reroute #$reroutes)",
        )

        // Re-assert Z-order: no fullscreen flip needed (media center is already foreground);
        // reuse the shared backdrop.show() + raiseFreeformTask path from dismissReplacedTask.
        reAssertSplitZOrder(
            current.pair.narrowPkg, current.pair.widePkg,
            current.narrowTaskId, current.wideTaskId, "mediaReroute",
        )
        // Forward the play/pause toggle to the pane's media session.
        controller.dispatchPlayPause()
    }

    // ── Watchdog ──────────────────────────────────────────────────────────────

    private suspend fun watchdogLoop() {
        while (true) {
            delay(tickDelayMs)
            // Acquire mutex for the full tick so no public op races with it.
            val continueLoop = mutex.withLock { tickLocked() }
            if (!continueLoop) break
        }
    }

    /**
     * Executes one watchdog tick.
     * Returns true to keep the watchdog running, false when the session has ended.
     *
     * Must be called from within [mutex].
     */
    /**
     * Ends the session without touching the pane tasks: overlays and internal state are cleaned up
     * while the pane windows are left exactly as the system put them.
     *
     * Used by the two "someone else owns the screen now" paths — COVERED (a foreign fullscreen app
     * took over) and NATIVE_SPLIT (the head unit's own split-screen took over). Deliberately no
     * setTaskWindowingMode: the panes stay freeform in the background so music keeps playing, and
     * fighting the system shell for window modes is exactly what must not happen here.
     *
     * Must be called from within [mutex]; the caller then stops the watchdog by returning false.
     */
    private suspend fun endSessionLeavingPanesLocked(reason: EndReason) {
        mediaPollJob?.cancel()
        mediaPollJob = null
        rerouteCount = 0
        rerouteStoodDown = false
        lastStartedPair = null
        lastStartMs = Long.MIN_VALUE
        // D-2-R1: graceLock guards both maps (same as tearDownLocked).
        synchronized(graceLock) { departureGraceDeadlines = emptyMap(); calibrationPendingPkgs = emptySet() }
        narrowPostDepartureNoiseDeadlineMs = Long.MIN_VALUE
        widePostDepartureNoiseDeadlineMs = Long.MIN_VALUE
        coveredTickCount = 0
        nativeSplitTickCount = 0
        backdrop.hide()
        _state.value = SplitSessionState.Idle
        _events.emit(SplitEvent.SessionEnded(reason))
    }

    private suspend fun tickLocked(): Boolean {
        val current = _state.value as? SplitSessionState.Active ?: return false

        val narrowState = helper.getTaskState(current.pair.narrowPkg)
        val wideState = helper.getTaskState(current.pair.widePkg)

        // Transient helper failure — skip this tick entirely.
        if (narrowState == null || wideState == null) return true

        // ── Native split-screen detection (W6-F3) ────────────────────────────────
        // Our panes are freeform; a system split-screen windowing mode on either of them means the
        // user started the head unit's own split over our session. Full coexistence with the native
        // split is NOT supported — we stand down instead of fighting the system shell for windows.
        // Same 2-tick anti-flap gate as COVERED, so a single transitional reading cannot end a
        // healthy session.
        val nativeSplitObserved =
            narrowState.windowingMode in NATIVE_SPLIT_MODES || wideState.windowingMode in NATIVE_SPLIT_MODES
        if (nativeSplitObserved) {
            nativeSplitTickCount++
            Log.d(TAG, "NATIVE_SPLIT tick=$nativeSplitTickCount narrow=${narrowState.windowingMode} wide=${wideState.windowingMode}")
            if (nativeSplitTickCount >= 2) {
                endSessionLeavingPanesLocked(EndReason.NATIVE_SPLIT)
                return false
            }
        } else {
            nativeSplitTickCount = 0
        }

        // ── COVERED detection (Q3 / F-3) ─────────────────────────────────────────
        // A foreign fullscreen app covering the split means the user has moved on — end
        // the session without touching the pane tasks so music/navigation keeps running
        // in the background. Requires 2 consecutive ticks to suppress app-switch flicker.
        // Skipped when either pane is not fully alive-and-freeform on the main display
        // (departed, dead, or mid-transfer during grace).
        // C-1: single volatile read; grace keyed by package so swapApps cannot misalign deadlines.
        // C-2: require at least ONE live-freeform pane on display0 (not BOTH). A departed session
        //       (one pane on cluster) must still be COVERED when a foreign fullscreen takes over.
        //       Grace (Q1) suppresses the whole COVERED block: if any pane is mid-transfer, skip.
        val now = nowMs()
        val deadlines = departureGraceDeadlines
        val narrowGrace = now < (deadlines[current.pair.narrowPkg] ?: Long.MIN_VALUE)
        val wideGrace   = now < (deadlines[current.pair.widePkg] ?: Long.MIN_VALUE)
        val anyGraceActive = narrowGrace || wideGrace
        // Note: per-pane grace variables are still used below in handlePaneLocked.
        // NEW-4 / E-1c: suppress COVERED while any pane has a departure in progress.
        // "Departure in progress" = task alive on cluster (displayId>0) but departedEmitted
        // still false (calibration has not yet succeeded for this pane). This covers:
        //   (a) the departure tick itself — COVERED runs BEFORE handlePaneLocked, so
        //       calibrationPendingPkgs is not yet set at COVERED-check time. anyCalibrationPending
        //       missed this tick entirely, letting coveredTickCount accumulate across ticks;
        //   (b) all retry ticks (same condition remains true until calibration succeeds).
        // anyCalibrationPending removed: anyDepartureInProgress is a strict superset that
        // also closes the tick-ordering gap that anyCalibrationPending left on the departure tick.
        val narrowDepartureInProgress = narrowState.taskId != -1 && narrowState.displayId > 0 && !narrowPaneDepartedEmitted
        val wideDepartureInProgress = wideState.taskId != -1 && wideState.displayId > 0 && !widePaneDepartedEmitted
        val anyDepartureInProgress = narrowDepartureInProgress || wideDepartureInProgress
        val panesAtLeastOneAliveFreeformOnMain = !anyGraceActive && !anyDepartureInProgress && (
            (narrowState.taskId != -1 && narrowState.windowingMode == WINDOWING_FREEFORM && narrowState.displayId == 0) ||
            (wideState.taskId != -1 && wideState.windowingMode == WINDOWING_FREEFORM && wideState.displayId == 0)
        )

        if (panesAtLeastOneAliveFreeformOnMain) {
            val topTask: TopTaskInfo? = helper.getTopTask()
            if (topTask == null) {
                // Helper hiccup — no classification change this tick.
                coveredTickCount = 0
            } else {
                val foreignFullscreen =
                    topTask.displayId == 0 &&
                    topTask.windowingMode == WINDOWING_FULLSCREEN &&
                    topTask.pkg != current.pair.narrowPkg &&
                    topTask.pkg != current.pair.widePkg &&
                    topTask.pkg != OUR_PACKAGE
                if (foreignFullscreen) {
                    coveredTickCount++
                    Log.d(TAG, "COVERED tick=$coveredTickCount top=${topTask.pkg}")
                    if (coveredTickCount >= 2) {
                        // Foreign fullscreen covered the split for 2 ticks in a row.
                        endSessionLeavingPanesLocked(EndReason.COVERED)
                        return false
                    }
                } else {
                    coveredTickCount = 0
                }
            }
        } else {
            coveredTickCount = 0
        }
        // ─────────────────────────────────────────────────────────────────────────

        val (wideBounds, narrowBounds) = boundsFor(current.pair.narrowSide)

        // Classify narrow pane first.
        val narrowEnded = handlePaneLocked(
            taskState = narrowState,
            taskId = current.narrowTaskId,
            pane = Pane.NARROW,
            pkg = current.pair.narrowPkg,
            expectedBounds = narrowBounds,
            otherTaskId = current.wideTaskId,
        )
        if (narrowEnded) return false

        // Re-snapshot; narrow handling might have changed the state.
        val afterNarrow = _state.value as? SplitSessionState.Active ?: return false

        // Classify wide pane.
        val wideEnded = handlePaneLocked(
            taskState = wideState,
            taskId = afterNarrow.wideTaskId,
            pane = Pane.WIDE,
            pkg = afterNarrow.pair.widePkg,
            expectedBounds = wideBounds,
            otherTaskId = afterNarrow.narrowTaskId,
        )
        return !wideEnded
    }

    /**
     * Classifies and handles a single pane's watchdog result.
     * Returns true when the session was terminated and the watchdog loop must stop.
     *
     * Must be called from within [mutex].
     *
     * MAXIMIZED path: does NOT call [tearDownLocked] (that would cancel the currently
     * running watchdog job from within itself). Instead it replicates the teardown
     * steps directly; the watchdog loop exits naturally when tickLocked returns false.
     */
    private suspend fun handlePaneLocked(
        taskState: SplitTaskState,
        taskId: Int,
        pane: Pane,
        pkg: String,
        expectedBounds: SplitBounds,
        otherTaskId: Int,
    ): Boolean {
        // Departure grace (Q1 / F-1 / C-1): while BYDMate's own cluster send is in flight, the pane
        // task transiently dies then reappears on the cluster display (REMOVE+RELAUNCH). Suppress
        // the MAXIMIZED and task-death branches during this window. The departed branch resolves it.
        // C-1: grace keyed by package so swapApps does not transfer grace to the wrong pane.
        val graceDeadline = departureGraceDeadlines[pkg] ?: Long.MIN_VALUE
        val graceActive = nowMs() < graceDeadline

        return when {
            taskState.taskId == -1 -> {
                if (graceActive) {
                    // REMOVE step of REMOVE+RELAUNCH: suppress death handling. The new task on
                    // the cluster will soon produce a displayId>0 tick that resolves the grace.
                    Log.d(TAG, "departure grace suppressed task-death for ${pane.name}")
                    return false
                }
                // Task died — "departed to cluster" is no longer valid for this pane. Clear the
                // flag so a subsequent reAssert does not skip raising the replacement into the
                // vacated slot. PaneClosed is not re-emitted (narrowPaneClosedEmitted stays true
                // from the departed branch that fired earlier, so alreadyReported will be true).
                when (pane) {
                    Pane.NARROW -> narrowPaneDepartedEmitted = false
                    Pane.WIDE -> widePaneDepartedEmitted = false
                }
                // D-1-R3: clear any pending calibration marker for the dead task. Without this,
                // a failed calibration followed by task death leaves calibrationPendingPkgs set
                // forever, permanently blocking swapApps/mirror for the remainder of the session.
                synchronized(graceLock) { calibrationPendingPkgs = calibrationPendingPkgs - pkg }
                // App closed — emit PaneClosed once per closure (edge-trigger).
                val alreadyReported = when (pane) {
                    Pane.NARROW -> narrowPaneClosedEmitted
                    Pane.WIDE -> widePaneClosedEmitted
                }
                if (!alreadyReported) {
                    when (pane) {
                        Pane.NARROW -> narrowPaneClosedEmitted = true
                        Pane.WIDE -> widePaneClosedEmitted = true
                    }
                    _events.emit(SplitEvent.PaneClosed(pane))
                }
                false
            }
            // Departed branch: task is alive but has moved to another display (displayId > 0).
            // Checked BEFORE WINDOWING_FULLSCREEN so a native-mechanism flip to FULLSCREEN on
            // the cluster is not misread as the user maximising the pane on the main screen.
            // Display.INVALID_DISPLAY (-1) is excluded (mid-reparent transient, not a real
            // departure) — only a confirmed positive display id triggers this path.
            // Never snap bounds back — doing so would fight the native mechanism.
            taskState.taskId != -1 && taskState.displayId > 0 -> {
                // Departure confirmed: resolve any active grace (the new task is on the cluster).
                if (graceActive) {
                    // C-1: clear by package. D-2: graceLock against concurrent beginClusterSend.
                    synchronized(graceLock) { departureGraceDeadlines = departureGraceDeadlines - pkg }
                    Log.d(TAG, "departure grace resolved by confirmed departure for ${pane.name}")
                }
                val alreadyDeparted = when (pane) {
                    Pane.NARROW -> narrowPaneDepartedEmitted
                    Pane.WIDE -> widePaneDepartedEmitted
                }
                if (!alreadyDeparted) {
                    // E-1: arm the post-departure noise window HERE — at first confirmed departure,
                    // BEFORE the calibration attempt — so that stale fullscreen@display0 readings
                    // (getTaskState cache lag) cannot pass the MAXIMIZED gate while calibration is
                    // retrying. Without this, the noise window is only armed after calibration
                    // SUCCESS, leaving an unprotected window between departure confirmation and
                    // successful calibration. The window is refreshed on every retry tick (every
                    // time we enter this !alreadyDeparted block) so stale reads during retries are
                    // always covered.
                    val noiseDeadline = nowMs() + POST_DEPARTURE_NOISE_MS
                    when (pane) {
                        Pane.NARROW -> narrowPostDepartureNoiseDeadlineMs = noiseDeadline
                        Pane.WIDE -> widePostDepartureNoiseDeadlineMs = noiseDeadline
                    }

                    // D-1 / D-1-R1: apply cluster geometry BEFORE marking the pane as departed.
                    // CPM's runCatching only wraps setTaskBounds; resolveClusterDisplay,
                    // readSizePct, readOffsetPct, and geometryFor can still throw. Any non-CE
                    // exception from the hook must be caught here so it cannot propagate through
                    // tickLocked into the watchdogLoop's bare scope.launch (no try/catch there) and
                    // kill the watchdog silently. Treat any throw as false = retry next tick. (D-1-R2)
                    // A null hook means no calibration is needed (success).
                    // On false/throw: arm the calibrationPendingPkgs marker so swapApps/mirror are
                    // blocked, then return false to let the next watchdog tick retry.
                    // On true or null: clear any pending marker and proceed to emit PaneClosed.
                    val calibrated = runCatching { applyCalibratedBounds?.invoke(taskState.taskId, taskState.displayId) }
                        .onFailure { if (it is CancellationException) throw it }
                        .getOrDefault(false) != false
                    if (!calibrated) {
                        synchronized(graceLock) { calibrationPendingPkgs = calibrationPendingPkgs + pkg }
                        Log.w(TAG, "applyCalibratedBounds returned false; will retry next tick (task=${taskState.taskId})")
                        return false  // retry on next watchdog tick
                    }
                    // Calibration succeeded (or hook is absent). Clear any pending retry marker.
                    synchronized(graceLock) { calibrationPendingPkgs = calibrationPendingPkgs - pkg }

                    when (pane) {
                        // Set both departed/closed flags only AFTER calibration succeeds:
                        // a departed task that later dies must not emit a second PaneClosed (M-3).
                        // Noise window was already armed above (before calibration); no change here.
                        Pane.NARROW -> { narrowPaneDepartedEmitted = true; narrowPaneClosedEmitted = true }
                        Pane.WIDE -> { widePaneDepartedEmitted = true; widePaneClosedEmitted = true }
                    }
                    // Open the replacement picker in the vacated pane — the departed task lives
                    // on the cluster, the split slot is visually empty on the main screen.
                    _events.emit(SplitEvent.PaneClosed(pane))
                }
                false  // session stays Active; no teardown
            }
            taskState.windowingMode == WINDOWING_FULLSCREEN -> {
                // Q1 / F-1 + fix-round F-Q1-1: suppress MAXIMIZED during departure grace or within
                // the bounded post-departure noise window. During grace: REMOVE+RELAUNCH makes the
                // task transiently fullscreen@display0. Within the noise window (POST_DEPARTURE_NOISE_MS
                // after confirmed departure): getTaskState lag may still return display0 for a task
                // that is actually live on the cluster. After the noise window expires, fullscreen@display0
                // means the pane genuinely returned from the cluster → treat as MAXIMIZED (session ends).
                // This replaces the old unbounded `paneAlreadyDeparted` guard which made the session
                // non-terminable once a pane had departed (absorbing state — F-Q1-1).
                val postDepartureNoiseDeadline = if (pane == Pane.NARROW) narrowPostDepartureNoiseDeadlineMs
                                                 else widePostDepartureNoiseDeadlineMs
                val inNoiseWindow = nowMs() < postDepartureNoiseDeadline
                // E-1a noise window suppresses stale fullscreen@display0 cache readings during
                // the bounded post-departure lag (POST_DEPARTURE_NOISE_MS). Refreshed on every
                // retry tick that goes through the departed branch (displayId>0). Once the window
                // expires a genuine fullscreen@display0 correctly ends the session.
                //
                // E-1b (calibrationPendingPkgs in this gate) was removed (NEW-1): it created an
                // absorbing state — FULLSCREEN@display0 reaches this branch only when displayId==0,
                // which is unreachable by the departed branch (displayId>0). The else branch
                // (return-to-main, E-3) clears the marker but is also unreachable for fullscreen.
                // Result: one failed calibration + pane returning home as fullscreen = session
                // impossible to close via MAXIMIZED or COVERED (F-Q1-1 third occurrence).
                // E-1a alone covers the bounded cache-lag window; longer retry sequences are
                // suppressed by anyDepartureInProgress in the COVERED gate (NEW-4/E-1c).
                if (graceActive || inNoiseWindow) {
                    Log.d(TAG, "departure grace/noise suppressed MAXIMIZED for ${pane.name} (grace=$graceActive noise=$inNoiseWindow)")
                    return false
                }
                // User maximised this pane — treat as implicit session exit.
                // Do NOT call tearDownLocked: it would cancel this coroutine's own job.
                // The watchdog loop exits naturally (tickLocked returns false → break).
                // Cancel the media poll separately (safe: cancelling a coroutine from a
                // coroutine is not self-cancellation since mediaPollJob is a sibling, not
                // an ancestor of the watchdog job).
                mediaPollJob?.cancel()
                mediaPollJob = null
                rerouteCount = 0
                rerouteStoodDown = false
                // Clear double-tap guard so an immediate re-start after maximize is not silently suppressed.
                lastStartedPair = null
                lastStartMs = Long.MIN_VALUE
                // Clear departure-grace and post-departure noise markers — no transfer survives session end (Q1).
                // D-2: graceLock against concurrent beginClusterSend.
                // D-1-R3: calibrationPendingPkgs must be cleared alongside grace so swapApps/mirror
                // are not blocked at the start of the next session (marker lives ≤ grace lifetime).
                synchronized(graceLock) { departureGraceDeadlines = emptyMap(); calibrationPendingPkgs = emptySet() }
                narrowPostDepartureNoiseDeadlineMs = Long.MIN_VALUE
                widePostDepartureNoiseDeadlineMs = Long.MIN_VALUE
                coveredTickCount = 0
                val current = _state.value as? SplitSessionState.Active
                if (current != null) {
                    // Read the top package before flipping the other pane. If the maximized
                    // pane is still on top we re-front it after the flip (the daemon's
                    // am-stack-remove + am-start relaunch brings the other app forward last).
                    // Mirrors the in-lock re-read pattern in reactToMediaCenterLocked.
                    val topPkg = helper.getTopTaskPackage()
                    val maximizedPkg = if (pane == Pane.NARROW) current.pair.narrowPkg else current.pair.widePkg
                    helper.setTaskWindowingMode(otherTaskId, WINDOWING_FULLSCREEN)
                    // Re-front the maximized pane only when the user's app was on top —
                    // if topPkg is null (old daemon) or differs, do not steal focus.
                    if (topPkg == maximizedPkg) {
                        var firstOk = false
                        runCatching { firstOk = helper.setFocusedTask(taskState.taskId) }
                            .onFailure { if (it is CancellationException) throw it }
                        if (!firstOk) {
                            delay(FOCUS_RETRY_DELAY_MS)
                            var retryOk = false
                            runCatching { retryOk = helper.setFocusedTask(taskState.taskId) }
                                .onFailure { if (it is CancellationException) throw it }
                            if (!retryOk) {
                                Log.w(TAG, "MAXIMIZED refocus: setFocusedTask failed after retry (task=${taskState.taskId})")
                            }
                        }
                    } else {
                        Log.d(TAG, "MAXIMIZED refocus: gate closed (top=$topPkg maximizedPkg=$maximizedPkg)")
                    }
                    backdrop.hide()
                    _state.value = SplitSessionState.Idle
                }
                _events.emit(SplitEvent.SessionEnded(EndReason.MAXIMIZED))
                true
            }
            else -> {
                if (graceActive) {
                    // Task alive on display 0 mid-transfer: bounds snap is premature — the task is
                    // between the REMOVE on this display and the RELAUNCH on the cluster display.
                    return false
                }
                // Task is alive on the main display (displayId == 0); clear any stale edge-trigger
                // flags so that a future departure is treated as a fresh event, and a returned-from-
                // cluster task gets its bounds snapped back into the session geometry.
                when (pane) {
                    Pane.NARROW -> { narrowPaneClosedEmitted = false; narrowPaneDepartedEmitted = false }
                    Pane.WIDE -> { widePaneClosedEmitted = false; widePaneDepartedEmitted = false }
                }
                // E-3: the task returned to the main display before calibration succeeded. Clear
                // the calibrationPendingPkgs marker for this pane so that swapApps/mirror are no
                // longer blocked. The pane is back home; nothing to calibrate.
                synchronized(graceLock) { calibrationPendingPkgs = calibrationPendingPkgs - pkg }
                // Snap bounds back if the task drifted (silent correction).
                if (taskState.windowingMode == WINDOWING_FREEFORM &&
                    taskState.toBounds() != expectedBounds
                ) {
                    helper.setTaskBounds(taskId, expectedBounds)
                }
                false
            }
        }
    }
}
