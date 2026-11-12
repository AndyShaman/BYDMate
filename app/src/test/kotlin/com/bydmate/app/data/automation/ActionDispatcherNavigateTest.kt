package com.bydmate.app.data.automation

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherNavigateTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = testDispatcher(vehicleApi, helper, app, appStrings = com.bydmate.app.util.AppStrings(app))

    init {
        // The fallback reasons below are asserted in Russian; another test may leave its language set.
        LocalePreferences(app).setLanguage("ru")
    }

    private fun actionDef(payload: String) =
        ActionDef(command = "", displayName = "navi", kind = "navigate", payload = payload)

    /** Dispatches [payload] and returns the result with the activity it started, if any. */
    private suspend fun open(payload: String): Pair<DispatchResult, Intent?> =
        dispatcher.dispatch(actionDef(payload), null) to shadowOf(app).nextStartedActivity

    @Test fun `with no navigator chosen the Yandex Navigator gets shortcuts, points, routes and searches`() = runTest {
        open("""{"shortcut":"home"}""").let { (res, intent) ->
            assertTrue("home: success", res.success)
            assertEquals("home: action", "ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT", intent!!.action)
            assertEquals("home: package", "ru.yandex.yandexnavi", intent.`package`)
            assertTrue("home: new task", intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        }
        open("""{"shortcut":"work"}""").let { (res, intent) ->
            assertTrue("work: success", res.success)
            assertEquals("work: action", "ru.yandex.yandexmaps.action.ROUTE_TO_WORK_SHORTCUT", intent!!.action)
        }
        open("""{"shortcut":"dacha"}""").let { (res, intent) ->
            assertFalse("unknown shortcut: fails", res.success)
            assertEquals("unknown shortcut: no activity", null, intent)
        }
        open("""{"show":true,"lat":55.75,"lon":37.62,"label":"Кафе"}""").let { (res, intent) ->
            assertTrue("show point: success", res.success)
            assertEquals("show point: action", Intent.ACTION_VIEW, intent!!.action)
            assertTrue("show point: uri", intent.dataString!!.startsWith("yandexnavi://show_point_on_map?lat=55.75&lon=37.62"))
            assertTrue("show point: desc", intent.dataString!!.contains("desc="))
        }
        open("""{"show":true,"lat":55.75,"lon":37.62}""").let { (_, intent) ->
            assertFalse("show point without label: no desc", intent!!.dataString!!.contains("desc="))
        }
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("route: success", res.success)
            assertEquals("route: uri", "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent!!.dataString)
        }
        open("""{"query":"кафе"}""").let { (_, intent) ->
            assertTrue("search: uri", intent!!.dataString!!.startsWith("yandexnavi://map_search?text="))
        }
        val query = "аэропорт Сочи"
        open("""{"query":"$query"}""").let { (res, intent) ->
            assertTrue("search with spaces: success", res.success)
            assertEquals("search with spaces: action", Intent.ACTION_VIEW, intent!!.action)
            assertEquals("search with spaces: uri", "yandexnavi://map_search?text=${Uri.encode(query)}", intent.dataString)
        }
    }

    /** #190: the setting sends the route to 2GIS, package pinned, longitude first. */
    @Test fun `2GIS chosen gets the route, search and point, and falls back to Yandex while missing`() = runTest {
        selectNavigator(RouteNavigatorUris.DGIS)

        // 2GIS chosen but absent: the action still works, through Yandex, and says why.
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("missing: success", res.success)
            assertTrue("missing: the reason must mention the fallback: ${res.reason}", res.reason!!.contains("2ГИС"))
            assertEquals("missing: uri", "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent!!.dataString)
            assertEquals("missing: package", null, intent.`package`)
        }

        installDgis()
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("route: success", res.success)
            assertEquals("route: reason", null, res.reason)
            assertEquals("route: uri", "dgis://2gis.ru/routeSearch/rsType/car/to/36.0,57.0", intent!!.dataString)
            assertEquals("route: package", "ru.dublgis.dgismobile", intent.`package`)
        }
        open("""{"query":"кафе"}""").let { (_, intent) ->
            assertTrue("search: uri", intent!!.dataString!!.startsWith("dgis://2gis.ru/search/"))
        }
        open("""{"show":true,"lat":55.75,"lon":37.62}""").let { (_, intent) ->
            assertEquals("show: uri", "dgis://2gis.ru/geo/37.62,55.75", intent!!.dataString)
        }
    }

    /** #200: the setting sends the route to Yandex Maps' own dialect, no package pin. */
    @Test fun `Yandex Maps chosen gets its own dialect, and falls back to the Navigator while missing`() = runTest {
        selectNavigator(RouteNavigatorUris.MAPS)

        // Maps chosen but absent: the action still works, through Yandex Navigator, and says why.
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("missing: success", res.success)
            assertTrue("missing: the reason must mention the fallback: ${res.reason}", res.reason!!.contains("Яндекс Карты"))
            assertEquals("missing: uri", "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent!!.dataString)
            assertEquals("missing: package", null, intent.`package`)
        }

        installYandexMaps()
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("route: success", res.success)
            assertEquals("route: reason", null, res.reason)
            assertEquals("route: uri", "yandexmaps://maps.yandex.ru/?rtext=~57.0,36.0&rtt=auto", intent!!.dataString)
            assertEquals("route: package", null, intent.`package`)
        }
        open("""{"query":"кафе"}""").let { (_, intent) ->
            assertTrue("search: uri", intent!!.dataString!!.startsWith("yandexmaps://maps.yandex.ru/?text="))
        }
        open("""{"show":true,"lat":55.75,"lon":37.62}""").let { (_, intent) ->
            assertEquals("show: uri", "yandexmaps://maps.yandex.ru/?pt=55.75,37.62&z=14", intent!!.dataString)
        }
        // Maps chosen also covers «поехали домой/на работу» - navigateMaps() has its own
        // ROUTE_TO_HOME/WORK dialect for that.
        open("""{"shortcut":"home"}""").let { (res, intent) ->
            assertTrue("home: success", res.success)
            assertEquals("home: action", "ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT", intent!!.action)
            assertEquals("home: package", "ru.yandex.yandexmaps", intent.`package`)
        }
        // Review P2: with Maps chosen, «поехали домой» (go=true) must not wait for the Navigator's
        // «Поехали» button - that a11y read only ever looks at the Navigator's window. Without the
        // fix autoGoSupported stays true for any shortcut, and the dispatch fails with the
        // "не удалось проверить маршрут" a11y timeout even though Maps opened successfully.
        val go = dispatcher.dispatch(actionDef("""{"shortcut":"home","go":true}"""), null)
        assertTrue("go on a Maps shortcut must not wait for the Navigator's «Поехали»: ${go.reason}", go.success)
    }

    /** #305: the setting sends the route to Waze, package pinned, latitude first. */
    @Test fun `Waze chosen gets routes, searches and favorites, and falls back to Yandex while missing`() = runTest {
        selectNavigator("waze")

        // Waze chosen but absent: the action still works, through Yandex, and says why.
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("missing: success", res.success)
            assertTrue("missing: the reason must mention the fallback: ${res.reason}", res.reason!!.contains("Waze"))
            assertEquals("missing: uri", "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent!!.dataString)
            assertEquals("missing: package", null, intent.`package`)
        }
        // Waze chosen but absent: Home stays on the Navigator's own shortcut, as before.
        open("""{"shortcut":"home"}""").let { (_, intent) ->
            assertEquals("missing, home: action", "ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT", intent!!.action)
        }

        installWaze()
        open("""{"lat":57.0,"lon":36.0}""").let { (res, intent) ->
            assertTrue("route: success", res.success)
            assertEquals("route: reason", null, res.reason)
            assertEquals("route: uri", "waze://?ll=57.0,36.0&navigate=yes", intent!!.dataString)
            assertEquals("route: package", "com.waze", intent.`package`)
        }
        open("""{"query":"кафе"}""").let { (_, intent) ->
            assertTrue("search: uri", intent!!.dataString!!.startsWith("waze://?q="))
        }
        open("""{"show":true,"lat":55.75,"lon":37.62}""").let { (_, intent) ->
            assertEquals("show: uri", "waze://?ll=55.75,37.62", intent!!.dataString)
        }
        // #334: Home/Work with Waze chosen goes to Waze's own saved favorites, not to Yandex.
        open("""{"shortcut":"home"}""").let { (res, intent) ->
            assertTrue("home: success", res.success)
            assertEquals("home: uri", "waze://?favorite=home&navigate=yes", intent!!.dataString)
            assertEquals("home: package", "com.waze", intent.`package`)
        }
        open("""{"shortcut":"work"}""").let { (_, intent) ->
            assertEquals("work: uri", "waze://?favorite=work&navigate=yes", intent!!.dataString)
        }
        // The Waze favorite link starts the route itself, and «Поехали» is pressed through the
        // Yandex Navigator's a11y window that Waze does not have: nothing waits for it.
        assertFalse("home: no auto go", dispatcher.autoGoSupported(org.json.JSONObject("""{"shortcut":"home"}""")))
        assertFalse("route: no auto go", dispatcher.autoGoSupported(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))
    }

    /** #305: AgentTools waits for the package the intent was pinned to, so this must agree with
     *  sendNavigateIntent: pinned only for an installed 2GIS/Waze (Waze Home/Work included, #334) and never for Maps. */
    @Test fun willOpenPinned_follows_the_routing() {
        selectNavigator("waze")
        assertEquals(null, dispatcher.willOpenPinned(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))

        installWaze()
        assertEquals("com.waze", dispatcher.willOpenPinned(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))
        assertEquals(null, dispatcher.willOpenPinned(org.json.JSONObject("""{"app":"maps","lat":57.0,"lon":36.0}""")))
        assertEquals("com.waze", dispatcher.willOpenPinned(org.json.JSONObject("""{"shortcut":"home"}""")))
    }

    // --- willOpenMaps: what AgentTools asks to pick the right foreground-verification app ---

    @Test fun `willOpenMaps follows an explicit app and the installed Maps setting`() {
        assertTrue("explicit app maps", dispatcher.willOpenMaps(org.json.JSONObject("""{"app":"maps"}""")))

        selectNavigator(RouteNavigatorUris.MAPS)
        assertFalse("setting maps, not installed",
            dispatcher.willOpenMaps(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))

        installYandexMaps()
        assertTrue("setting maps, installed", dispatcher.willOpenMaps(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))
        assertTrue("setting maps, shortcut", dispatcher.willOpenMaps(org.json.JSONObject("""{"shortcut":"home"}""")))
    }

    private fun selectNavigator(value: String) {
        app.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .edit().putString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, value).commit()
    }

    /** The dispatcher probes for 2GIS with getLaunchIntentForPackage, so the shadow needs a
     *  launcher activity for that package, not just an installed-package entry. */
    private fun installDgis() {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(
            android.content.ComponentName("ru.dublgis.dgismobile", "ru.dublgis.dgismobile.Main"))
        pm.addIntentFilterForActivity(
            android.content.ComponentName("ru.dublgis.dgismobile", "ru.dublgis.dgismobile.Main"),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
        )
    }

    /** Same as [installDgis], for Waze (#305). */
    private fun installWaze() {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(android.content.ComponentName("com.waze", "com.waze.FreeMapAppActivity"))
        pm.addIntentFilterForActivity(
            android.content.ComponentName("com.waze", "com.waze.FreeMapAppActivity"),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
        )
    }

    /** Same as [installDgis], for one of the store variants of Yandex Maps (#200). */
    private fun installYandexMaps() {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(
            android.content.ComponentName("ru.yandex.yandexmaps", "ru.yandex.yandexmaps.Main"))
        pm.addIntentFilterForActivity(
            android.content.ComponentName("ru.yandex.yandexmaps", "ru.yandex.yandexmaps.Main"),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
        )
    }
}
