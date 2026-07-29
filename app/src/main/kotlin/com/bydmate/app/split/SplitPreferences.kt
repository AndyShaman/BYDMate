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
 * Persists the last-used split pair so the widget / voice agent can restore it
 * without the user having to pick apps again.
 *
 * Backed by SharedPreferences file "bydmate_split".
 */
interface SplitPreferences {
    /** Returns the last saved pair, or null if none has been saved yet. */
    fun getLastPair(): SplitPair?
    fun saveLastPair(pair: SplitPair)
    fun clearLastPair()

    /** Master switch: true when the split-screen feature is enabled (default false). */
    fun isFeatureEnabled(): Boolean

    /** Persists the master switch state. */
    fun setFeatureEnabled(enabled: Boolean)
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
}

/** SharedPreferences-backed production implementation of [SplitPreferences]. */
class SplitPreferencesImpl(context: Context) : SplitPreferences {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun getLastPair(): SplitPair? {
        val narrowPkg = prefs.getString(KEY_NARROW_PKG, null) ?: return null
        val widePkg = prefs.getString(KEY_WIDE_PKG, null) ?: return null
        val side = when (prefs.getString(KEY_NARROW_SIDE, DEFAULT_SIDE)) {
            "left" -> SplitSide.LEFT
            else -> SplitSide.RIGHT
        }
        return SplitPair(narrowPkg, widePkg, side)
    }

    override fun saveLastPair(pair: SplitPair) {
        prefs.edit()
            .putString(KEY_NARROW_PKG, pair.narrowPkg)
            .putString(KEY_WIDE_PKG, pair.widePkg)
            .putString(KEY_NARROW_SIDE, pair.narrowSide.name.lowercase())
            .apply()
    }

    override fun clearLastPair() {
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

    companion object {
        const val PREFS_NAME = "bydmate_split"
        const val KEY_NARROW_PKG = "narrow_pkg"
        const val KEY_WIDE_PKG = "wide_pkg"
        const val KEY_NARROW_SIDE = "narrow_side"
        const val KEY_FEATURE_ENABLED = "feature_enabled"
        const val DEFAULT_SIDE = "right"
    }
}
