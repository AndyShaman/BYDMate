@file:Suppress("MatchingDeclarationName") // the unit plus the two helpers that convert a pause

package com.bydmate.app.ui.automation

import com.bydmate.app.data.automation.ActionDispatcher
import kotlin.math.roundToLong

// A pause is stored in ms; the editor shows it as a number plus a unit («5 мин», «1,5 ч»).

internal enum class DelayUnit(val ms: Long) { SECONDS(1_000), MINUTES(60_000), HOURS(3_600_000) }

private val DELAY_NUMBER = Regex("""\d*[.,]?\d*""")

/** The largest unit that shows [ms] with at most one decimal: 90 000 → 1,5 min, 3 660 000 → 61 min. */
internal fun splitDelay(ms: Long): Pair<Double, DelayUnit> = when {
    ms >= DelayUnit.HOURS.ms && ms % (DelayUnit.HOURS.ms / 10) == 0L -> ms.toDouble() / DelayUnit.HOURS.ms to DelayUnit.HOURS
    ms >= DelayUnit.MINUTES.ms && ms % (DelayUnit.MINUTES.ms / 10) == 0L -> ms.toDouble() / DelayUnit.MINUTES.ms to DelayUnit.MINUTES
    else -> ms.toDouble() / DelayUnit.SECONDS.ms to DelayUnit.SECONDS
}

/** «1,5» in hours → 5 400 000; null for blank, garbage, negative or above 24 h. */
internal fun parseDelayMs(text: String, unit: DelayUnit): Long? {
    val t = text.trim()
    if (t.isEmpty() || !DELAY_NUMBER.matches(t)) return null
    val value = t.replace(',', '.').toDoubleOrNull() ?: return null
    val ms = (value * unit.ms).roundToLong()
    return ms.takeIf { it in 0..ActionDispatcher.MAX_DELAY_MS }
}
