package com.bydmate.app.navdata

import android.view.accessibility.AccessibilityNodeInfo

/** Extracts guidance widgets from a Navigator a11y tree into NavGuidance.
 *  View ids are package-relative (the .inhouse / .rustore builds prefix them with their
 *  own package), so the prefix is derived from the root node. Every found node is
 *  recycled after reading: the a11y feed fires many times per second and the framework
 *  node pool is finite on DiLink (Codex fix 5). */
object NavA11yExtractor {

    sealed class ReadResult {
        object NotNavigator : ReadResult()
        object NoGuidance : ReadResult()
        data class Guidance(val data: NavGuidance) : ReadResult()

        override fun toString(): String = this::class.java.simpleName
    }

    fun read(root: AccessibilityNodeInfo?): ReadResult {
        if (root == null) return ReadResult.NotNavigator
        val pkg = root.packageName?.toString() ?: return ReadResult.NotNavigator
        if (pkg !in NavPackages.YANDEX_NAVI) return ReadResult.NotNavigator
        val raw = NavGuidanceParser.RawFields(
            maneuverDesc = descOf(root, "$pkg:id/image_maneuverballoon_maneuver"),
            exitNumber = textOf(root, "$pkg:id/exit_number_text"),
            distance = textOf(root, "$pkg:id/text_maneuverballoon_distance"),
            distanceUnit = textOf(root, "$pkg:id/text_maneuverballoon_metrics"),
            nextStreet = textOf(root, "$pkg:id/text_nextstreet"),
            statusPanel = textOf(root, "$pkg:id/status_panel_text"),
            etaTime = descOrTextOf(root, "$pkg:id/textview_eta_time"),
            etaDistance = textOf(root, "$pkg:id/textview_eta_distance"),
            speedLimit = textOf(root, "$pkg:id/text_speedlimit"),
        )
        val parsed = NavGuidanceParser.parse(raw) ?: return ReadResult.NoGuidance
        return ReadResult.Guidance(parsed)
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
