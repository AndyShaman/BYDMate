package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
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
 * step can fail with comes from the app strings now: each cause gets its own string in the app
 * language, and an English UI reads no Cyrillic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherFailureLocaleTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
    private val split = mockk<SplitSessionManager>(relaxed = true)
    private val dispatcher = testDispatcher(vehicleApi = vehicleApi, context = app, clusterVoiceControl = cluster, splitSessionManager = split, appStrings = AppStrings(app)).also {
        it.clusterPollIntervalMs = 1L
        it.readSpeedNow = { 0 } // a standing car unless a test says otherwise
    }

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

    private val appStrings = AppStrings(app)

    /** [id] in the current app language. */
    private fun t(id: Int, vararg args: Any) = appStrings.get(id, *args)

    /**
     * [action] fails with the text [expected] builds in each app language, so the reason the
     * step names is checked, not its wording; the English one reads no Cyrillic.
     */
    private suspend fun assertReason(label: String, action: ActionDef, expected: () -> String) {
        lang("en")
        val en = reason(action)
        assertEquals("case: $label (en)", expected(), en)
        assertFalse("case: $label (en): $en", en.hasCyrillic())
        lang("ru")
        assertEquals("case: $label (ru)", expected(), reason(action))
    }

    // ── window readback (#242) ──────────────────────────────────────────────────

    // Audit 3.19 item 7: the journal shows a phrase in the app language, never the raw error.
    @Test fun `a window that did not move is named in the app language`() = runTest {
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.DRIVER)
        assertReason("driver window", driverWindowOpen) {
            t(R.string.window_stuck_one, t(R.string.window_pane_driver))
        }
        coEvery { vehicleApi.dispatch(any()) } returns stuck(WindowPane.REAR_LEFT, WindowPane.REAR_RIGHT)
        assertReason("several windows", driverWindowOpen) {
            t(R.string.window_stuck_many, t(R.string.window_pane_rear_left) + ", " + t(R.string.window_pane_rear_right))
        }
        coEvery { vehicleApi.dispatch(any()) } returns
            Result.failure(VehicleWriteError.ReadbackMismatch("doors_lock", "expected=2 got=1"))
        assertReason("readback mismatch naming no window", driverWindowOpen) { t(R.string.auto_err_not_done) }
    }

    @Test fun `write errors are worded per cause in the app language`() = runTest {
        val cases = listOf(
            VehicleWriteError.Sentinel("doors_lock") to R.string.auto_err_not_confirmed,
            VehicleWriteError.AllowlistMiss("doors_lock") to R.string.auto_err_not_supported,
            VehicleWriteError.OutOfRange("doors_lock", "value=7") to R.string.auto_err_out_of_range,
            VehicleWriteError.HelperUnreachable("doors_lock", "binder dead") to R.string.auto_err_unreachable,
            VehicleWriteError.Unsupported("doors_lock") to R.string.auto_err_unverified,
            IllegalStateException("boom") to R.string.auto_err_failed,
        )
        for ((err, id) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            assertReason(err.toString(), driverWindowOpen) { t(id) }
        }
    }

    // The per-action run button writes through VehicleApi directly and words the error here.
    @Test fun `the run button gets the same text as a rule step`() = runTest {
        lang("en")
        val err = VehicleWriteError.ReadbackMismatch("window_driver_open", "log line", listOf(WindowPane.DRIVER))
        coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)

        assertEquals(reason(driverWindowOpen), dispatcher.vehicleFailureReason(err))
    }

    // ── drive mode (#253) ──────────────────────────────────────────────────────

    @Test fun `drive mode failures are worded per cause in the app language`() = runTest {
        val eco = ActionDef(command = "ECO模式", displayName = "x", kind = "param")
        val cases = listOf<Pair<VehicleWriteError, () -> String>>(
            VehicleWriteError.NotEquipped("drive_mode_eco") to { t(R.string.drive_mode_not_supported) },
            VehicleWriteError.StateBlocked("drive_mode_eco", "emergency flotation mode") to
                { t(R.string.drive_mode_flotation) },
            VehicleWriteError.ReadbackMismatch("drive_mode_eco", "mode did not change") to
                { t(R.string.drive_mode_not_changed) },
            VehicleWriteError.HelperUnreachable("drive_mode_eco", "unreachable") to { t(R.string.drive_mode_failed) },
            VehicleWriteError.SpeedBlocked("drive_mode_eco", "too fast", 40) to { t(R.string.gate_drive_mode_speed, 40) },
            VehicleWriteError.SpeedBlocked("drive_mode_eco", "speed unknown", null) to
                { t(R.string.gate_drive_mode_speed_unknown) },
        )
        for ((err, expected) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            assertReason(err.toString(), eco, expected)
        }
    }

    // ── HUD (#292) ─────────────────────────────────────────────────────────────

    @Test fun `a car without a HUD says so in the app language`() = runTest {
        coEvery { vehicleApi.dispatch(any()) } returns Result.failure(VehicleWriteError.NotEquipped("hud_off"))
        val off = ActionDef(command = "关闭抬头显示", displayName = "x", kind = "param")
        assertReason("no HUD", off) { t(R.string.hud_not_equipped) }
    }

    // ── Spoiler (#383) ─────────────────────────────────────────────────────────

    @Test fun `a refused spoiler names the reason in the app language`() = runTest {
        val up = ActionDef(command = "打开尾翼", displayName = "x", kind = "param")
        val cases = listOf(
            // Without its own line a car with no spoiler would be told it has no steering heat.
            VehicleWriteError.NotEquipped("spoiler_up") to R.string.spoiler_not_equipped,
            VehicleWriteError.StateBlocked("spoiler_up", "anti-pinch") to R.string.spoiler_anti_pinch,
            VehicleWriteError.StateBlocked("spoiler_up", "blocked warning=1") to R.string.spoiler_blocked,
            VehicleWriteError.StateBlocked("spoiler_down", "blocked standing position=100 speed=0") to
                R.string.spoiler_standing,
        )
        for ((err, id) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            assertReason(err.toString(), up) { t(id) }
        }
    }

    // ── Battery warm-up (#311) ─────────────────────────────────────────────────

    @Test fun `a refused battery warm-up names the reason in the app language`() = runTest {
        val on = ActionDef(command = "打开电池预热", displayName = "x", kind = "param")
        val cases = listOf(
            VehicleWriteError.NotEquipped("battery_preheat_on") to R.string.battery_preheat_not_equipped,
            VehicleWriteError.NotNeeded("battery_preheat_on", "permit=1") to R.string.battery_preheat_not_needed,
            VehicleWriteError.StateBlocked("battery_preheat_on", "charge gun connected") to
                R.string.battery_preheat_gun_connected,
            VehicleWriteError.ReadbackMismatch("battery_preheat_on", "no effect read=0") to R.string.auto_err_not_done,
        )
        for ((err, id) in cases) {
            coEvery { vehicleApi.dispatch(any()) } returns Result.failure(err)
            assertReason(err.toString(), on) { t(id) }
        }
    }

    @Test fun `a terrain mode at unknown speed is refused before the car is asked`() = runTest {
        lang("ru")
        dispatcher.readSpeedNow = { null }
        val snow = ActionDef(command = "雪地模式", displayName = "x", kind = "param")
        assertEquals(t(R.string.gate_drive_mode_speed_unknown), reason(snow))
        io.mockk.coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── every other step reason ────────────────────────────────────────────────

    private fun action(kind: String, payload: String?) =
        ActionDef(command = kind, displayName = "x", kind = kind, payload = payload)

    /** Steps that fail before touching the car. */
    private val failingSteps = listOf(
        action("sentry", "7"),
        action("sentry", "1"),
        action("hotspot", "7"),
        action("hotspot", "1"),
        action("screen", "7"),
        action("cluster_projection", "7"),
        action("speak", null),
        action("agent_query", null),
        action("split_screen", null),
        action("split_screen", """{"wide":"b","side":"left"}"""),
        action("split_screen", """{"narrow":"a","side":"left"}"""),
        action("split_screen", """{"narrow":"a","wide":"b","side":"up"}"""),
        action("split_screen_toggle", null),
        action("delay", null),
        action("delay", "86400001"),
        action("media_volume", null),
        action("media_volume", "loud"),
        action("app_launch", null),
        action("app_launch", """{"packageName":"com.example.absent"}"""),
        action("call", null),
        action("navigate", null),
        action("url", null),
        action("yandex_music", """{"mode":"search"}"""),
        action("yandex_music", """{"mode":"radio"}"""),
        action("youtube", null),
        action("youtube", """{"query":"x"}"""),
    )

    @Test fun `step reasons follow the app language, no Cyrillic on an English UI`() = runTest {
        for (action in failingSteps) {
            lang("en")
            val en = reason(action)
            assertTrue("case: $action (en): $en", en.isNotBlank() && !en.hasCyrillic())
            lang("ru")
            val ru = reason(action)
            assertTrue("case: $action (ru): $ru", ru.hasCyrillic())
        }
    }

    @Test fun `cluster projection failures follow the app language`() = runTest {
        every { cluster.lastFailure() } returns null
        every { cluster.projectionMode() } returns ClusterMode.OFF
        assertReason("projection did not turn on", action("cluster_projection", "1")) { t(R.string.dispatch_cluster_not_on) }

        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        assertReason("projection did not leave", action("cluster_projection", "0")) { t(R.string.dispatch_cluster_not_off) }
    }

    // The agent tells a restarting daemon from a broken projection by the flag, not by the text.
    @Test fun `a restarting cluster daemon is flagged whatever the language`() = runTest {
        every { cluster.lastFailure() } returns "daemon"
        every { cluster.projectionMode() } returns ClusterMode.OFF

        for (tag in listOf("ru", "en")) {
            lang(tag)
            val result = dispatcher.dispatch(action("cluster_projection", "1"), null)
            assertEquals(tag, t(R.string.dispatch_cluster_daemon_restarting), result.reason)
            assertTrue(tag, result.daemonRestarting)
        }
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
