package com.bydmate.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests for [appLocalizedContext] (Q5 / F-10): verifies that strings resolved through the wrapper
 * use the in-app locale (set via [LocalePreferences]) rather than the Robolectric/system locale.
 *
 * The test class is configured with qualifiers="en" to simulate DiLink's system locale (en_US).
 * Setting [LocalePreferences] to "ru" mimics the user selecting Russian inside the app.
 *
 * Anti-vacuity: the "without wrapper" assertion confirms that WITHOUT the wrapper the string
 * DOES resolve to English — proving the wrapper is what makes the Russian resolution happen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
class WithAppLocaleTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun setSystemLocaleToEnglish() {
        // Ensure no app locale is set from a previous test, so the baseline is system-locale English.
        // (Robolectric resets the application between test classes, but @Before guards within the class.)
        LocalePreferences(ctx).setLanguage("en")
    }

    // ---------------------------------------------------------------------------
    // appLocalizedContext() locale resolution
    // ---------------------------------------------------------------------------

    @Test
    fun `appLocalizedContext returns Russian split menu string when app locale is ru`() {
        LocalePreferences(ctx).setLanguage("ru")
        val result = ctx.appLocalizedContext().getString(R.string.split_menu_mirror)
        // values/strings.xml (ru): "Поменять стороны"
        assertEquals("Поменять стороны", result)
    }

    @Test
    fun `appLocalizedContext returns English split menu string when app locale is en`() {
        LocalePreferences(ctx).setLanguage("en")
        val result = ctx.appLocalizedContext().getString(R.string.split_menu_mirror)
        // values-en/strings.xml: "Switch sides"
        assertEquals("Switch sides", result)
    }

    @Test
    fun `appLocalizedContext returns Russian fid-dump error string when app locale is ru`() {
        LocalePreferences(ctx).setLanguage("ru")
        val result = ctx.appLocalizedContext().getString(R.string.settings_fid_dump_error_unavailable)
        // values/strings.xml (ru): "демон недоступен"
        assertEquals("демон недоступен", result)
    }

    @Test
    fun `appLocalizedContext returns Russian fid-dump read-error string when app locale is ru`() {
        LocalePreferences(ctx).setLanguage("ru")
        val detail = "status=-1 at chunk 0"
        val result = ctx.appLocalizedContext().getString(R.string.settings_fid_dump_error_read, detail)
        // values/strings.xml (ru): "ошибка чтения: %1$s"
        assertEquals("ошибка чтения: $detail", result)
    }

    // ---------------------------------------------------------------------------
    // Anti-vacuity: WITHOUT the wrapper, system locale (English) is used
    // ---------------------------------------------------------------------------

    @Test
    fun `anti-vacuity bare context with ru app locale returns English (system locale leak)`() {
        // Set the app locale to Russian — but intentionally do NOT use appLocalizedContext().
        LocalePreferences(ctx).setLanguage("ru")
        // The bare ApplicationContext stays on the system locale (qualifiers="en").
        val withoutWrapper = ctx.getString(R.string.split_menu_mirror)
        // Must be English (system locale) — proving the wrapper IS what fixes the locale.
        assertEquals(
            "Without appLocalizedContext() the ApplicationContext must resolve to system locale (English)",
            "Switch sides",
            withoutWrapper,
        )
        // And with the wrapper it MUST differ.
        val withWrapper = ctx.appLocalizedContext().getString(R.string.split_menu_mirror)
        assertNotEquals(
            "appLocalizedContext() must produce a different string than the bare context when locales differ",
            withoutWrapper,
            withWrapper,
        )
        assertEquals("Поменять стороны", withWrapper)
    }
}
