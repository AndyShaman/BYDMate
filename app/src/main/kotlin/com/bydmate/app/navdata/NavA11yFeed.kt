package com.bydmate.app.navdata

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService

/** Passive a11y event feed: Navigator window events -> NavGuidanceHub.
 *  Gated by [enabled] (set by HudController) so that users without the HUD feature
 *  pay a single volatile read per event. Debounced: guidance widgets update ~1/s,
 *  a11y events fire far more often. */
@Suppress("TooManyFunctions") // the event, timer and source reads plus their diagnostic probes
object NavA11yFeed {
    private const val TAG = "NavA11yFeed"
    private const val DEBOUNCE_MS = 500L
    /** Cycle guard for the parent climb: a stale tree can hand back a looping chain. */
    private const val MAX_PARENT_HOPS = 64
    /** Lane-widget research dump: bounded so a deep tree cannot stall the a11y thread
     *  or flood logcat. Fires once per maneuver, i.e. about once a minute while driving. */
    private const val TREE_DUMP_MAX_NODES = 300
    private const val TREE_DUMP_MAX_CHARS = 3500
    /** Floor between two walks, independent of the maneuver code: the extractor can
     *  alternate codes faster than the driver passes intersections. */
    private const val TREE_DUMP_MIN_INTERVAL_MS = 30_000L
    private const val NO_MANEUVER = Int.MIN_VALUE
    /** Navigator windows described per no-guidance streak, so a stray window list stays short. */
    private const val TRACE_MAX_WINDOWS = 6
    /** A standing car changes nothing on screen, so no events come and the hub would expire
     *  the maneuver (MANEUVER_TIMEOUT_MS) while the navigator still shows it: the window is
     *  re-read when no read happened for this long, looked at on the same period. */
    private const val TIMER_READ_MS = 5_000L

    /** Re-enabling starts a fresh diagnostic episode: the transition-only flags below
     *  would otherwise survive a HUD off/on cycle and swallow the first edge log. */
    @Volatile var enabled: Boolean = false
        set(value) {
            if (value && !field) {
                rootReachable = true
                sourceFallbackWorking = false
                lastDumpedGaode = NO_MANEUVER
                lastDumpMs = 0L
                unknownManeuvers.reset()
                noGuidanceTrace.reset()
                timerKeptAlive = false
                resetTimer(start = true)
            }
            // A blind spell does not outlive the feed it was seen by.
            if (value != field) NavGuidanceHub.a11yBlind(false)
            field = value
            if (!value) resetTimer(start = false)
        }

    /** Where the tree dump goes; logcat in production, a collector in tests. */
    internal var treeDumpSink: (String) -> Unit = { Log.i(TAG, it) }
    /** Where the no-guidance trace goes; logcat in production, a collector in tests. */
    internal var traceSink: (String) -> Unit = { Log.i(TAG, it) }

    @Volatile internal var lastProcessMs = 0L
    // Transition-only log guard: "events flowing but window unreachable" is exactly the
    // agent-blindness symptom, but it repeats every debounce tick — log edges, not ticks.
    @Volatile private var rootReachable = true
    // Same edge discipline for the event-source fallback, tracked separately so the
    // field logs say which of the two paths is feeding the hub.
    @Volatile private var sourceFallbackWorking = false
    // Maneuver the tree was last dumped for; NO_MANEUVER means "nothing dumped yet".
    @Volatile private var lastDumpedGaode = NO_MANEUVER
    @Volatile internal var lastDumpMs = 0L
    // Raw maneuver values logged as unrecognised and when; its floor is the walk's lastDumpMs.
    private val unknownManeuvers = UnknownManeuverGate(minIntervalMs = 0L)
    // Streak, walk floor and missing-window edge of the no-guidance trace (issue #199).
    private val noGuidanceTrace = NoGuidanceTrace()
    // Edge guard for the timer's keep-alive line: set by the first timer refresh of a quiet
    // episode, cleared by the next event read.
    @Volatile private var timerKeptAlive = false
    // Main-looper handler of the current enable; a tick from an older one stops itself.
    @Volatile private var timer: Handler? = null

    /** Where timer reads get the window from; the live a11y service in production. */
    internal var timerService: () -> SteeringWheelKeyService? = { SteeringWheelKeyService.instance }

