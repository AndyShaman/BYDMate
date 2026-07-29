package com.bydmate.app.ui.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Factories + labels for the payload-less split kinds (W6-F5). Pins the kind
 * strings the dispatcher routes on, the absence of a payload (the validator
 * relies on it), and a real translation in all four locales — a missing
 * values-xx entry would silently fall back to the Russian string.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitToggleActionFactoryTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun localized(lang: String): Context {
        LocalePreferences(ctx).setLanguage(lang)
        return ctx.appLocalizedContext()
    }

    @Test fun `close factory builds a payload-less action of the close kind`() {
        val action = newSplitScreenCloseAction(localized("ru"))
        assertEquals("split_screen_close", action.kind)
        assertNull(action.payload)
        assertEquals("", action.command)
        assertEquals("Закрыть разделение экрана", action.displayName)
    }

    @Test fun `toggle factory builds a payload-less action of the toggle kind`() {
        val action = newSplitScreenToggleAction(localized("ru"))
        assertEquals("split_screen_toggle", action.kind)
        assertNull(action.payload)
        assertEquals("", action.command)
        assertEquals("Переключить разделение экрана", action.displayName)
    }

    @Test fun `close label is translated in every locale`() {
        assertEquals("Закрыть разделение экрана", localized("ru").getString(R.string.automation_action_split_screen_close))
        assertEquals("Close Split Screen", localized("en").getString(R.string.automation_action_split_screen_close))
        assertEquals("Fechar tela dividida", localized("pt").getString(R.string.automation_action_split_screen_close))
        assertEquals("关闭分屏", localized("zh").getString(R.string.automation_action_split_screen_close))
    }

    @Test fun `toggle label is translated in every locale`() {
        assertEquals("Переключить разделение экрана", localized("ru").getString(R.string.automation_action_split_screen_toggle))
        assertEquals("Toggle Split Screen", localized("en").getString(R.string.automation_action_split_screen_toggle))
        assertEquals("Alternar tela dividida", localized("pt").getString(R.string.automation_action_split_screen_toggle))
        assertEquals("切换分屏", localized("zh").getString(R.string.automation_action_split_screen_toggle))
    }

    @Test fun `action display names are translated in every locale`() {
        for (lang in listOf("ru", "en", "pt", "zh")) {
            val lc = localized(lang)
            assertEquals(lc.getString(R.string.auto_act_split_screen_close), newSplitScreenCloseAction(lc).displayName)
            assertEquals(lc.getString(R.string.auto_act_split_screen_toggle), newSplitScreenToggleAction(lc).displayName)
        }
    }
}
