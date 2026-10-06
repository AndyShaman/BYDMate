package com.bydmate.app.data.camera

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.ui.widget.WidgetController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** The screen and widget part of the trace: what took the screen, which hints were ignored,
 *  and why the widget hid, linked to the screen change that caused it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ScreenTraceTest {

    @get:Rule val trace = TraceRecorder()

    private lateinit var monitor: CameraStateMonitor

    @Before fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        shadowOf(context.packageManager).addActivityIfNotPresent(ComponentName(CAMERA, CAMERA_ACTIVITY))
        monitor = CameraStateMonitor(context)
    }

    private fun widgetHideReason() = WidgetController.hideReason(monitor.active.value, false, false, monitor.foregroundPackage.value, emptySet())

    // --- Field log 28.09: the camera app's parking radar overlay read as the camera ---

    @Test fun `an overlay window of the camera app is recorded as ignored once per run and the widget stays`() {
        repeat(3) { monitor.onForegroundHint(CAMERA, RADAR_OVERLAY) }

        // One line, no repeat counter: the overlay firing on every gesture used to fill the ring.
        assertEquals(listOf("screen hint-ignored pkg=$CAMERA class=$RADAR_OVERLAY #1"), trace.events())
        assertFalse(monitor.active.value)
        assertNull(widgetHideReason())
    }

    @Test fun `the camera taking the screen hides the widget, caused by that screen change`() {
        monitor.onForegroundHint(CAMERA, CAMERA_ACTIVITY)
        assertTrue(monitor.active.value)
        val reason = widgetHideReason()
        WidgetController.traceVisibility(reason, previous = null)

        monitor.onForegroundHint(NAVIGATOR, null) // not an activity: ignored, the camera stays
        monitor.onForegroundHint("com.android.systemui", "android.widget.FrameLayout") // never a screen
        WidgetController.traceVisibility(null, previous = reason)

        assertEquals(
            listOf(
                "screen foreground pkg=$CAMERA class=$CAMERA_ACTIVITY src=a11y #1",
                "widget hide reason=camera #2 by=#1",
                "screen hint-ignored pkg=$NAVIGATOR #3",
                "widget show was=camera #4 by=#1",
            ),
            trace.events(),
        )
    }

    @Test fun `a hide by our own overlay has no screen cause`() {
        monitor.onForegroundHint(CAMERA, CAMERA_ACTIVITY)
        WidgetController.traceVisibility("suppressed:blindspot", previous = null)
        assertEquals("widget hide reason=suppressed:blindspot #2", trace.events().last())
    }

    private companion object {
        const val CAMERA = "com.byd.avc"
        const val CAMERA_ACTIVITY = "com.byd.avc.AvmActivity"
        const val RADAR_OVERLAY = "com.byd.avc.ui.RadarOverlayWindow"
        const val NAVIGATOR = "ru.yandex.yandexnavi"
    }
}
