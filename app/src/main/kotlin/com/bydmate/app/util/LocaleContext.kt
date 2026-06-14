package com.bydmate.app.util

import android.content.Context
import android.content.res.Configuration
import com.bydmate.app.data.local.LocalePreferences
import java.util.Locale

/**
 * Returns a [Context] configured with the app's user-selected language
 * ([LocalePreferences]), falling back to Russian — matching
 * `MainActivity.attachBaseContext` / `MainActivity.DEFAULT_LANG`.
 *
 * Use for any locale-sensitive formatting or `getString` performed OFF the
 * Activity context (e.g. from a ViewModel holding `@ApplicationContext`, which
 * is NOT localized and stays on the head unit's system locale — often zh/en).
 *
 * Build on demand; do NOT cache the result — the application context keeps the
 * old locale after a language switch, so a cached localized context would go stale.
 */
fun Context.appLocalizedContext(): Context {
    val lang = LocalePreferences(this).getLanguage() ?: "ru"
    val config = Configuration(resources.configuration).apply {
        setLocale(Locale.forLanguageTag(lang))
    }
    return createConfigurationContext(config)
}
