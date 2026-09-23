package com.bydmate.app.util

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.service.TrackingService
import com.bydmate.app.ui.overlay.ListeningOverlay
import com.bydmate.app.ui.widget.WidgetController

/** App UI languages as (code, native name); shared by the Settings picker and the first-run wizard. */
val APP_LANGUAGES: List<Pair<String, String>> = listOf(
    "ru" to "Русский",
    "en" to "English",
    "zh" to "简体中文",
    "pt" to "Português",
    "pl" to "Polski",
    "be" to "Беларуская",
)

/**
 * Persists [lang] and applies it at runtime. MainActivity listens to [LocalePreferences]
 * and recomposes without Activity.recreate().
 */
fun applyAppLanguage(appContext: Context, localePreferences: LocalePreferences, lang: String) {
    localePreferences.setLanguage(lang)
    AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
    // Force overlay teardown so the next attach picks up the new locale.
    // applicationContext keeps a stale Configuration after setApplicationLocales,
    // which leaves the floating widget rendering against the old language.
    WidgetController.relocale(appContext)  // C-5: pass context from VM, not from widgetView
    // The voice orb dialog may be on screen: swap its captions in place.
    ListeningOverlay.relocale(appContext)
    // Channel names live in the system settings: re-register them under the new language.
    val strings = appContext.localizedContext(lang)
    TrackingService.NotificationChannels.create(appContext, strings)
    AutomationEngine.createConfirmChannel(appContext, strings)
}
