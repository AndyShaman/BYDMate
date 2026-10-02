package com.bydmate.app.navdata

import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo

/** Extracts guidance widgets from a Navigator a11y tree into NavGuidance.
 *  View ids are package-relative (the .inhouse / .rustore builds prefix them with their
 *  own package), so the prefix is derived from the root node. Every found node is
 *  recycled after reading: the a11y feed fires many times per second and the framework
 *  node pool is finite on DiLink (Codex fix 5). */
object NavA11yExtractor {

    /** The maneuver image; read by the parse and by [probeManeuver]. */
    private const val MANEUVER_ID = "image_maneuverballoon_maneuver"
    private const val DISTANCE_ID = "text_maneuverballoon_distance"
    private const val METRICS_ID = "text_maneuverballoon_metrics"
    private const val NEXT_STREET_ID = "text_nextstreet"
    private const val STATUS_ID = "status_panel_text"
    private const val ETA_TIME_ID = "textview_eta_time"
    /** The widgets the parse decides guidance by, counted by [countIds] under these names. */
    private val TRACED_IDS = listOf(
        "maneuver" to MANEUVER_ID, "distance" to DISTANCE_ID, "metrics" to METRICS_ID,
        "nextstreet" to NEXT_STREET_ID, "status" to STATUS_ID, "eta" to ETA_TIME_ID,
    )

    sealed class ReadResult {
        object NotNavigator : ReadResult()
        object NoGuidance : ReadResult()
        data class Guidance(val data: NavGuidance) : ReadResult()

        override fun toString(): String = this::class.java.simpleName
    }

    fun read(root: AccessibilityNodeInfo?): ReadResult {
        if (root == null) return ReadResult.NotNavigator
        val pkg = root.packageName?.toString() ?: return ReadResult.NotNavigator
        if (pkg !in NavPackages.GUIDANCE_SOURCES) return ReadResult.NotNavigator
        val raw = NavGuidanceParser.RawFields(
            maneuverDesc = descOf(root, "$pkg:id/$MANEUVER_ID"),
            exitNumber = textOf(root, "$pkg:id/exit_number_text"),
            distance = textOf(root, "$pkg:id/$DISTANCE_ID"),
            distanceUnit = textOf(root, "$pkg:id/$METRICS_ID"),
            nextStreet = textOf(root, "$pkg:id/$NEXT_STREET_ID"),
            statusPanel = textOf(root, "$pkg:id/$STATUS_ID"),
            etaTime = descOrTextOf(root, "$pkg:id/$ETA_TIME_ID"),
            etaDistance = textOf(root, "$pkg:id/textview_eta_distance"),
            speedLimit = textOf(root, "$pkg:id/text_speedlimit"),
        )
        val parsed = NavGuidanceParser.parse(raw) ?: return ReadResult.NoGuidance
        return ReadResult.Guidance(parsed)
    }

    /** Raw view of the maneuver image for the unknown-maneuver log: how many nodes carry its id,
     *  and the class and contentDescription head of the one the parse reads (the first non-blank,
     *  else the first). The nodes are recycled; [root] stays the caller's. */
    internal fun probeManeuver(root: AccessibilityNodeInfo): String {
        val pkg = root.packageName?.toString() ?: return "found=0"
        val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$MANEUVER_ID") }
            .getOrNull().orEmpty()
        try {
            if (nodes.isEmpty()) return "found=0"
            val descs = nodes.map { runCatching { it.contentDescription?.toString() }.getOrNull() }
            val read = descs.indexOfFirst { !it.isNullOrBlank() }.coerceAtLeast(0)
            val cls = runCatching { nodes[read].className?.toString() }.getOrNull()
            return "found=${nodes.size} class=$cls desc=${UnknownManeuverGate.textHead(descs[read])}"
        } finally {
            @Suppress("DEPRECATION")
            nodes.forEach { runCatching { it.recycle() } }
        }
    }

    /** The window [root] is drawn in, for the no-guidance log: display (API 30+), window id,
     *  type and its active/focused flags; `?` for what cannot be read. [root] stays the caller's. */
    internal fun windowFacts(root: AccessibilityNodeInfo): String {
        val windowId = runCatching { root.windowId }.getOrNull()
        val window = runCatching { root.window }.getOrNull()
        try {
            val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching { window?.displayId }.getOrNull()
            } else null
            val type = runCatching { window?.type }.getOrNull()
            val active = runCatching { window?.isActive }.getOrNull()
            val focused = runCatching { window?.isFocused }.getOrNull()
            return "display=${display ?: "?"} window=${windowId ?: "?"} type=${type ?: "?"} " +
                "active=${active ?: "?"} focused=${focused ?: "?"}"
        } finally {
            @Suppress("DEPRECATION")
            runCatching { window?.recycle() }
        }
    }

    /** How many nodes carry each id the parse decides guidance by, for the no-guidance log:
     *  counts only, `?` for a lookup that threw. The nodes are recycled; [root] stays the caller's. */
    internal fun countIds(root: AccessibilityNodeInfo): String {
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        return TRACED_IDS.joinToString(" ", prefix = "ids[", postfix = "]") { (name, id) ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByViewId("$pkg:id/$id") }.getOrNull()
            @Suppress("DEPRECATION")
            nodes?.forEach { runCatching { it.recycle() } }
            "$name=${nodes?.size ?: "?"}"
        }
    }

    private fun textOf(root: AccessibilityNodeInfo, viewId: String): String? =
        readNodes(root, viewId) { it.text?.toString() }

    private fun descOf(root: AccessibilityNodeInfo, viewId: String): String? =
        readNodes(root, viewId) { it.contentDescription?.toString() }

    private fun descOrTextOf(root: AccessibilityNodeInfo, viewId: String): String? =
        readNodes(root, viewId) { it.contentDescription?.toString() ?: it.text?.toString() }

    private inline fun readNodes(
        root: AccessibilityNodeInfo,
        viewId: String,
        crossinline extract: (AccessibilityNodeInfo) -> String?,
    ): String? = runCatching {
        val nodes = root.findAccessibilityNodeInfosByViewId(viewId) ?: return@runCatching null
        var value: String? = null
        for (node in nodes) {
            // extract() guarded per node: a stale node throwing must not leak the rest unrecycled
            if (value == null) value = runCatching { extract(node) }.getOrNull()?.takeIf { it.isNotBlank() }
            @Suppress("DEPRECATION")
            runCatching { node.recycle() }
        }
        value
    }.getOrNull()
}
