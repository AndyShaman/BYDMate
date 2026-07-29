package com.bydmate.app.split

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.util.appLocalizedContext
import io.mockk.every
import io.mockk.mockk
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * R6: the picker title must reach the overlay view, and every title must be translated
 * in all four shipped locales (a missing values-xx entry silently falls back to Russian).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SplitPickerTitleI18nTest {

    private val sessionManager = mockk<SplitSessionManager>(relaxed = true)
    private val helperClient = mockk<HelperClient>(relaxed = true)
    private val splitPrefs = mockk<SplitPreferences>(relaxed = true)
    private val events = MutableSharedFlow<SplitEvent>(extraBufferCapacity = 4)

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun localized(lang: String): Context {
        LocalePreferences(ctx).setLanguage(lang)
        return ctx.appLocalizedContext()
    }

    private fun startController(): SplitOverlayController {
        every { splitPrefs.isFeatureEnabled() } returns true
        every { sessionManager.events } returns events
        val controller = SplitOverlayController(ctx, Provider { sessionManager }, helperClient, splitPrefs)
        controller.start(CoroutineScope(Dispatchers.Unconfined + SupervisorJob()))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        return controller
    }

    // ── Propagation logic → view ──────────────────────────────────────────────

    @Test fun `first-pair picker propagates the wide then the narrow title to the view`() {
        every { sessionManager.state } returns MutableStateFlow(SplitSessionState.Idle)
        val controller = startController()

        controller.showFirstPairPicker()

        val view = controller.pillView
        assertNotNull("Precondition: pill view must be attached by showFirstPairPicker", view)
        assertEquals(R.string.split_picker_title_wide, view!!.pickerTitleRes)

        // Step 1 → step 2: the same picker now asks for the narrow (1/3) app.
        controller.handleAppPicked("pkg.wide")

        assertEquals(R.string.split_picker_title_narrow, view.pickerTitleRes)
    }

    @Test fun `pane-closed picker propagates the side title of the closed pane`() {
        val pair = SplitPair(narrowPkg = "pkg.narrow", widePkg = "pkg.wide", narrowSide = SplitSide.RIGHT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = startController()

        events.tryEmit(SplitEvent.PaneClosed(Pane.NARROW))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        // narrowSide = RIGHT → the NARROW pane is the right-hand column.
        assertEquals(R.string.split_picker_title_right, controller.pillView?.pickerTitleRes)
    }

    @Test fun `pane-closed picker with narrow on the left propagates the left title`() {
        val pair = SplitPair(narrowPkg = "pkg.narrow", widePkg = "pkg.wide", narrowSide = SplitSide.LEFT)
        every { sessionManager.state } returns MutableStateFlow(
            SplitSessionState.Active(pair, narrowTaskId = 11, wideTaskId = 10)
        )
        val controller = startController()

        events.tryEmit(SplitEvent.PaneClosed(Pane.NARROW))
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals(R.string.split_picker_title_left, controller.pillView?.pickerTitleRes)
    }

    @Test fun `mirror while the picker is open re-syncs the header to the new side`() {
        // mirror() flips narrowSide on an active session; the state flow re-emits Active.
        val state = MutableStateFlow<SplitSessionState>(
            SplitSessionState.Active(
                SplitPair(narrowPkg = "pkg.narrow", widePkg = "pkg.wide", narrowSide = SplitSide.RIGHT),
                narrowTaskId = 11, wideTaskId = 10,
            )
        )
        every { sessionManager.state } returns state
        val controller = startController()

        // Picker open for the NARROW pane while it sits on the right.
        events.tryEmit(SplitEvent.PaneClosed(Pane.NARROW))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Precondition: narrow pane is on the right",
            R.string.split_picker_title_right, controller.pillView?.pickerTitleRes)

        // mirror(): narrow moves to the left, picker stays open.
        state.value = SplitSessionState.Active(
            SplitPair(narrowPkg = "pkg.narrow", widePkg = "pkg.wide", narrowSide = SplitSide.LEFT),
            narrowTaskId = 11, wideTaskId = 10,
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()

        assertEquals("Header must follow the pane to its new side after mirror",
            R.string.split_picker_title_left, controller.pillView?.pickerTitleRes)
    }

    // ── Locales ───────────────────────────────────────────────────────────────

    @Test fun `left and right titles are translated in every locale`() {
        val expectedLeft = mapOf(
            "ru" to "Выберите приложение для левой панели",
            "en" to "Choose an app for the left pane",
            "pt" to "Escolha um aplicativo para o painel esquerdo",
            "zh" to "选择左侧分屏的应用",
        )
        val expectedRight = mapOf(
            "ru" to "Выберите приложение для правой панели",
            "en" to "Choose an app for the right pane",
            "pt" to "Escolha um aplicativo para o painel direito",
            "zh" to "选择右侧分屏的应用",
        )
        for ((lang, text) in expectedLeft) {
            assertEquals(text, localized(lang).getString(R.string.split_picker_title_left))
        }
        for ((lang, text) in expectedRight) {
            assertEquals(text, localized(lang).getString(R.string.split_picker_title_right))
        }
    }

    @Test fun `first-pair titles name the pane role in every locale`() {
        for (lang in listOf("ru", "en", "pt", "zh")) {
            val lc = localized(lang)
            val wide = lc.getString(R.string.split_picker_title_wide)
            val narrow = lc.getString(R.string.split_picker_title_narrow)
            assertTrue("[$lang] wide title must mention the 2/3 pane: $wide", wide.contains("2/3"))
            assertTrue("[$lang] narrow title must mention the 1/3 pane: $narrow", narrow.contains("1/3"))
            assertTrue("[$lang] wide and narrow titles must differ", wide != narrow)
        }
    }

    @Test fun `every locale has its own translation of all four picker titles`() {
        val titles = listOf(
            R.string.split_picker_title_left,
            R.string.split_picker_title_right,
            R.string.split_picker_title_wide,
            R.string.split_picker_title_narrow,
        )
        for (res in titles) {
            val ru = localized("ru").getString(res)
            assertTrue("Russian title must not be blank", ru.isNotBlank())
            for (lang in listOf("en", "pt", "zh")) {
                val text = localized(lang).getString(res)
                assertTrue("[$lang] title must not be blank", text.isNotBlank())
                assertTrue("[$lang] title falls back to the Russian string: $text", text != ru)
            }
        }
    }
}
