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
        mockk<ClusterVoiceControl>(relaxed = true))

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
}
