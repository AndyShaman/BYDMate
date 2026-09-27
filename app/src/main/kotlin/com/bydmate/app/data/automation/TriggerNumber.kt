package com.bydmate.app.data.automation

/**
 * The number a param condition compares against. A decimal comma is accepted («12,5» is 12.5),
 * the way the head unit keyboard types it; anything that is not a finite number is null, so the
 * editor, the agent and the engine all read a value the same way.
 */
internal object TriggerNumber {
    fun parse(value: String): Double? =
        value.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
}