    fun onEvent(service: SteeringWheelKeyService, event: AccessibilityEvent?) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        if (!shouldProcess(event?.packageName?.toString(), event?.eventType ?: 0, nowMs, lastProcessMs)) return
        lastProcessMs = nowMs
        timerKeptAlive = false
        // An unreachable window says NOTHING about the route: the navigator may be
        // minimized, covered by another pane, or projected onto a private VirtualDisplay
        // while guidance keeps running (field-confirmed, issue #144). A navigator that
        // really closed is caught reader-side: ACTIVE_TIMEOUT_MS drops active when no
        // source refreshes, MANEUVER_TIMEOUT_MS expires the arrow, and the notification
        // lane's removal grace deactivates too.
        val root = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: run {
                val readViaSource = readViaEventSource(event, nowMs)
                if (readViaSource) {
                    if (!sourceFallbackWorking) {
                        sourceFallbackWorking = true
                        Log.i(TAG, "navigator window hidden; guidance read via event source")
                        NavGuidanceHub.a11yBlind(false, nowMs)
                    }
                } else if (rootReachable || sourceFallbackWorking) {
                    sourceFallbackWorking = false
                    Log.w(TAG, "Navigator events flowing but window unreachable (a11y feed blind)")
                    NavGuidanceHub.a11yBlind(true, nowMs)
                }
                rootReachable = false
                return
            }
        if (!rootReachable) {
            rootReachable = true
            sourceFallbackWorking = false
            Log.i(TAG, "Navigator window reachable again")
            NavGuidanceHub.a11yBlind(false, nowMs)
        }
        readWindow(root, nowMs, "event", service)
    }

    /** Timer tick (main looper, like the events): the same window read as [onEvent] when a
     *  route is guided and neither events nor the timer read for [TIMER_READ_MS]. An
     *  unreachable window says nothing here either, and there is no event source to fall
     *  back to. */
    internal fun onTimer(service: SteeringWheelKeyService?, nowMs: Long) {
        if (!shouldTimerRead(enabled, NavGuidanceHub.snapshot(nowMs).active, nowMs, lastProcessMs)) return
        service ?: return
        lastProcessMs = nowMs
        val root = runCatching { service.findNavigatorRoot() }.getOrNull() ?: run {
            if (noGuidanceTrace.navigatorMissing()) traceSink("timer read: navigator window not found")
            return
        }
        if (readWindow(root, nowMs, "timer", service) && !timerKeptAlive) {
            timerKeptAlive = true
            Log.i(TAG, "timer re-read keeps maneuver alive")
        }
    }

    /** Reads a reachable navigator [root] into the hub and recycles it: widgets with text =
     *  guidance; widgets without text only keep the route alive, as in the donor; a navigator
     *  without them changes nothing (issue #199). Both of the latter are traced. True when
     *  guidance was read.
     *  [src] and [service] only feed the no-guidance trace. */
    private fun readWindow(
        root: AccessibilityNodeInfo,
        nowMs: Long,
        src: String,
        service: SteeringWheelKeyService,
    ): Boolean {
        noGuidanceTrace.navigatorFound()
        try {
            return when (val result = NavA11yExtractor.read(root)) {
                is NavA11yExtractor.ReadResult.Guidance -> {
                    NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
                    dumpTreeOnManeuverChange(root, result.data.maneuverGaode, nowMs)
                    logUnknownManeuver(root, result.data, nowMs)
                    traceGuidanceAgain(src, nowMs)
                    true
                }
                is NavA11yExtractor.ReadResult.NoGuidance -> {
                    val kept = NavA11yExtractor.hasGuidanceNodes(root)
                    NavGuidanceHub.countA11yNoGuidance()
                    if (kept) NavGuidanceHub.keepAlive(nowMs)
                    traceNoGuidance(root, nowMs, src, service, kept)
                    false
                }
                is NavA11yExtractor.ReadResult.NotNavigator -> false
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Drops the current enable's timer; [start] posts a fresh one. */
    private fun resetTimer(start: Boolean) {
        timer?.removeCallbacksAndMessages(null)
        timer = null
        if (!start) return
        val handler = Handler(Looper.getMainLooper())
        timer = handler
        handler.postDelayed(object : Runnable {
            override fun run() {
                if (timer !== handler) return
                onTimer(timerService(), System.currentTimeMillis())
                handler.postDelayed(this, TIMER_READ_MS)
            }
        }, TIMER_READ_MS)
    }

    /** Last resort when window enumeration cannot see the Navigator (projected onto a
     *  PRIVATE VirtualDisplay on DiLink 5.1, field-confirmed issue #134): the event still
     *  carries a live source node whose parent chain reaches the window root.
     *  ONLY a positive guidance read is accepted — that root may be a sub-window (balloon,
     *  dialog) where missing widgets say nothing about the route, so this path only adds
     *  data; widgets without text keep an active route alive, as in [readWindow]. Returns
     *  true when the hub was fed. */
    private fun readViaEventSource(event: AccessibilityEvent?, nowMs: Long): Boolean {
        val root = climbToWindowRoot(runCatching { event?.source }.getOrNull()) ?: return false
        try {
            // read() re-checks the package: the climb can land in a host window that merely
            // embeds the Navigator, and a foreign root reads as NotNavigator.
            val result = NavA11yExtractor.read(root)
            if (result is NavA11yExtractor.ReadResult.NoGuidance && NavA11yExtractor.hasGuidanceNodes(root)) {
                NavGuidanceHub.keepAlive(nowMs)
            }
            if (result !is NavA11yExtractor.ReadResult.Guidance) return false
            NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
            dumpTreeOnManeuverChange(root, result.data.maneuverGaode, nowMs)
            logUnknownManeuver(root, result.data, nowMs)
            traceGuidanceAgain("event", nowMs)
            return true
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Highest reachable ancestor of [node] (the node itself when it has no parent).
     *  Intermediate nodes are recycled on the way up; the returned one is the caller's. */
    private fun climbToWindowRoot(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var current = node ?: return null
        repeat(MAX_PARENT_HOPS) {
            val parent = runCatching { current.parent }.getOrNull() ?: return current
            @Suppress("DEPRECATION")
            runCatching { current.recycle() }
            current = parent
        }
        return current
    }

    /** Research probe for the lane-guidance widget: on a new maneuver, list the ids the
     *  Navigator exposes to accessibility so a recorded log shows whether the lane hint is
     *  in the tree at all. [root] belongs to the caller and is never recycled here. */
    private fun dumpTreeOnManeuverChange(
        root: AccessibilityNodeInfo,
        maneuverGaode: Int,
        nowMs: Long,
    ) {
        // A blinked-out maneuver description reads as 0 while the route keeps running, so
        // it is neither dumped nor remembered: 2 -> 0 -> 2 must stay one dump.
        if (maneuverGaode <= 0) return
        if (maneuverGaode == lastDumpedGaode) return
        if (nowMs - lastDumpMs < TREE_DUMP_MIN_INTERVAL_MS) return
        lastDumpedGaode = maneuverGaode
        lastDumpMs = nowMs
        runCatching {
            val ids = StringBuilder()
            appendIds(root, ids, intArrayOf(TREE_DUMP_MAX_NODES))
            treeDumpSink("nav tree [gaode=$maneuverGaode]:${ids.take(TREE_DUMP_MAX_CHARS)}")
        }
    }

    /** A guided route with a distance but no recognised maneuver: one line with the raw
     *  maneuver node and one id walk per distinct value per 5 min, so a recorded log shows what the
     *  parse could not map. Waits for the same floor as [dumpTreeOnManeuverChange], checked
     *  before the probe so a read inside it costs no extra lookup. [root] is the caller's. */
    private fun logUnknownManeuver(root: AccessibilityNodeInfo, data: NavGuidance, nowMs: Long) {
        if (!UnknownManeuverGate.applies(data.distanceMeters, data.maneuverGaode) { NavGuidanceHub.snapshot(nowMs).active }) return
        if (nowMs - lastDumpMs < TREE_DUMP_MIN_INTERVAL_MS) return
        val node = NavA11yExtractor.probeManeuver(root)
        if (!unknownManeuvers.take(node, nowMs)) return
        lastDumpMs = nowMs
        runCatching {
            treeDumpSink("nav maneuver unknown [a11y]: $node")
            val ids = StringBuilder()
            appendIds(root, ids, intArrayOf(TREE_DUMP_MAX_NODES))
            treeDumpSink("nav tree [gaode=0]:${ids.take(TREE_DUMP_MAX_CHARS)}")
        }
    }

    /** Issue #199: a window read without guidance that starts a streak while a route is guided
     *  logs what the read saw (window, navigator windows, widget counts, no text), at most once
     *  per 5 s, then the id walk with its node count behind its own 60 s floor. The route state
     *  is read without expiries, so the trace writes nothing into the hub. [kept]: the read kept
     *  the route alive (widgets without text). [root] is the caller's. */
    private fun traceNoGuidance(
        root: AccessibilityNodeInfo,
        nowMs: Long,
        src: String,
        service: SteeringWheelKeyService,
        kept: Boolean,
    ) {
        val skipped = noGuidanceTrace.startStreak(nowMs) { NavGuidanceHub.isActiveNow() } ?: return
        val windows = runCatching { service.navigatorWindowRoots() }.getOrNull()
        try {
            runCatching {
                traceSink("no-guidance read: src=$src ${NavA11yExtractor.windowFacts(root)} " +
                    "navWindows=${windows?.size ?: "?"} skipped=$skipped kept=$kept ${NavA11yExtractor.countIds(root)}")
                traceWindows(root, windows.orEmpty())
                if (!noGuidanceTrace.takeDump(nowMs)) return@runCatching
                val ids = StringBuilder()
                val budget = intArrayOf(TREE_DUMP_MAX_NODES)
                appendIds(root, ids, budget)
                val walked = TREE_DUMP_MAX_NODES - budget[0]
                val nodes = if (budget[0] <= 0) "$walked+" else "$walked"
                traceSink("nav tree [no-guidance nodes=$nodes]:${ids.take(TREE_DUMP_MAX_CHARS)}")
            }
        } finally {
            @Suppress("DEPRECATION")
            windows?.forEach { runCatching { it.recycle() } }
        }
    }

    /** One line per Navigator window (at most [TRACE_MAX_WINDOWS]): the guidance widgets may
     *  live in another window than the one [SteeringWheelKeyService.findNavigatorRoot] handed
     *  to the read (issue #199). [read] and [windows] stay the caller's. */
    private fun traceWindows(read: AccessibilityNodeInfo, windows: List<AccessibilityNodeInfo>) {
        val readId = runCatching { read.windowId }.getOrNull()
        for (window in windows.take(TRACE_MAX_WINDOWS)) {
            runCatching {
                val isRead = readId != null && runCatching { window.windowId }.getOrNull() == readId
                traceSink("no-guidance window: ${NavA11yExtractor.windowFacts(window)} read=$isRead " +
                    NavA11yExtractor.countIds(window))
            }
        }
    }

    /** The guidance read that ends a no-guidance streak says how long the streak lasted. */
    private fun traceGuidanceAgain(src: String, nowMs: Long) {
        val lasted = noGuidanceTrace.endStreak(nowMs) ?: return
        traceSink("guidance read again after $lasted ms (src=$src)")
    }

    /** Depth-first, [budget] nodes at most; only ids are collected, text is reduced to its
     *  length (screen text is the driver's route, not diagnostic data). */
    private fun appendIds(node: AccessibilityNodeInfo, out: StringBuilder, budget: IntArray) {
        if (budget[0] <= 0) return
        budget[0]--
        runCatching { node.viewIdResourceName }.getOrNull()?.let { id ->
            out.append(' ').append(id.substringAfter(":id/"))
            val textLength = runCatching { node.text?.length }.getOrNull() ?: 0
            if (textLength > 0) out.append(":t").append(textLength)
        }
        val children = runCatching { node.childCount }.getOrDefault(0)
        for (i in 0 until children) {
            if (budget[0] <= 0) return
            val child = runCatching { node.getChild(i) }.getOrNull() ?: continue
            try {
                appendIds(child, out, budget)
            } finally {
                @Suppress("DEPRECATION")
                runCatching { child.recycle() }
            }
        }
    }

    /** Pure gate of the timer read: the feed on, a route guided, no read for [TIMER_READ_MS]. */
    fun shouldTimerRead(enabled: Boolean, guidanceActive: Boolean, nowMs: Long, lastMs: Long): Boolean =
        enabled && guidanceActive && nowMs - lastMs >= TIMER_READ_MS

    /** Pure gate, unit-tested separately from the framework-bound onEvent. */
    fun shouldProcess(pkg: String?, eventType: Int, nowMs: Long, lastMs: Long): Boolean {
        if (pkg == null || pkg !in NavPackages.GUIDANCE_SOURCES) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        return nowMs - lastMs >= DEBOUNCE_MS
    }
}
