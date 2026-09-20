package com.bydmate.app.data.automation

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
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
    private val dispatcher = ActionDispatcher(vehicleApi, helper, app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true))

    private fun actionDef(payload: String) =
        ActionDef(command = "", displayName = "navi", kind = "navigate", payload = payload)

    @Test fun shortcut_home_fires_route_to_home_action_on_navi_package() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"home"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT", intent.action)
        assertEquals("ru.yandex.yandexnavi", intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun shortcut_work_fires_route_to_work_action() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"work"}"""), null)
        assertTrue(res.success)
        assertEquals("ru.yandex.yandexmaps.action.ROUTE_TO_WORK_SHORTCUT",
            shadowOf(app).nextStartedActivity.action)
    }

    @Test fun shortcut_unknown_fails_without_starting_activity() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"dacha"}"""), null)
        assertFalse(res.success)
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test fun show_point_builds_show_point_uri_with_desc() = runTest {
        val res = dispatcher.dispatch(
            actionDef("""{"show":true,"lat":55.75,"lon":37.62,"label":"Кафе"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.dataString!!.startsWith("yandexnavi://show_point_on_map?lat=55.75&lon=37.62"))
        assertTrue(intent.dataString!!.contains("desc="))
    }

    @Test fun show_point_without_label_omits_desc() = runTest {
        dispatcher.dispatch(actionDef("""{"show":true,"lat":55.75,"lon":37.62}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertFalse(intent.dataString!!.contains("desc="))
    }

    @Test fun latlon_still_builds_route_uri() = runTest {
        dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent.dataString)
    }

    @Test fun query_still_opens_map_search() = runTest {
        dispatcher.dispatch(actionDef("""{"query":"кафе"}"""), null)
        assertTrue(shadowOf(app).nextStartedActivity.dataString!!
            .startsWith("yandexnavi://map_search?text="))
    }

    /** #190: the setting sends the route to 2GIS, package pinned, longitude first. */
    @Test fun dgis_selected_and_installed_opens_2gis() = runTest {
        selectNavigator(RouteNavigatorUris.DGIS)
        installDgis()

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        assertEquals(null, res.reason)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("dgis://2gis.ru/routeSearch/rsType/car/to/36.0,57.0", intent.dataString)
        assertEquals("ru.dublgis.dgismobile", intent.`package`)
    }

    /** 2GIS chosen but absent: the action still works, through Yandex, and says why. */
    @Test fun dgis_selected_but_missing_falls_back_to_yandex() = runTest {
        selectNavigator(RouteNavigatorUris.DGIS)

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        assertTrue("the reason must mention the fallback: ${res.reason}",
            res.reason!!.contains("2ГИС"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent.dataString)
        assertEquals(null, intent.`package`)
    }

    @Test fun dgis_search_and_show_use_the_2gis_scheme() = runTest {
        selectNavigator(RouteNavigatorUris.DGIS)
        installDgis()

        dispatcher.dispatch(actionDef("""{"query":"кафе"}"""), null)
        assertTrue(shadowOf(app).nextStartedActivity.dataString!!
            .startsWith("dgis://2gis.ru/search/"))

        dispatcher.dispatch(actionDef("""{"show":true,"lat":55.75,"lon":37.62}"""), null)
        assertEquals("dgis://2gis.ru/geo/37.62,55.75",
            shadowOf(app).nextStartedActivity.dataString)
    }

    @Test fun waze_selected_and_installed_opens_waze() = runTest {
        selectNavigator(RouteNavigatorUris.WAZE)
        installWaze()

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("https://waze.com/ul?ll=57.0,36.0&navigate=yes", intent.dataString)
        assertEquals("com.waze", intent.`package`)
    }

    @Test fun waze_selected_but_missing_falls_back_to_yandex() = runTest {
        selectNavigator(RouteNavigatorUris.WAZE)

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        assertTrue(res.reason!!.contains("Waze"))
        assertEquals(
            "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0",
            shadowOf(app).nextStartedActivity.dataString,
        )
    }

    @Test fun waze_home_shortcut_uses_favorite_deep_link() = runTest {
        selectNavigator(RouteNavigatorUris.WAZE)
        installWaze()

        val res = dispatcher.dispatch(actionDef("""{"shortcut":"home","go":true}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("https://waze.com/ul?favorite=home&navigate=yes", intent.dataString)
        assertEquals("com.waze", intent.`package`)
    }

    @Test fun google_maps_selected_and_installed_opens_google_maps() = runTest {
        selectNavigator(RouteNavigatorUris.GOOGLE_MAPS)
        installGoogleMaps()

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=57.0%2C36.0&travelmode=driving&dir_action=navigate",
            intent.dataString,
        )
        assertEquals("com.google.android.apps.maps", intent.`package`)
    }

    @Test fun google_maps_selected_but_missing_falls_back_to_yandex() = runTest {
        selectNavigator(RouteNavigatorUris.GOOGLE_MAPS)

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        assertTrue(res.reason!!.contains("Google Maps"))
        assertEquals(
            "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0",
            shadowOf(app).nextStartedActivity.dataString,
        )
    }

    /** #200: the setting sends the route to Yandex Maps' own dialect, no package pin. */
    @Test fun maps_selected_and_installed_opens_maps() = runTest {
        selectNavigator(RouteNavigatorUris.MAPS)
        installYandexMaps()

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        assertEquals(null, res.reason)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("yandexmaps://maps.yandex.ru/?rtext=~57.0,36.0&rtt=auto", intent.dataString)
        assertEquals(null, intent.`package`)
    }

    /** Maps chosen but absent: the action still works, through Yandex Navigator, and says why. */
    @Test fun maps_selected_but_missing_falls_back_to_yandex() = runTest {
        selectNavigator(RouteNavigatorUris.MAPS)

        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertTrue(res.success)
        assertTrue("the reason must mention the fallback: ${res.reason}",
            res.reason!!.contains("Яндекс Карты"))
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0", intent.dataString)
        assertEquals(null, intent.`package`)
    }

    @Test fun maps_search_and_show_use_the_yandexmaps_scheme() = runTest {
        selectNavigator(RouteNavigatorUris.MAPS)
        installYandexMaps()

        dispatcher.dispatch(actionDef("""{"query":"кафе"}"""), null)
        assertTrue(shadowOf(app).nextStartedActivity.dataString!!
            .startsWith("yandexmaps://maps.yandex.ru/?text="))

        dispatcher.dispatch(actionDef("""{"show":true,"lat":55.75,"lon":37.62}"""), null)
        assertEquals("yandexmaps://maps.yandex.ru/?pt=55.75,37.62&z=14",
            shadowOf(app).nextStartedActivity.dataString)
    }

    /** Maps chosen also covers «поехали домой/на работу» - navigateMaps() has its own
     *  ROUTE_TO_HOME/WORK dialect for that. */
    @Test fun maps_selected_home_shortcut_uses_the_maps_action() = runTest {
        selectNavigator(RouteNavigatorUris.MAPS)
        installYandexMaps()

        val res = dispatcher.dispatch(actionDef("""{"shortcut":"home"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT", intent.action)
        assertEquals("ru.yandex.yandexmaps", intent.`package`)
    }

    /**
     * Review P2: with Maps chosen, «поехали домой» (go=true) must not wait for the Navigator's
     * «Поехали» button - that a11y read only ever looks at the Navigator's window. Without the
     * fix autoGoSupported stays true for any shortcut, and the dispatch fails with the
     * "не удалось проверить маршрут" a11y timeout even though Maps opened successfully.
     */
    @Test fun maps_selected_home_shortcut_with_go_skips_auto_go_wait() = runTest {
        selectNavigator(RouteNavigatorUris.MAPS)
        installYandexMaps()

        val res = dispatcher.dispatch(actionDef("""{"shortcut":"home","go":true}"""), null)
        assertTrue("go on a Maps shortcut must not wait for the Navigator's «Поехали»: ${res.reason}",
            res.success)
    }

    // --- willOpenMaps: what AgentTools asks to pick the right foreground-verification app ---

    @Test fun willOpenMaps_true_for_explicit_app_maps() {
        assertTrue(dispatcher.willOpenMaps(org.json.JSONObject("""{"app":"maps"}""")))
    }

    @Test fun willOpenMaps_true_when_settings_default_is_maps_and_installed() {
        selectNavigator(RouteNavigatorUris.MAPS)
        installYandexMaps()
        assertTrue(dispatcher.willOpenMaps(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))
    }

    @Test fun willOpenMaps_false_when_settings_default_is_maps_but_not_installed() {
        selectNavigator(RouteNavigatorUris.MAPS)
        assertFalse(dispatcher.willOpenMaps(org.json.JSONObject("""{"lat":57.0,"lon":36.0}""")))
    }

    @Test fun willOpenMaps_true_for_shortcut_when_settings_default_is_maps() {
        selectNavigator(RouteNavigatorUris.MAPS)
        installYandexMaps()
        assertTrue(dispatcher.willOpenMaps(org.json.JSONObject("""{"shortcut":"home"}""")))
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

    private fun installWaze() {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(
            android.content.ComponentName("com.waze", "com.waze.Main"))
        pm.addIntentFilterForActivity(
            android.content.ComponentName("com.waze", "com.waze.Main"),
            android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            },
        )
    }

    private fun installGoogleMaps() {
        val pm = shadowOf(app.packageManager)
        pm.addActivityIfNotPresent(
            android.content.ComponentName("com.google.android.apps.maps", "com.google.android.apps.maps.Main"),
        )
        pm.addIntentFilterForActivity(
            android.content.ComponentName("com.google.android.apps.maps", "com.google.android.apps.maps.Main"),
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
