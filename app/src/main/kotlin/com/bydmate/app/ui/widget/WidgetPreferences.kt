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

    init { migrateLegacyLeftTapKey() }

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

    fun getAlpha(): Float = prefs.getFloat(KEY_ALPHA, 1.0f)

    fun setAlpha(alpha: Float) {
        val clamped = alpha.coerceIn(0.3f, 1.0f)
        prefs.edit().putFloat(KEY_ALPHA, clamped).apply()
    }

    fun alphaFlow(): Flow<Float> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == KEY_ALPHA) trySend(getAlpha())
        }
        trySend(getAlpha())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    fun getScale(): Float = prefs.getFloat(KEY_SCALE, 1.0f)

    fun setScale(scale: Float) {
        val clamped = scale.coerceIn(SCALE_MIN, SCALE_MAX)
        prefs.edit().putFloat(KEY_SCALE, clamped).apply()
    }

    fun scaleFlow(): Flow<Float> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == KEY_SCALE) trySend(getScale())
        }
        trySend(getScale())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    /**
     * Transient flag: when true, widget stays hidden until user opens MainActivity.
     * Triggered by long-press on the widget. Cleared in onActivityResumed.
     */
    fun isHiddenUntilAppLaunch(): Boolean = prefs.getBoolean(KEY_HIDDEN_UNTIL_LAUNCH, false)

    fun setHiddenUntilAppLaunch(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_HIDDEN_UNTIL_LAUNCH, hidden).apply()
    }

    /**
     * When true, the LEFT third of the widget launches the configured app
     * (default Yandex Navigator). The rest opens BYDMate. When false, a tap
     * anywhere opens BYDMate — historical behaviour.
     */
    fun isLeftTapZoningEnabled(): Boolean =
        prefs.getBoolean(KEY_LEFT_TAP_ZONING, false)

    fun setLeftTapZoningEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_LEFT_TAP_ZONING, enabled).apply()
    }

    fun getLeftTapAppPackage(): String =
        prefs.getString(KEY_LEFT_TAP_APP_PKG, DEFAULT_LEFT_TAP_APP_PKG) ?: DEFAULT_LEFT_TAP_APP_PKG

    fun getLeftTapAppLabel(): String =
        prefs.getString(KEY_LEFT_TAP_APP_LABEL, DEFAULT_LEFT_TAP_APP_LABEL) ?: DEFAULT_LEFT_TAP_APP_LABEL

    fun setLeftTapApp(packageName: String, label: String) {
        prefs.edit()
            .putString(KEY_LEFT_TAP_APP_PKG, packageName)
            .putString(KEY_LEFT_TAP_APP_LABEL, label)
            .apply()
    }

    data class LeftTapAppState(
        val enabled: Boolean,
        val packageName: String,
        val label: String,
    )

    fun leftTapAppFlow(): Flow<LeftTapAppState> = callbackFlow {
        fun snapshot() = LeftTapAppState(
            enabled = isLeftTapZoningEnabled(),
            packageName = getLeftTapAppPackage(),
            label = getLeftTapAppLabel(),
        )
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changedKey ->
            if (changedKey == KEY_LEFT_TAP_ZONING ||
                changedKey == KEY_LEFT_TAP_APP_PKG ||
                changedKey == KEY_LEFT_TAP_APP_LABEL
            ) {
                trySend(snapshot())
            }
        }
        trySend(snapshot())
        prefs.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    private fun migrateLegacyLeftTapKey() {
        if (!prefs.contains(LEGACY_KEY_LEFT_TAP_NAVIGATOR)) return
        val legacy = prefs.getBoolean(LEGACY_KEY_LEFT_TAP_NAVIGATOR, false)
        prefs.edit()
            .putBoolean(KEY_LEFT_TAP_ZONING, legacy)
            .remove(LEGACY_KEY_LEFT_TAP_NAVIGATOR)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "bydmate_widget"
        const val KEY_ENABLED = "floating_widget_enabled"
        const val KEY_X = "widget_x"
        const val KEY_Y = "widget_y"
        const val KEY_ALPHA = "widget_alpha"
        const val KEY_SCALE = "widget_scale"
        const val KEY_HIDDEN_UNTIL_LAUNCH = "widget_hidden_until_launch"
        const val LEGACY_KEY_LEFT_TAP_NAVIGATOR = "widget_left_tap_navigator"
        const val KEY_LEFT_TAP_ZONING = "widget_left_tap_zoning"
        const val KEY_LEFT_TAP_APP_PKG = "widget_left_tap_app_pkg"
        const val KEY_LEFT_TAP_APP_LABEL = "widget_left_tap_app_label"
        const val DEFAULT_LEFT_TAP_APP_PKG = "ru.yandex.yandexnavi"
        const val DEFAULT_LEFT_TAP_APP_LABEL = "Яндекс.Навигатор"
        const val SCALE_MIN = 0.7f
        const val SCALE_MAX = 2.0f
    }
}
