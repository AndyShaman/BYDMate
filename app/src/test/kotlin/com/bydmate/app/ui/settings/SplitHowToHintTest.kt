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
 * Text behind the "?" help badge on the «Разделение экрана» section header. Launch settings live in
 * one block, so the hint is a single sentence that points to it instead of describing the old
 * three ways to launch.
 *
 * Limitation: the project has no Compose UI test dependency, so «badge is rendered on the section
 * header / tap reveals the text» is on-car acceptance, not a unit test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitHowToHintTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun localized(lang: String): Context {
        LocalePreferences(ctx).setLanguage(lang)
        return ctx.appLocalizedContext()
    }

    private val locales = listOf("ru", "en", "be", "pl", "pt", "zh")

    @Test fun `hint is one non-blank sentence in every locale`() {
        for (lang in locales) {
            val body = localized(lang).getString(R.string.settings_split_howto_body)
            assertTrue("[$lang] body must be a single line: $body", body.isNotBlank() && !body.contains("\n"))
        }
    }

    @Test fun `russian hint sends the user to the how to launch block`() {
        val body = localized("ru").getString(R.string.settings_split_howto_body)
        assertTrue(body, body.contains("«Как запускать»"))
    }

    @Test fun `body is really translated, not falling back to russian`() {
        val ru = localized("ru").getString(R.string.settings_split_howto_body)
        for (lang in locales - "ru") {
            assertTrue("[$lang] settings_split_howto_body falls back to the Russian string",
                localized(lang).getString(R.string.settings_split_howto_body) != ru)
        }
    }
}
