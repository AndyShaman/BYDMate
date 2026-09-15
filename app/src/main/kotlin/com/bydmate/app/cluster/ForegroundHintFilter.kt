package com.bydmate.app.cluster

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * Whether a window-state accessibility event may be read as "this app now owns the main screen".
 *
 * Events arrive from every display. With the navigation projected onto the instrument cluster, a
 * dialog opening there reports the navigator as the window that changed while the main screen is
 * still showing the native 360 view — taken at face value, that would bring the blind-spot window
 * back over the camera. Missing a hint costs nothing (the UsageStats poll is right behind it),
 * so anything that clearly belongs to another display is dropped.
 */
object ForegroundHintFilter {
    private const val TAG = "SteeringWheelKeySvc"

    /**
     * Decision for a real event: the display the event came from when it carries a window, the
     * package's own windows otherwise. Every accessibility call is guarded — a window that cannot
     * be read simply leaves the question open. No tree is walked, one root node per window.
     */
    fun allows(service: AccessibilityService, event: AccessibilityEvent, pkg: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return true
        val displayId = runCatching { event.source?.window?.displayId }.getOrNull()
        val presence = if (displayId == null) packageOnDisplays(service, pkg) else null
        val allowed = shouldForwardHint(displayId, presence?.first, presence?.second)
        if (!allowed) Log.d(TAG, "foreground hint dropped: $pkg display=${displayId ?: "unknown"}")
        return allowed
    }

    /**
     * [eventDisplayId] null means the event carried no window to ask. Then the decision falls back
     * to where the package has windows: [pkgOnMainDisplay] / [pkgOnOtherDisplay], both null when
     * the window list could not be read — in which case the hint goes through as before.
     */
    fun shouldForwardHint(
        eventDisplayId: Int?,
        pkgOnMainDisplay: Boolean?,
        pkgOnOtherDisplay: Boolean?,
    ): Boolean {
        if (eventDisplayId != null) return eventDisplayId == Display.DEFAULT_DISPLAY
        return !(pkgOnOtherDisplay == true && pkgOnMainDisplay == false)
    }

    /** (has a window on the main display, has one on another display), null when unreadable. */
    private fun packageOnDisplays(
        service: AccessibilityService,
        pkg: String,
    ): Pair<Boolean, Boolean>? {
        val byDisplay = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return null
        var onMain = false
        var onOther = false
        for (index in 0 until byDisplay.size()) {
            if (byDisplay.valueAt(index).none { windowPackage(it) == pkg }) continue
            if (byDisplay.keyAt(index) == Display.DEFAULT_DISPLAY) onMain = true else onOther = true
        }
        return onMain to onOther
    }

    /** Package of a window's root node, or null when it has none or cannot be read. */
    private fun windowPackage(window: AccessibilityWindowInfo): String? {
        val root = runCatching { window.root }.getOrNull() ?: return null
        val pkg = runCatching { root.packageName?.toString() }.getOrNull()
        @Suppress("DEPRECATION") runCatching { root.recycle() }
        return pkg
    }
}
