package com.bydmate.app.data.local

import android.content.Context
import android.content.SharedPreferences

class LocalePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getLanguage(): String? = prefs.getString(KEY_LANG, null)

    fun setLanguage(lang: String) {
        // commit = true: synchronous write so bootstrap never races with a read.
        prefs.edit().putString(KEY_LANG, lang).commit()
    }

    fun getFontScale(): Float = readFontScale(prefs)

    fun setFontScale(scale: Float) {
        // commit = true: same synchronous write as the language.
        prefs.edit().putFloat(KEY_FONT_SCALE, scale).commit()
    }

    fun isSetupCompletedMirror(): Boolean = prefs.contains(KEY_SETUP_MIRROR)

    fun markSetupCompletedMirror() {
        // commit = true: synchronous write so SettingsRepository.setSetupCompleted()
        // guarantees the mirror is visible before the call returns.
        prefs.edit().putBoolean(KEY_SETUP_MIRROR, true).commit()
    }

    companion object {
        const val FILE = "bydmate_locale"
        const val KEY_LANG = "app_language"
        const val KEY_SETUP_MIRROR = "setup_completed_mirror"
        const val KEY_FONT_SCALE = "font_scale"
        const val DEFAULT_FONT_SCALE = 1.0f
        val FONT_SCALES = listOf(1.0f, 1.15f, 1.3f)

        /** A value of another type (ClassCastException) or outside the allowed steps falls back to 1.0. */
        fun readFontScale(prefs: SharedPreferences): Float {
            val stored = try {
                prefs.getFloat(KEY_FONT_SCALE, DEFAULT_FONT_SCALE)
            } catch (_: ClassCastException) {
                DEFAULT_FONT_SCALE
            }
            return if (stored in FONT_SCALES) stored else DEFAULT_FONT_SCALE
        }
    }
}
