package com.bydmate.app.navdata

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.cluster.SteeringWheelKeyService

/** Passive a11y event feed: Navigator window events -> NavGuidanceHub.
 *  Gated by [enabled] (set by HudController) so that users without the HUD feature
 *  pay a single volatile read per event. Debounced: guidance widgets update ~1/s,
 *  a11y events fire far more often. */
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

    /** Re-enabling starts a fresh diagnostic episode: the transition-only flags below
     *  would otherwise survive a HUD off/on cycle and swallow the first edge log. */
    @Volatile var enabled: Boolean = false
        set(value) {
            if (value && !field) {
                rootReachable = true
                sourceFallbackWorking = false
                lastDumpedGaode = NO_MANEUVER
                lastDumpMs = 0L
            }
            field = value
        }

    /** Where the tree dump goes; logcat in production, a collector in tests. */
    internal var treeDumpSink: (String) -> Unit = { Log.i(TAG, it) }

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

    fun onEvent(service: SteeringWheelKeyService, event: AccessibilityEvent?) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        if (!shouldProcess(event?.packageName?.toString(), event?.eventType ?: 0, nowMs, lastProcessMs)) return
        lastProcessMs = nowMs
        // An unreachable window says NOTHING about the route: the navigator may be
        // minimized, covered by another pane, or projected onto a private VirtualDisplay
        // while guidance keeps running (field-confirmed, issue #144). Only a REACHABLE
        // window without guidance widgets means "route ended", so this branch must
        // neither arm nor cancel the no-guidance streak. A navigator that really closed
        // is still caught reader-side: an armed streak expires in snapshot(),
        // ACTIVE_TIMEOUT_MS drops active when no source refreshes, MANEUVER_TIMEOUT_MS
        // expires the arrow, and the notification lane's removal grace deactivates too.
        val root = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: run {
                val readViaSource = readViaEventSource(event, nowMs)
                if (readViaSource) {
                    if (!sourceFallbackWorking) {
                        sourceFallbackWorking = true
                        Log.i(TAG, "navigator window hidden; guidance read via event source")
                    }
                } else if (rootReachable || sourceFallbackWorking) {
                    sourceFallbackWorking = false
                    Log.w(TAG, "Navigator events flowing but window unreachable (a11y feed blind)")
                }
                rootReachable = false
                return
            }
        if (!rootReachable) {
            rootReachable = true
            sourceFallbackWorking = false
            Log.i(TAG, "Navigator window reachable again")
        }
        try {
            when (val result = NavA11yExtractor.read(root)) {
                is NavA11yExtractor.ReadResult.Guidance -> {
                    NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
                    dumpTreeOnManeuverChange(root, result.data.maneuverGaode, nowMs)
                }
                is NavA11yExtractor.ReadResult.NoGuidance ->
                    NavGuidanceHub.markNoGuidance(nowMs)
                is NavA11yExtractor.ReadResult.NotNavigator -> Unit
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Last resort when window enumeration cannot see the Navigator (projected onto a
     *  PRIVATE VirtualDisplay on DiLink 5.1, field-confirmed issue #134): the event still
     *  carries a live source node whose parent chain reaches the window root.
     *  ONLY a positive guidance read is accepted — that root may be a sub-window (balloon,
     *  dialog) where missing widgets say nothing about the route, so this path may add
     *  data but must never end guidance (no markNoGuidance here). Returns true when the
     *  hub was fed. */
    private fun readViaEventSource(event: AccessibilityEvent?, nowMs: Long): Boolean {
        val root = climbToWindowRoot(runCatching { event?.source }.getOrNull()) ?: return false
        try {
            // read() re-checks the package: the climb can land in a host window that merely
            // embeds the Navigator, and a foreign root reads as NotNavigator.
            val result = NavA11yExtractor.read(root)
            if (result !is NavA11yExtractor.ReadResult.Guidance) return false
            NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
            dumpTreeOnManeuverChange(root, result.data.maneuverGaode, nowMs)
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

    /** Pure gate, unit-tested separately from the framework-bound onEvent. */
    fun shouldProcess(pkg: String?, eventType: Int, nowMs: Long, lastMs: Long): Boolean {
        if (pkg == null || pkg !in NavPackages.GUIDANCE_SOURCES) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        return nowMs - lastMs >= DEBOUNCE_MS
    }
}
