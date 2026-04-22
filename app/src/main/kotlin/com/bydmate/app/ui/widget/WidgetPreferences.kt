package com.bydmate.app.ui.widget

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin wrapper around the "bydmate_widget" SharedPreferences file.
 * Keeps the keys in one place and exposes a Flow so Settings UI can react
 * live to the drag-to-trash gesture flipping `enabled` off.
 */
class WidgetPreferences(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun getX(): Int = prefs.getInt(KEY_X, 0)
    fun getY(): Int = prefs.getInt(KEY_Y, 0)

    fun savePosition(x: Int, y: Int) {
        prefs.edit().putInt(KEY_X, x).putInt(KEY_Y, y).apply()
    }

    fun resetPosition() {
        prefs.edit().remove(KEY_X).remove(KEY_Y).apply()
    }

    fun enabledFlow(): Flow<Boolean> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == KEY_ENABLED) {
                trySend(isEnabled())
            }
        }
        trySend(isEnabled())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    companion object {
        const val PREFS_NAME = "bydmate_widget"
        const val KEY_ENABLED = "floating_widget_enabled"
        const val KEY_X = "widget_x"
        const val KEY_Y = "widget_y"
    }
}
