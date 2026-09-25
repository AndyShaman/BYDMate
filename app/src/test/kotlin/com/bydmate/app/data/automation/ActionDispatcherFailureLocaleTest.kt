package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.vehicle.VehicleWriteError
import com.bydmate.app.data.vehicle.WindowPane
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #242 (English UI, BYD e2): a window that did not move was reported in Russian. Every reason a
 * step can fail with comes from the app strings now: an English UI reads no Cyrillic, a Russian
 * UI keeps the wording it always had.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherFailureLocaleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
    private val split = mockk<SplitSessionManager>(relaxed = true)
    private val dispatcher = ActionDispatcher(vehicleApi, mockk<HelperClient>(relaxed = true), app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        cluster,
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        split,
        AppStrings(app),
    ).also { it.clusterPollIntervalMs = 1L }

    init {
        every { split.state } returns MutableStateFlow(SplitSessionState.Idle)
        coEvery { split.startLastPair() } returns null
    }

    private fun lang(tag: String) = LocalePreferences(app).setLanguage(tag)

    @After fun restoreLanguage() = lang("ru")

    private fun String.hasCyrillic() = any { it in 'Ѐ'..'ӿ' }

    private val driverWindowOpen = ActionDef(command = "主驾打开100", displayName = "x", kind = "param")

    private fun stuck(vararg panes: WindowPane): Result<Unit> = Result.failure(
        VehicleWriteError.ReadbackMismatch("window_driver_open", "log line", panes.toList()))

    private suspend fun reason(action: ActionDef): String {
        val r = dispatcher.dispatch(action, null)
        assertFalse(action.toString(), r.success)
        return r.reason!!
    }

    // ── window readback (#242) ──────────────────────────────────────────────────

    @Test fun `a driver window that did not move is reported in English on an English UI`() = runTest {
        lang("en")
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.DRIVER)

        val text = reason(driverWindowOpen)

        assertEquals("driver window did not move, the command did not work", text)
        assertFalse(text, text.hasCyrillic())
    }

    @Test fun `a driver window that did not move keeps the Russian wording on a Russian UI`() = runTest {
        lang("ru")
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.DRIVER)

        assertEquals("окно водителя не сдвинулось с места, команда не сработала", reason(driverWindowOpen))
    }

    @Test fun `several stuck windows are listed in the app language`() = runTest {
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.REAR_LEFT, WindowPane.REAR_RIGHT)

        lang("en")
        assertEquals("did not move: rear left window, rear right window", reason(driverWindowOpen))
        lang("ru")
        assertEquals("не сдвинулись с места: заднее левое окно, заднее правое окно", reason(driverWindowOpen))
    }

    @Test fun `a readback mismatch that names no window keeps its raw message`() = runTest {
        lang("en")
        coEvery { vehicleApi.dispatch(any()) } returns
            Result.failure(VehicleWriteError.ReadbackMismatch("doors_lock", "expected=2 got=1"))

        assertEquals("doors_lock: expected=2 got=1", reason(driverWindowOpen))
    }

    // The per-action run button writes through VehicleApi directly and words the error here.
    @Test fun `the run button gets the same text as a rule step`() {
        lang("en")
        val err = VehicleWriteError.ReadbackMismatch("window_driver_open", "log line", listOf(WindowPane.DRIVER))

        assertEquals("driver window did not move, the command did not work", dispatcher.vehicleFailureReason(err))
    }

    // ── every other step reason ────────────────────────────────────────────────

    private fun action(kind: String, payload: String?) =
        ActionDef(command = kind, displayName = "x", kind = kind, payload = payload)

    /** Steps that fail before touching the car, with the Russian text they always had. */
    private val failingSteps = listOf(
        action("sentry", "7") to "Некорректное состояние охранного режима",
        action("sentry", "1") to "Не удалось переключить охранный режим",
        action("hotspot", "7") to "Некорректное состояние точки доступа Wi-Fi",
        action("hotspot", "1") to "Не удалось переключить точку доступа Wi-Fi",
        action("cluster_projection", "7") to "Некорректное состояние проекции на приборку",
        action("speak", null) to "не задан текст",
        action("agent_query", null) to "не задан запрос",
        action("split_screen", null) to "payload не задан",
        action("split_screen", """{"wide":"b","side":"left"}""") to "narrow не задан",
        action("split_screen", """{"narrow":"a","side":"left"}""") to "wide не задан",
        action("split_screen", """{"narrow":"a","wide":"b","side":"up"}""") to "неверная сторона",
        action("split_screen_toggle", null) to "пара для разделения экрана не сохранена",
        action("delay", null) to "Длительность паузы не задана",
        action("delay", "70000") to "Длительность паузы вне диапазона (0..60000 мс)",
        action("media_volume", null) to "Уровень громкости не задан",
        action("media_volume", "loud") to "Некорректный уровень громкости: loud",
        action("app_launch", null) to "packageName не задан",
        action("app_launch", """{"packageName":"com.example.absent"}""") to
            "Приложение не установлено: com.example.absent",
        action("call", null) to "phone не задан",
        action("navigate", null) to "payload не задан",
        action("url", null) to "url не задан",
        action("yandex_music", """{"mode":"search"}""") to "query не задан",
        action("yandex_music", """{"mode":"radio"}""") to "Неизвестный режим Я.Музыки: radio",
        action("youtube", null) to "query не задан",
        action("youtube", """{"query":"x"}""") to "Приложение YouTube не установлено",
    )

    @Test fun `step reasons keep their Russian wording on a Russian UI`() = runTest {
        lang("ru")
        for ((action, expected) in failingSteps) assertEquals(action.toString(), expected, reason(action))
    }

    @Test fun `step reasons carry no Cyrillic on an English UI`() = runTest {
        lang("en")
        for ((action, _) in failingSteps) {
            val text = reason(action)
            assertTrue(action.toString(), text.isNotBlank() && !text.hasCyrillic())
        }
    }

    @Test fun `cluster projection failures follow the app language`() = runTest {
        every { cluster.lastFailure() } returns null
        every { cluster.projectionMode() } returns ClusterMode.OFF
        lang("ru")
        assertEquals("проекция на приборку не включилась", reason(action("cluster_projection", "1")))
        lang("en")
        assertEquals("projection to the cluster did not turn on", reason(action("cluster_projection", "1")))

        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        lang("ru")
        assertEquals("проекция с приборки не убралась", reason(action("cluster_projection", "0")))
        lang("en")
        assertEquals("projection did not leave the cluster", reason(action("cluster_projection", "0")))
    }

    // The agent tells a restarting daemon from a broken projection by the flag, not by the text.
    @Test fun `a restarting cluster daemon is flagged whatever the language`() = runTest {
        every { cluster.lastFailure() } returns "daemon"
        every { cluster.projectionMode() } returns ClusterMode.OFF

        lang("ru")
        val ru = dispatcher.dispatch(action("cluster_projection", "1"), null)
        assertEquals("служебный процесс перезапускается", ru.reason)
        assertTrue(ru.daemonRestarting)
        lang("en")
        val en = dispatcher.dispatch(action("cluster_projection", "1"), null)
        assertEquals("the service process is restarting", en.reason)
        assertTrue(en.daemonRestarting)
    }

    // ── locale parity ──────────────────────────────────────────────────────────

    @Test fun `every failure reason key exists in all six locales`() {
        val res = File("src/main/res").takeIf { it.isDirectory } ?: File("app/src/main/res")
        val locales = listOf("values", "values-en", "values-be", "values-pl", "values-pt", "values-zh")
        val keyPattern = Regex("""<string name="((?:dispatch_|window_pane_|window_stuck_)[a-z_]+)">([^<]*)</string>""")
        val byLocale = locales.associateWith { dir ->
            keyPattern.findAll(File(res, "$dir/strings.xml").readText()).associate { it.groupValues[1] to it.groupValues[2] }
        }
        val all = byLocale.values.flatMap { it.keys }.toSet()
        assertTrue("the key pattern matched nothing", "window_stuck_one" in all)
        for ((dir, strings) in byLocale) {
            assertEquals("$dir is missing keys", all, strings.keys)
            strings.forEach { (key, text) -> assertFalse("$dir/$key has an em dash", text.contains('—')) }
        }
    }
}
