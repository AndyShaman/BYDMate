package com.bydmate.app.media

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * The Navigator's «Поехали» button — the one the driver presses to turn a built route into
 * active guidance — read and pressed through the a11y tree the HUD already walks.
 *
 * Matched by TEXT, never by the coordinates uiautomator reports: they move with the window
 * (split panes, cluster projection). The text node itself is not clickable on the 2026 build,
 * so the click goes to the nearest clickable ancestor.
 */
object NaviGoButton {

    /** Is the route-preview screen with «Поехали» on it right now? */
    fun visible(root: AccessibilityNodeInfo?): Boolean = root != null && node(root) != null

    /** Presses it; false when the button is not there or the framework refused the click. */
    fun click(root: AccessibilityNodeInfo?): Boolean {
        val target = root?.let { node(it) }
        if (target == null) {
            Log.i(TAG, "go button: not on screen")
            return false
        }
        var candidate: AccessibilityNodeInfo? = target
        var hops = 0
        while (candidate != null && !candidate.isClickable && hops < PARENT_HOPS) {
            candidate = runCatching { candidate?.parent }.getOrNull()
            hops++
        }
        val clickable = candidate ?: target
        val clicked = runCatching {
            clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }.getOrDefault(false)
        Log.i(TAG, "go button: click=$clicked hops=$hops clickable=${clickable.isClickable}")
        return clicked
    }

    /** The «Поехали» text node, compared without case or spaces. */
    private fun node(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        runCatching { root.findAccessibilityNodeInfosByText(GO_TEXT) }.getOrNull()
            ?.firstOrNull { candidate ->
                candidate.text?.toString()?.replace(" ", "")
                    ?.equals(GO_TEXT.replace(" ", ""), ignoreCase = true) == true
            }

    private const val TAG = "NaviGoButton"

    /** Label of the start-guidance button on the route preview screen. */
    private const val GO_TEXT = "Поехали"

    /** How far up from the text node to look for the clickable button around it. */
    private const val PARENT_HOPS = 6
}
