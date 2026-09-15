package com.bydmate.app.data.camera

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Merge rule between the two sources of "what is on screen": the accessibility hint, which
 * arrives as the window opens, and the UsageStats poll, which is up to half a second behind.
 * The blind-spot window is taken down by this state, so a poll result older than the hint must
 * not put the camera back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CameraForegroundHintTest {

    private val monitor = CameraStateMonitor(ApplicationProvider.getApplicationContext())

    @Test
    fun `a hint takes effect at once`() {
        monitor.onForegroundHint(NATIVE_CAMERA)
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a poll event older than the hint does not undo it`() {
        monitor.onForegroundHint(NATIVE_CAMERA)
        val stale = System.currentTimeMillis() - 5_000L
        assertFalse(monitor.acceptForeground("com.android.chrome", stale))
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a newer poll event wins over the hint`() {
        monitor.onForegroundHint(NATIVE_CAMERA)
        val later = System.currentTimeMillis() + 5_000L
        assertTrue(monitor.acceptForeground("com.android.chrome", later))
        // A hint is stamped with the moment it arrives, so one that lands after this newer event
        // is still the older observation and is dropped — the poll keeps the screen it saw.
        assertFalse(monitor.acceptForeground(NATIVE_CAMERA, System.currentTimeMillis()))
    }

    @Test
    fun `hints from our own windows and from the system bars are ignored`() {
        monitor.onForegroundHint(NATIVE_CAMERA)
        monitor.onForegroundHint("com.bydmate.app")
        monitor.onForegroundHint("com.android.systemui")
        monitor.onForegroundHint("com.android.inputmethod.latin")
        assertTrue(monitor.active.value)
        assertEquals(NATIVE_CAMERA, monitor.foregroundPackage.value)
    }

    @Test
    fun `a hint for another app clears the camera state`() {
        monitor.onForegroundHint(NATIVE_CAMERA)
        monitor.onForegroundHint("com.google.android.youtube")
        assertFalse(monitor.active.value)
        assertTrue(monitor.youtubeForeground.value)
    }

    private companion object {
        const val NATIVE_CAMERA = "com.byd.avc"
    }
}
