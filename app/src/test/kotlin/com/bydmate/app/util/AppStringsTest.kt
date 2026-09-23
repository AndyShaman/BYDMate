package com.bydmate.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The application context stays on the system locale (en-US under Robolectric, English on the
 * car); [AppStrings] must answer in the language stored in [LocalePreferences] instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AppStringsTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val locale = LocalePreferences(app)
    private val strings = AppStrings(app)

    @Test fun `answers in the app language, not the system one`() {
        locale.setLanguage("ru")
        assertEquals("Сохранено", strings.get(R.string.settings_saved))
        assertEquals("Saved", app.getString(R.string.settings_saved))
    }

    @Test fun `a language switch applies on the next call`() {
        locale.setLanguage("ru")
        assertEquals("Сохранено", strings.get(R.string.settings_saved))
        locale.setLanguage("be")
        assertEquals("Захавана", strings.get(R.string.settings_saved))
        assertEquals("Захавана", strings.context.getString(R.string.settings_saved))
        locale.setLanguage("en")
        assertEquals("Saved", strings.get(R.string.settings_saved))
    }

    @Test fun `format args are applied in the app language`() {
        locale.setLanguage("be")
        assertEquals(
            "Падключана: @bydmatebot → Chat",
            strings.get(R.string.settings_tg_backup_connected, "bydmatebot", "Chat"),
        )
    }

    @Test fun `a lookup without args keeps a literal percent sign`() {
        locale.setLanguage("ru")
        assertTrue(strings.get(R.string.tech_hint_remain_kwh).contains("100 %"))
    }
}
