package com.bydmate.app.ui.automation

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * Behaviour-lock for the regression where ViewModel-facing strings resolved via the
 * bare @ApplicationContext (head-unit SYSTEM locale, often zh/en) instead of the
 * user-selected language (LocalePreferences). The original `localized()` always
 * keyed off LocalePreferences; the resource migration must preserve that by routing
 * through `appLocalizedContext()`. This locks the real call-site (`saveRule`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@OptIn(ExperimentalCoroutinesApi::class)
class AutomationMessageLocaleTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before fun setUp() { Dispatchers.setMain(testDispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    private fun ctxIn(lang: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val cfg = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(lang)) }
        return base.createConfigurationContext(cfg)
    }

    @Test
    fun `saveRule editorError honors selected language not appContext system locale`() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        // User picked Russian in-app...
        LocalePreferences(base).setLanguage("ru")
        // ...but the injected @ApplicationContext is stuck on the head unit's system
        // locale (simulate English — on a real BYD head unit it is often zh/en).
        val appCtxEn = ctxIn("en")

        val vm = AutomationViewModel(
            ruleDao = mockk(relaxed = true),
            ruleLogDao = mockk(relaxed = true),
            placeRepository = mockk(relaxed = true),
            vehicleApi = mockk(relaxed = true),
            context = appCtxEn,
        )

        // Empty editing rule -> early-return validation message via the @ApplicationContext path.
        vm.saveRule()

        val actual = vm.uiState.value.editorError
        val ruExpected = ctxIn("ru").getString(R.string.auto_msg_name_cond_action_required)
        val enValue = appCtxEn.getString(R.string.auto_msg_name_cond_action_required)

        assertNotEquals("precondition: ru and en strings must differ", ruExpected, enValue)
        assertEquals(
            "editorError must resolve in the selected language (ru), not the @ApplicationContext system locale",
            ruExpected,
            actual,
        )
    }
}
