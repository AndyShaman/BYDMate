package com.bydmate.app.ui.tech

import androidx.compose.ui.graphics.Color
import com.bydmate.app.ui.theme.AccentGreen
import com.bydmate.app.ui.theme.SocRed
import com.bydmate.app.ui.theme.SocYellow
import com.bydmate.app.ui.theme.TextPrimary

/**
 * Colour cues for the technical readings, moved here 1:1 from the battery-health dialog
 * the «Техника» screen replaced.
 *
 * Cell imbalance is gated to the LFP plateau (SOC 20-80%) where the delta is actually
 * diagnostic — BYD's own HV battery check runs in this band with a 90 mV threshold. Near
 * full charge the leader cell hits the voltage knee first while the rest sit lower, so a
 * large delta there is normal LFP physics, not a defect; the value stays neutral outside
 * the band to avoid false "battery problem" alarms (issue #113).
 */
internal fun cellDeltaColor(delta: Double?, soc: Int?): Color {
    if (delta == null) return TextPrimary
    if (soc == null || soc !in 20..80) return TextPrimary
    // Round to millivolt precision so exact 50/90 mV boundaries survive IEEE 754
    // subtraction noise (3.43 - 3.34 = 0.0899999…).
    val mv = Math.round(delta * 1000.0) / 1000.0
    return when {
        mv < 0.050 -> AccentGreen
        mv < 0.090 -> SocYellow
        else -> SocRed
    }
}

/**
 * Low-voltage colour cue for the minimum cell. BYD Blade is LFP: the pack sits on a flat
 * ~3.2 V plateau and only drops past the "knee" below ~3.0 V/cell, with the BMS cutoff
 * around 2.5 V. A weak / imbalanced cell dips into the knee first, which is the early
 * warning for the sudden ~10% → 0 SOC collapse users report.
 */
internal fun cellMinVoltageColor(min: Double?): Color = when {
    min == null -> TextPrimary
    min >= 3.0 -> AccentGreen
    min >= 2.8 -> SocYellow
    else -> SocRed
}

/** LFP calendar-aging cue (approved mock): <65 green, 65-85 yellow, >85 red. */
internal fun avgSocColor(v: Int?): Color = when {
    v == null -> TextPrimary
    v < 65 -> AccentGreen
    v <= 85 -> SocYellow
    else -> SocRed
}

/** Insulation resistance in МОм: ≥1 healthy, 0.5-1 watch, below that the pack leaks to the body. */
internal fun insulationColor(mohm: Double?): Color = when {
    mohm == null -> TextPrimary
    mohm >= 1.0 -> AccentGreen
    mohm >= 0.5 -> SocYellow
    else -> SocRed
}

/** 12 V bus, same thresholds the Dashboard status uses. */
internal fun voltage12vColor(v: Double?): Color = when {
    v == null -> TextPrimary
    v < 11.8 -> SocRed
    v < 12.4 -> SocYellow
    else -> AccentGreen
}
