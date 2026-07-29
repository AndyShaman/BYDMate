package com.bydmate.app.ui.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.appLocalizedContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * R6: text behind the "?" help badge on the «Разделение экрана» section header
 * (SectionHeader(onHelp = …) → SettingHint, the same idiom as the projection-mode row).
 *
 * The hint is prose, not a list: an opening sentence plus one paragraph per way to launch a
 * split (widget left-tap zone, automation action, voice agent). Each locale must therefore
 * carry four paragraphs, a real translation, and the verbatim settings labels of its own
 * locale — the widget-menu path the user has to walk, the automation action names, and the
 * voice-agent section name.
 *
 * Limitation: the project has no Compose UI test dependency (see report), so «badge is
 * rendered on the section header / tap reveals the text» is on-car acceptance, not a unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitHowToHintTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun localized(lang: String): Context {
        LocalePreferences(ctx).setLanguage(lang)
        return ctx.appLocalizedContext()
    }

    private val locales = listOf("ru", "en", "pt", "zh")

    @Test fun `hint is prose with an intro and one paragraph per launch path`() {
        for (lang in locales) {
            val body = localized(lang).getString(R.string.settings_split_howto_body)
            val paragraphs = body.split("\n")
            assertTrue("[$lang] body must be an intro + 3 paragraphs (widget, automation, voice): $body",
                paragraphs.size == 4)
            assertTrue("[$lang] no bullet lists — the hint must read as sentences: $body",
                paragraphs.none { it.trimStart().startsWith("•") || it.trimStart().startsWith("-") })
            assertTrue("[$lang] every paragraph must be a finished sentence: $body",
                paragraphs.all { it.isNotBlank() && (it.trimEnd().endsWith(".") || it.trimEnd().endsWith("。")) })
        }
    }

    @Test fun `each launch path gets its own paragraph, in order`() {
        // Paragraph 1 = intro, 2 = widget zone, 3 = automations, 4 = voice. Order matters:
        // the widget path is the one users are told to try first.
        for (lang in locales) {
            val lc = localized(lang)
            val p = lc.getString(R.string.settings_split_howto_body).split("\n")
            assertTrue("[$lang] widget paragraph is not the second one: ${p[1]}",
                p[1].contains(lc.getString(R.string.settings_widget_left_tap_mode_split)))
            assertTrue("[$lang] automation paragraph is not the third one: ${p[2]}",
                p[2].contains(lc.getString(R.string.automation_action_split_screen_toggle)))
            assertTrue("[$lang] voice paragraph is not the fourth one: ${p[3]}",
                p[3].contains(lc.getString(R.string.settings_section_voice_agent), ignoreCase = true))
        }
    }

    @Test fun `russian body reads as an instruction, not a telegram`() {
        val body = localized("ru").getString(R.string.settings_split_howto_body)
        assertTrue("intro sentence missing: $body", body.startsWith("Разделение экрана можно включить"))
        assertTrue("widget path missing: $body", body.contains("левой трети", ignoreCase = true))
        assertTrue("automation path missing: $body", body.contains("автоматизац", ignoreCase = true))
        assertTrue("voice path missing: $body", body.contains("голос", ignoreCase = true))
    }

    @Test fun `body quotes the real automation action names of its own locale`() {
        // The hint tells the user which automation actions to look for; if R2 renames an
        // action label, the hint must be updated with it — this pins that link per locale.
        val actionLabels = listOf(
            R.string.automation_action_split_screen,
            R.string.automation_action_split_screen_close,
            R.string.automation_action_split_screen_toggle,
        )
        for (lang in locales) {
            val lc = localized(lang)
            val body = lc.getString(R.string.settings_split_howto_body)
            for (res in actionLabels) {
                val label = lc.getString(res)
                assertTrue("[$lang] hint body must name the action «$label»: $body", body.contains(label))
            }
        }
    }

    @Test fun `body quotes the real widget menu path of its own locale`() {
        // The first line must send the user to settings entries that actually exist, spelled the
        // way the app spells them: «Тап по виджету» → «Зонирование тапов» → «Действие левого тапа»
        // → «Запускать разделение экрана». Renaming any of them must break this test.
        val menuLabels = listOf(
            R.string.settings_widget_tap_section_header,
            R.string.settings_widget_left_tap_zoning_label,
            R.string.settings_widget_left_tap_mode_label,
            R.string.settings_widget_left_tap_mode_split,
        )
        for (lang in locales) {
            val lc = localized(lang)
            val body = lc.getString(R.string.settings_split_howto_body)
            for (res in menuLabels) {
                val label = lc.getString(res)
                assertTrue("[$lang] hint body must name the settings entry «$label»: $body", body.contains(label))
            }
        }
    }

    @Test fun `body is really translated, not falling back to russian`() {
        val ru = localized("ru").getString(R.string.settings_split_howto_body)
        for (lang in listOf("en", "pt", "zh")) {
            assertTrue("[$lang] settings_split_howto_body falls back to the Russian string",
                localized(lang).getString(R.string.settings_split_howto_body) != ru)
        }
    }
}
