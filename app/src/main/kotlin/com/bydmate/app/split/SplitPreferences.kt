package com.bydmate.app.split

import android.content.Context
import android.content.SharedPreferences

/** Which side of the screen holds the narrow (one-third) pane. */
enum class SplitSide { LEFT, RIGHT }

/** An app pair ready to fill the split layout: [narrowPkg] in one-third, [widePkg] in two-thirds. */
data class SplitPair(
    val narrowPkg: String,
    val widePkg: String,
    val narrowSide: SplitSide,
)

/**
 * Two pairs: the MENU pair the user picks in Settings (persisted) and the SESSION pair, the last
 * one actually launched in this process (in memory). The widget / voice agent / steering key
 * restore [getLastPair] without the user having to pick apps again; a new process (every car
 * power cycle) falls back to the menu pair.
 *
 * Backed by SharedPreferences file "bydmate_split".
 */
@Suppress("TooManyFunctions") // two pairs, the feature switches and the split key, one prefs file
interface SplitPreferences {
    /** The pair a start-from-saved opens: session pair if set, else the menu pair; null = none. */
    fun getLastPair(): SplitPair?

    /** A launch/swap/adoption: sets the session pair; also persists it as the menu pair when that is empty. */
    fun saveLastPair(pair: SplitPair)
    fun clearLastPair()

    /** The pair picked in Settings → Разделение экрана. */
    fun getMenuPair(): SplitPair? = getLastPair()

    /** Settings pickers: persists the menu pair and drops the session pair (the menu change wins). */
    fun saveMenuPair(pair: SplitPair) = saveLastPair(pair)

    /** Settings «Очистить пару»: removes the menu pair and the session pair. */
    fun clearMenuPair() = clearLastPair()

    /** The last pair launched in this process, or null. */
    fun getSessionPair(): SplitPair? = null

    /** Master switch: true when the split-screen feature is enabled (default false). */
    fun isFeatureEnabled(): Boolean

    /** Persists the master switch state. */
    fun setFeatureEnabled(enabled: Boolean)

    /**
     * True when a start must hand the pair to the FIRMWARE's own split instead of our freeform
     * layout (#139 firmwares where the freeform flag is gated and our split cannot run at all).
     *
     * Defaults to false everywhere — the whole fleet keeps the freeform path byte-identical.
     */
    fun isNativeModeEnabled(): Boolean = false

    /** Persists the native-mode switch state. */
    fun setNativeModeEnabled(enabled: Boolean) {}

    /** Steering-wheel keycode that toggles the split (Settings → Разделение экрана); 0 = none. */
    fun getSteeringKeyCode(): Int = 0

    /** Persists the split key; 0 removes it. */
    fun setSteeringKeyCode(keyCode: Int) {}

    /** True when a press of the split key does something: feature on and a key assigned. */
    fun isSteeringKeyActive(): Boolean = isFeatureEnabled() && getSteeringKeyCode() > 0
}

/**
 * No-op default used by ClusterProjectionManager before the production SplitPreferences
 * instance is wired in. Returns false for isFeatureEnabled() so the freeform flag
 * computation is byte-identical to the pre-split behavior.
 */
internal object DisabledSplitPreferences : SplitPreferences {
    override fun getLastPair(): SplitPair? = null
    override fun saveLastPair(pair: SplitPair) {}
    override fun clearLastPair() {}
    override fun isFeatureEnabled(): Boolean = false
    override fun setFeatureEnabled(enabled: Boolean) {}
    override fun isNativeModeEnabled(): Boolean = false
    override fun setNativeModeEnabled(enabled: Boolean) {}
    override fun getSteeringKeyCode(): Int = 0
    override fun setSteeringKeyCode(keyCode: Int) {}
}

/** SharedPreferences-backed production implementation of [SplitPreferences]. */
@Suppress("TooManyFunctions") // mirrors the interface
class SplitPreferencesImpl(context: Context) : SplitPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** In memory only: one process = one car power cycle (owner, 2026-10-26). */
    @Volatile private var sessionPair: SplitPair? = null

    override fun getLastPair(): SplitPair? = sessionPair ?: getMenuPair()

    override fun saveLastPair(pair: SplitPair) {
        sessionPair = pair
        if (getMenuPair() == null) writeMenuPair(pair)
    }

    override fun clearLastPair() = clearMenuPair()

    override fun getSessionPair(): SplitPair? = sessionPair

    override fun saveMenuPair(pair: SplitPair) {
        sessionPair = null
        writeMenuPair(pair)
    }

    override fun getMenuPair(): SplitPair? {
        val narrowPkg = prefs.getString(KEY_NARROW_PKG, null) ?: return null
        val widePkg = prefs.getString(KEY_WIDE_PKG, null) ?: return null
        val side = when (prefs.getString(KEY_NARROW_SIDE, DEFAULT_SIDE)) {
            "left" -> SplitSide.LEFT
            else -> SplitSide.RIGHT
        }
        return SplitPair(narrowPkg, widePkg, side)
    }

    private fun writeMenuPair(pair: SplitPair) {
        prefs.edit()
            .putString(KEY_NARROW_PKG, pair.narrowPkg)
            .putString(KEY_WIDE_PKG, pair.widePkg)
            .putString(KEY_NARROW_SIDE, pair.narrowSide.name.lowercase())
            .apply()
    }

    override fun clearMenuPair() {
        sessionPair = null
        prefs.edit()
            .remove(KEY_NARROW_PKG)
            .remove(KEY_WIDE_PKG)
            .remove(KEY_NARROW_SIDE)
            .apply()
    }

    override fun isFeatureEnabled(): Boolean =
        prefs.getBoolean(KEY_FEATURE_ENABLED, false)

    override fun setFeatureEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FEATURE_ENABLED, enabled).apply()
    }

    override fun isNativeModeEnabled(): Boolean =
        prefs.getBoolean(KEY_NATIVE_MODE, false)

    override fun setNativeModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_NATIVE_MODE, enabled).apply()
    }

    override fun getSteeringKeyCode(): Int = prefs.getInt(KEY_STEERING_KEYCODE, 0)

    override fun setSteeringKeyCode(keyCode: Int) {
        prefs.edit().putInt(KEY_STEERING_KEYCODE, keyCode).apply()
    }

    companion object {
        const val PREFS_NAME = "bydmate_split"
        const val KEY_NARROW_PKG = "narrow_pkg"
        const val KEY_WIDE_PKG = "wide_pkg"
        const val KEY_NARROW_SIDE = "narrow_side"
        const val KEY_FEATURE_ENABLED = "feature_enabled"
        const val KEY_NATIVE_MODE = "native_mode"
        const val KEY_STEERING_KEYCODE = "steering_keycode"
        const val DEFAULT_SIDE = "right"
    }
}
