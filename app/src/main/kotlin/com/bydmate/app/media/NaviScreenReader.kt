package com.bydmate.app.media

import android.view.accessibility.AccessibilityNodeInfo
import com.bydmate.app.navdata.NavA11yExtractor

/** On-demand a11y read of the Navigator screen.
 *
 *  Originally added for speed limit and exit number (absent from the guidance notification).
 *  As of the 2026 Navigator build (Maps engine, package ru.yandex.yandexnavi) the app posts a
 *  static stub notification ("Навигатор запущен", contentView=null, never updated), so this
 *  screen reader is now the FALLBACK data source for all guidance fields: maneuver distance,
 *  remaining distance / time, arrival time, current street.  Notification (NaviRouteHolder) stays
 *  primary when it carries real data (older Navigator builds).
 *
 *  View ids are stable on the 2026 build (validated live during active guidance twice); they may
 *  vanish with a future Navigator update, so every field is optional. */
object NaviScreenReader {
    data class ScreenInfo(
        val speedLimit: String?,
        val exitNumber: String?,
        val maneuverDistance: String?,   // "250 м" — distance + metrics joined, trimmed
        val remainingDistance: String?,  // "28 км"
        val remainingTime: String?,      // "27 мин"
        val arrivalTime: String?,        // "10:10"
        val street: String?,             // "ул. Качаны"
    )

    fun read(root: AccessibilityNodeInfo?): ScreenInfo? {
        if (root == null) return null
        val pkg = root.packageName?.toString() ?: return null
        if (pkg !in com.bydmate.app.navdata.NavPackages.GUIDANCE_SOURCES) return null
        val maneuverDist = textOf(root, "$pkg:id/${NavA11yExtractor.DISTANCE_ID}")
        val maneuverMetrics = textOf(root, "$pkg:id/${NavA11yExtractor.METRICS_ID}")
        return ScreenInfo(
            speedLimit = textOf(root, "$pkg:id/${NavA11yExtractor.SPEED_LIMIT_ID}"),
            exitNumber = textOf(root, "$pkg:id/${NavA11yExtractor.EXIT_NUMBER_ID}"),
            maneuverDistance = maneuverDist?.let { (it + (maneuverMetrics ?: "")).trim() },
            remainingDistance = textOf(root, "$pkg:id/${NavA11yExtractor.ETA_DISTANCE_ID}"),
            remainingTime = textOf(root, "$pkg:id/${NavA11yExtractor.ETA_TIME_ID}"),
            arrivalTime = textOf(root, "$pkg:id/textview_eta_arrival"),
            street = textOf(root, "$pkg:id/${NavA11yExtractor.STATUS_ID}"),
        )
    }

    private fun textOf(root: AccessibilityNodeInfo, viewId: String): String? = runCatching {
        root.findAccessibilityNodeInfosByViewId(viewId)
            ?.firstOrNull()?.text?.toString()?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
