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
        val displayId = eventDisplayId(event)
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

    /** Display the event's window is on, null when the event carries none or below API 30. */
    fun eventDisplayId(event: AccessibilityEvent): Int? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { event.source?.window?.displayId }.getOrNull()
    }

    /**
     * Whether [pkg] has a window on the main display (#303). UsageStats events carry no display,
     * so the cluster's own activities read as the head unit's foreground; only the window list
     * tells them apart. null when display 0 cannot be read (API < 30, list unreadable).
     */
    fun mainDisplayPresence(service: AccessibilityService, pkg: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val byDisplay = runCatching { service.windowsOnAllDisplays }.getOrNull() ?: return null
        val main = byDisplay.get(Display.DEFAULT_DISPLAY) ?: return null
        val entries = main.map { WindowEntry(runCatching { it.type }.getOrDefault(-1), windowPackage(it)) }
        return presenceFrom(entries, pkg)
    }

    /** A window on display 0: its [type] (AccessibilityWindowInfo.TYPE_*) and root package,
     *  null when the root cannot be read. */
    data class WindowEntry(val type: Int, val pkg: String?)

    /**
     * true when [pkg] owns a readable window, false when other windows are readable and none is
     * [pkg]'s. null when nothing is readable, or when an application window cannot be read: that
     * one may be [pkg]'s own, so it cannot prove the app absent.
     */
    fun presenceFrom(windows: List<WindowEntry>, pkg: String): Boolean? {
        if (windows.any { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.pkg == null }) return null
        val packages = windows.mapNotNull { it.pkg }
        if (packages.isEmpty()) return null
        return pkg in packages
    }

    /** A foreground change counts for every widget rule but the camera unless display 0 denies it. */
    fun confirmsForeground(onMain: Boolean?): Boolean = onMain != false

    /** (has a window on the main display, has one on another display), null when unreadable. */
    private fun packageOnDisplays(
        service: AccessibilityService,
        pkg: String,
    ): Pair<Boolean, Boolean>? {
        // getWindowsOnAllDisplays() is API 30; on Android 10 (DiLink 3.0) there is only the
        // main display anyway, so «unreadable» keeps the pre-existing pass-through behaviour.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
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
