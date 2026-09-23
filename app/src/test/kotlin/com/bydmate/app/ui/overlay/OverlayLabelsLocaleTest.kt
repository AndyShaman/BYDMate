package com.bydmate.app.ui.overlay

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.automation.ConfirmOverlayManager
import com.bydmate.app.data.local.LocalePreferences
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Overlays get the application context, which stays on the system locale (en-US here, zh or en
 * on the car). Their labels must follow the app language from [LocalePreferences] instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OverlayLabelsLocaleTest {
    private val app: Context = ApplicationProvider.getApplicationContext()
    private val locale = LocalePreferences(app)

    @Test fun `confirm overlay buttons follow the app language, not the system one`() {
        locale.setLanguage("ru")
        assertEquals("Отмена" to "Выполнить", ConfirmOverlayManager.buttonLabels(app))
        locale.setLanguage("en")
        assertEquals("Cancel" to "Run", ConfirmOverlayManager.buttonLabels(app))
    }

    @Test fun `voice orb captions follow the app language and a switch`() {
        locale.setLanguage("ru")
        ListeningOverlay.relocale(app)
        assertEquals("Ты:" to "Агент:", ListeningOverlay.dialogLabels)
        locale.setLanguage("en")
        ListeningOverlay.relocale(app)
        assertEquals("You:" to "Agent:", ListeningOverlay.dialogLabels)
    }
}
