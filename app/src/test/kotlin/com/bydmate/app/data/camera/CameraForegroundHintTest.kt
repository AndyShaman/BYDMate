package com.bydmate.app.data.camera

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Merge rule between the two sources of "what is on screen": the accessibility hint, which
 * arrives as the window opens, and the UsageStats poll, which is up to half a second behind.
 * The blind-spot window is taken down by this state, so a poll result older than the hint must
 * not put the camera back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CameraForegroundHintTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val monitor = CameraStateMonitor(context)

    @Before
    fun registerActivities() {
        val pm = shadowOf(context.packageManager)
        for ((pkg, cls) in listOf(
            NATIVE_CAMERA to CAMERA_ACTIVITY, MUSIC to MUSIC_ACTIVITY, YOUTUBE to YOUTUBE_ACTIVITY,
        )) pm.addActivityIfNotPresent(ComponentName(pkg, cls))
        ShadowLog.clear()
    }

    @Test
    fun `a hint takes effect at once`() {
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a poll event older than the hint does not undo it`() {
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        val stale = System.currentTimeMillis() - 5_000L
        assertFalse(monitor.acceptForeground("com.android.chrome", stale))
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a newer poll event wins over the hint`() {
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        val later = System.currentTimeMillis() + 5_000L
        assertTrue(monitor.acceptForeground("com.android.chrome", later))
        // A hint is stamped with the moment it arrives, so one that lands after this newer event
        // is still the older observation and is dropped — the poll keeps the screen it saw.
        assertFalse(monitor.acceptForeground(NATIVE_CAMERA, System.currentTimeMillis()))
    }

    @Test
    fun `hints from our own windows and from the system bars are ignored`() {
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        monitor.onForegroundHint("com.bydmate.app", "com.bydmate.app.MainActivity")
        monitor.onForegroundHint("com.android.systemui", "android.widget.FrameLayout")
        monitor.onForegroundHint("com.android.inputmethod.latin", "android.inputmethodservice.SoftInputWindow")
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a hint for another app clears the camera state`() {
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        monitor.onForegroundHint(YOUTUBE, YOUTUBE_ACTIVITY)
        assertFalse(monitor.active.value)
        assertTrue(monitor.youtubeForeground.value)
    }

    // Field log 28.09, driving with music on screen:
    //   08:27:44.818 I/CameraMonitor: foreground hint: com.byd.avc (a11y)
    //   08:27:44.820 I/WidgetController: widget: hidden (camera)
    // The event came from the camera app's parking radar overlay, a window with a LinearLayout
    // root and no activity behind it: the camera never took the screen.
    @Test
    fun `the parking radar overlay of the camera app is not the camera`() {
        monitor.onForegroundHint(MUSIC, MUSIC_ACTIVITY)
        monitor.onForegroundHint(NATIVE_CAMERA, "android.widget.LinearLayout")
        assertFalse(monitor.active.value)
        assertEquals(MUSIC, monitor.foregroundPackage.value)
    }

    @Test
    fun `an overlay of another app does not clear the camera`() {
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        monitor.onForegroundHint(GESTURES, "android.widget.FrameLayout")
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a hint without a window class is ignored`() {
        monitor.onForegroundHint(MUSIC, MUSIC_ACTIVITY)
        monitor.onForegroundHint(NATIVE_CAMERA, null)
        assertFalse(monitor.active.value)
        assertEquals(MUSIC, monitor.foregroundPackage.value)
    }

    // A gesture overlay fires on every swipe: a run of the same ignored window is one log line.
    @Test
    fun `repeated identical ignored hints leave one log line`() {
        repeat(3) { monitor.onForegroundHint(GESTURES, "android.widget.FrameLayout") }
        val ignored = ShadowLog.getLogsForTag("CameraMonitor").map { it.msg }
            .filter { it.startsWith("foreground hint ignored:") }
        assertEquals(
            listOf("foreground hint ignored: $GESTURES class=android.widget.FrameLayout (not an activity)"),
            ignored)
    }

    private companion object {
        const val NATIVE_CAMERA = "com.byd.avc"
        const val CAMERA_ACTIVITY = "com.byd.avc.AutoVideoActivity"
        const val MUSIC = "ru.yandex.music"
        const val MUSIC_ACTIVITY = "ru.yandex.music.main.MainScreenActivity"
        const val YOUTUBE = "com.google.android.youtube"
        const val YOUTUBE_ACTIVITY = "com.google.android.youtube.HomeActivity"
        const val GESTURES = "com.byd.gesture.global"
    }
}
