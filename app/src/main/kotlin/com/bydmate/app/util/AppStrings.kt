package com.bydmate.app.util

import android.content.Context
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Strings in the app's chosen language for code that holds only the application context
 * (ViewModels, services, the automation engine). AppCompat re-localizes Activities only; the
 * application context stays on the head unit's system locale, so a plain `getString` there
 * comes out in English or Chinese. Same language rule as [appLocalizedContext].
 */
@Singleton
class AppStrings @Inject constructor(@ApplicationContext private val appContext: Context) {

    // Keyed by the language tag: a switch in LocalePreferences rebuilds it on the next call.
    @Volatile private var cached: Pair<String, Context>? = null

    /** The application context configured to the app language. */
    val context: Context
        get() {
            val lang = appContext.appLanguageTag()
            cached?.let { (tag, ctx) -> if (tag == lang) return ctx }
            return appContext.localizedContext(lang).also { cached = lang to it }
        }

    fun get(@StringRes id: Int, vararg args: Any): String {
        val ctx = context
        // No args: plain lookup, a format pass would choke on a literal "%" in the text.
        return if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)
    }
}
