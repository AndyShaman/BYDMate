package com.bydmate.app.data.automation

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
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

/**
 * #200: `app="maps"` sends the same three navigation kinds to Yandex Maps instead of the
 * Navigator. Anything else, including no value at all, must reach the Navigator byte-for-byte
 * as before — that default is what every released build does today.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherNavigateMapsTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = ActionDispatcher(
        mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true))

    private fun actionDef(payload: String) =
        ActionDef(command = "", displayName = "navi", kind = "navigate", payload = payload)

    @Test fun `route goes to maps from the current position`() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0,"app":"maps"}"""), null)
        assertTrue(res.success)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("yandexmaps://maps.yandex.ru/?rtext=~57.0,36.0&rtt=auto", intent.dataString)
        // Not package-pinned: the scheme resolves to whichever store variant is installed.
        assertEquals(null, intent.`package`)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun `search opens the maps search`() = runTest {
        dispatcher.dispatch(actionDef("""{"query":"кафе","app":"maps"}"""), null)
        assertTrue(shadowOf(app).nextStartedActivity.dataString!!
            .startsWith("yandexmaps://maps.yandex.ru/?text="))
    }

    @Test fun `show drops a pin without a route`() = runTest {
        dispatcher.dispatch(actionDef("""{"show":true,"lat":55.75,"lon":37.62,"label":"Кафе","app":"maps"}"""), null)
        assertEquals("yandexmaps://maps.yandex.ru/?pt=55.75,37.62&z=14",
            shadowOf(app).nextStartedActivity.dataString)
    }

    @Test fun `home and work go through the maps shortcut actions`() = runTest {
        dispatcher.dispatch(actionDef("""{"shortcut":"home","app":"maps"}"""), null)
        val intent = shadowOf(app).nextStartedActivity
        assertEquals("ru.yandex.yandexmaps.action.ROUTE_TO_HOME_SHORTCUT", intent.action)
        assertTrue("a Maps package must be pinned, got ${intent.`package`}",
            intent.`package`!!.startsWith("ru.yandex.yandexmaps"))

        dispatcher.dispatch(actionDef("""{"shortcut":"work","app":"maps"}"""), null)
        assertEquals("ru.yandex.yandexmaps.action.ROUTE_TO_WORK_SHORTCUT",
            shadowOf(app).nextStartedActivity.action)
    }

    @Test fun `an unknown shortcut is refused without starting anything`() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"shortcut":"dacha","app":"maps"}"""), null)
        assertFalse(res.success)
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test fun `a route without coordinates is refused`() = runTest {
        val res = dispatcher.dispatch(actionDef("""{"app":"maps"}"""), null)
        assertFalse(res.success)
        assertEquals(null, shadowOf(app).nextStartedActivity)
    }

    @Test fun `without the parameter every kind still goes to the navigator`() = runTest {
        dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0}"""), null)
        assertEquals("yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0",
            shadowOf(app).nextStartedActivity.dataString)

        dispatcher.dispatch(actionDef("""{"query":"кафе"}"""), null)
        assertTrue(shadowOf(app).nextStartedActivity.dataString!!
            .startsWith("yandexnavi://map_search?text="))

        dispatcher.dispatch(actionDef("""{"show":true,"lat":55.75,"lon":37.62}"""), null)
        assertTrue(shadowOf(app).nextStartedActivity.dataString!!
            .startsWith("yandexnavi://show_point_on_map?"))

        dispatcher.dispatch(actionDef("""{"shortcut":"home"}"""), null)
        assertEquals("ru.yandex.yandexnavi", shadowOf(app).nextStartedActivity.`package`)
    }

    @Test fun `an unknown app value keeps the navigator default`() = runTest {
        dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0,"app":"navigator"}"""), null)
        assertEquals("yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0",
            shadowOf(app).nextStartedActivity.dataString)

        dispatcher.dispatch(actionDef("""{"lat":57.0,"lon":36.0,"app":"2gis"}"""), null)
        assertEquals("yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0",
            shadowOf(app).nextStartedActivity.dataString)
    }

    @Test fun `the shortcut failure text tells the driver what to do`() {
        // Robolectric starts activities happily, so the "no package took it" branch is checked
        // through the string it reports rather than through a simulated refusal.
        val text = app.getString(R.string.navigate_maps_shortcut_failed)
        assertTrue(text.contains("BYDMate"))
    }
}
