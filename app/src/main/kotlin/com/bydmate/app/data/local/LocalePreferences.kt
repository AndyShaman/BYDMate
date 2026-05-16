package com.bydmate.app.data.local

import android.content.Context

class LocalePreferences(context: Context) {

    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getLanguage(): String? = prefs.getString(KEY_LANG, null)

    fun setLanguage(lang: String) {
        // commit = true: synchronous write so bootstrap never races with a read.
        prefs.edit().putString(KEY_LANG, lang).commit()
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
    }
}
