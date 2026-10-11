package com.bydmate.app.data.camera

import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
            CLUSTER_ALARM to CLUSTER_ALARM_ACTIVITY,
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

    // --- #303: a foreground change counts only when display 0 does not deny it ---
    // Leopard 8, DiLink 5.1: the cluster's projection stubs resume on displays 2-5 every few
    // seconds; UsageStats carries no display, so they read as the foreground of the head unit.

    private var clockMs = 1_000_000L

    private fun poll(pkg: String, ts: Long = clockMs) {
        monitor.onPolled(pkg, ts)
        monitor.publish()
    }

    @Test
    fun `a polled package absent from the main display leaves the published foreground`() {
        monitor.clock = { clockMs }
        monitor.mainDisplayPresence = { pkg -> pkg != CLUSTER_STUB }
        poll(LAUNCHER)
        clockMs += 3_000L
        poll(CLUSTER_STUB)
        assertEquals(LAUNCHER, monitor.foregroundPackage.value)
        assertFalse(monitor.active.value)
    }

    @Test
    fun `an unknown main-display answer publishes the package as today`() {
        monitor.clock = { clockMs }
        monitor.mainDisplayPresence = { null }
        poll(CLUSTER_STUB)
        assertEquals(CLUSTER_STUB, monitor.foregroundPackage.value)
    }

    @Test
    fun `an a11y hint absent from the main display publishes nothing and expires`() {
        monitor.clock = { clockMs }
        var present = false
        monitor.mainDisplayPresence = { pkg -> pkg == MUSIC || present }
        monitor.onForegroundHint(MUSIC, MUSIC_ACTIVITY)
        clockMs += 1_000L
        monitor.onForegroundHint(CLUSTER_ALARM, CLUSTER_ALARM_ACTIVITY)
        assertEquals(MUSIC, monitor.foregroundPackage.value)

        clockMs += 2_500L
        monitor.recheckForegroundCandidate()
        present = true
        monitor.recheckForegroundCandidate()
        monitor.publish()
        assertEquals(MUSIC, monitor.foregroundPackage.value)
    }

    // Cold start: the opening app's window is not in the a11y list yet when its first event lands.
    @Test
    fun `a candidate seen on the main display within the window is published`() {
        monitor.clock = { clockMs }
        var present = false
        monitor.mainDisplayPresence = { pkg -> pkg == LAUNCHER || present }
        poll(LAUNCHER)
        clockMs += 1_000L
        poll(NAVIGATOR)
        assertEquals(LAUNCHER, monitor.foregroundPackage.value)

        clockMs += 1_000L
        present = true
        monitor.recheckForegroundCandidate()
        monitor.publish()
        assertEquals(NAVIGATOR, monitor.foregroundPackage.value)
    }

    @Test
    fun `a candidate never seen on the main display is dropped after the window`() {
        monitor.clock = { clockMs }
        var present = false
        monitor.mainDisplayPresence = { pkg -> pkg == LAUNCHER || present }
        poll(LAUNCHER)
        clockMs += 1_000L
        poll(NAVIGATOR)

        clockMs += 2_500L
        monitor.recheckForegroundCandidate()
        present = true
        clockMs += 500L
        monitor.recheckForegroundCandidate()
        monitor.publish()
        assertEquals(LAUNCHER, monitor.foregroundPackage.value)
    }

    // The camera surface may not be an a11y window: it follows the raw package, unconfirmed.
    @Test
    fun `the camera follows the raw foreground even when absent from the main display`() {
        monitor.clock = { clockMs }
        monitor.mainDisplayPresence = { false }
        monitor.onForegroundHint(NATIVE_CAMERA, CAMERA_ACTIVITY)
        assertTrue(monitor.active.value)
        clockMs += 3_000L
        poll(MUSIC)
        assertFalse(monitor.active.value)
        clockMs += 3_000L
        poll(NATIVE_CAMERA)
        assertTrue(monitor.active.value)
    }

    @Test
    fun `youtube counts only once confirmed on the main display`() {
        monitor.clock = { clockMs }
        var present = false
        monitor.mainDisplayPresence = { present }
        poll(YOUTUBE)
        assertFalse(monitor.youtubeForeground.value)

        clockMs += 3_000L
        monitor.recheckForegroundCandidate()
        present = true
        clockMs += 3_000L
        poll(MUSIC)
        clockMs += 3_000L
        poll(YOUTUBE)
        assertTrue(monitor.youtubeForeground.value)
    }

    // A window read is a binder call outside the lock; the a11y thread can move the foreground
    // while it runs. The callback below plays that thread.
    @Test
    fun `a read finishing after the foreground moved on does not confirm the candidate`() {
        monitor.clock = { clockMs }
        var musicReads = 0
        monitor.mainDisplayPresence = { pkg ->
            when (pkg) {
                MUSIC -> if (++musicReads == 2) {
                    clockMs += 10L
                    monitor.onForegroundHint(YOUTUBE, YOUTUBE_ACTIVITY)
                    true
                } else false
                else -> true
            }
        }
        poll(LAUNCHER)
        clockMs += 1_000L
        poll(MUSIC)
        monitor.recheckForegroundCandidate()
        monitor.publish()
        assertEquals(YOUTUBE, monitor.foregroundPackage.value)
    }

    // A -> B -> A during one read: the package matches again, but the read is from before.
    @Test
    fun `a read spanning a leave and return of the same package does not confirm it`() {
        monitor.clock = { clockMs }
        var musicReads = 0
        monitor.mainDisplayPresence = { pkg ->
            when (pkg) {
                LAUNCHER -> true
                MUSIC -> when (++musicReads) {
                    2 -> {
                        clockMs += 10L
                        monitor.onForegroundHint(YOUTUBE, YOUTUBE_ACTIVITY)
                        clockMs += 10L
                        monitor.onForegroundHint(MUSIC, MUSIC_ACTIVITY)
                        true
                    }
                    1, 3 -> false
                    else -> true
                }
                else -> false
            }
        }
        poll(LAUNCHER)
        clockMs += 1_000L
        poll(MUSIC)
        monitor.recheckForegroundCandidate()
        monitor.publish()
        assertEquals(LAUNCHER, monitor.foregroundPackage.value)

        monitor.recheckForegroundCandidate()
        monitor.publish()
        assertEquals(MUSIC, monitor.foregroundPackage.value)
    }

    // A queryEvents in flight when stop() ran can still land; the next start() must not publish it.
    @Test
    fun `start forgets a foreground confirmed by a poll that finished after stop`() {
        monitor.clock = { clockMs }
        monitor.mainDisplayPresence = { true }
        poll(MUSIC)
        monitor.stop()
        clockMs += 1_000L
        monitor.onPolled(MUSIC, clockMs)
        try {
            monitor.start()
            monitor.publish()
            assertNull(monitor.foregroundPackage.value)
        } finally {
            monitor.stop()
        }
    }

    private companion object {
        const val NATIVE_CAMERA = "com.byd.avc"
        const val CAMERA_ACTIVITY = "com.byd.avc.AutoVideoActivity"
        const val MUSIC = "ru.yandex.music"
        const val MUSIC_ACTIVITY = "ru.yandex.music.main.MainScreenActivity"
        const val YOUTUBE = "com.google.android.youtube"
        const val YOUTUBE_ACTIVITY = "com.google.android.youtube.HomeActivity"
        const val GESTURES = "com.byd.gesture.global"
        const val LAUNCHER = "com.android.launcher3"
        const val NAVIGATOR = "ru.yandex.yandexnavi"
        const val CLUSTER_STUB = "com.example.amapservice"
        const val CLUSTER_ALARM = "com.huawei.hibaic.adshmiic"
        const val CLUSTER_ALARM_ACTIVITY = "com.huawei.mdwmdashboardcastimpl.view.AlarmViewActivity"
    }
}
