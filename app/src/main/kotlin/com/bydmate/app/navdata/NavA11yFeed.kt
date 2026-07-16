package com.bydmate.app.navdata

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.bydmate.app.cluster.SteeringWheelKeyService

/** Passive a11y event feed: Navigator window events -> NavGuidanceHub.
 *  Gated by [enabled] (set by HudController) so that users without the HUD feature
 *  pay a single volatile read per event. Debounced: guidance widgets update ~1/s,
 *  a11y events fire far more often. */
object NavA11yFeed {
    private const val TAG = "NavA11yFeed"
    private const val DEBOUNCE_MS = 500L

    @Volatile var enabled: Boolean = false

    @Volatile internal var lastProcessMs = 0L
    // Transition-only log guard: "events flowing but window unreachable" is exactly the
    // agent-blindness symptom, but it repeats every debounce tick — log edges, not ticks.
    @Volatile private var rootReachable = true

    fun onEvent(service: SteeringWheelKeyService, event: AccessibilityEvent?) {
        if (!enabled) return
        val nowMs = System.currentTimeMillis()
        if (!shouldProcess(event?.packageName?.toString(), event?.eventType ?: 0, nowMs, lastProcessMs)) return
        lastProcessMs = nowMs
        // A Navigator event with no reachable window is a route-ended signal too: the
        // guidance widgets are gone. Start the no-guidance streak so the hub deadline
        // can expire even if no further events arrive (Codex audit fix 2).
        val root = runCatching { service.findNavigatorRoot() }.getOrNull()
            ?: run {
                if (rootReachable) {
                    rootReachable = false
                    Log.w(TAG, "Navigator events flowing but window unreachable (a11y feed blind)")
                }
                NavGuidanceHub.markNoGuidance(nowMs)
                return
            }
        if (!rootReachable) {
            rootReachable = true
            Log.i(TAG, "Navigator window reachable again")
        }
        try {
            when (val result = NavA11yExtractor.read(root)) {
                is NavA11yExtractor.ReadResult.Guidance ->
                    NavGuidanceHub.update(result.data, NavGuidanceHub.Source.A11Y, nowMs)
                is NavA11yExtractor.ReadResult.NoGuidance ->
                    NavGuidanceHub.markNoGuidance(nowMs)
                is NavA11yExtractor.ReadResult.NotNavigator -> Unit
            }
        } finally {
            @Suppress("DEPRECATION")
            runCatching { root.recycle() }
        }
    }

    /** Pure gate, unit-tested separately from the framework-bound onEvent. */
    fun shouldProcess(pkg: String?, eventType: Int, nowMs: Long, lastMs: Long): Boolean {
        if (pkg == null || pkg !in NavPackages.YANDEX_NAVI) return false
        if (eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false
        return nowMs - lastMs >= DEBOUNCE_MS
    }
}
