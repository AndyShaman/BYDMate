package com.bydmate.app.cluster

import android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION
import android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM
import com.bydmate.app.cluster.ForegroundHintFilter.WindowEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which window-state events may move the "what is on the main screen" state. The cluster carries
 * our own projection, so an event from there must never count — otherwise the blind-spot window
 * comes back over the native 360 view, and no later poll undoes it.
 */
class ForegroundHintFilterTest {

    @Test
    fun `an event from the main display is forwarded`() {
        assertTrue(ForegroundHintFilter.shouldForwardHint(MAIN_DISPLAY, null, null))
    }

    @Test
    fun `an event from the cluster is dropped`() {
        assertFalse(ForegroundHintFilter.shouldForwardHint(CLUSTER_DISPLAY, null, null))
    }

    @Test
    fun `no display on the event - a package living only elsewhere is dropped`() {
        assertFalse(
            ForegroundHintFilter.shouldForwardHint(
                null, pkgOnMainDisplay = false, pkgOnOtherDisplay = true))
    }

    @Test
    fun `no display on the event - a package with a main-screen window is forwarded`() {
        assertTrue(
            ForegroundHintFilter.shouldForwardHint(
                null, pkgOnMainDisplay = true, pkgOnOtherDisplay = true))
        assertTrue(
            ForegroundHintFilter.shouldForwardHint(
                null, pkgOnMainDisplay = true, pkgOnOtherDisplay = false))
    }

    @Test
    fun `no display and no readable windows - the hint still goes through`() {
        // Nothing points at another display, and the poll behind the hint is the safety net.
        assertTrue(ForegroundHintFilter.shouldForwardHint(null, null, null))
    }

    // #303: the cluster's projection stubs resume on their own displays all drive long. A
    // foreground change counts (for every widget rule but the camera) unless display 0 denies it.
    @Test
    fun `confirm - a package seen on the main display is confirmed`() {
        assertTrue(ForegroundHintFilter.confirmsForeground(true))
    }

    @Test
    fun `confirm - a package absent from a readable main display is refused`() {
        assertFalse(ForegroundHintFilter.confirmsForeground(false))
    }

    @Test
    fun `confirm - an unknown answer confirms the package as before`() {
        assertTrue(ForegroundHintFilter.confirmsForeground(null))
    }

    // Display 0's window list: an unreadable application window may be the very app asked about,
    // so it leaves the answer open instead of proving the app absent.
    @Test
    fun `presence - readable launcher, package absent - false`() {
        assertFalse(ForegroundHintFilter.presenceFrom(listOf(WindowEntry(TYPE_APPLICATION, LAUNCHER)), APP)!!)
    }

    @Test
    fun `presence - package among readable windows - true`() {
        assertTrue(ForegroundHintFilter.presenceFrom(
            listOf(WindowEntry(TYPE_SYSTEM, SYSTEM_UI), WindowEntry(TYPE_APPLICATION, APP)), APP)!!)
    }

    @Test
    fun `presence - an unreadable application window next to system ui - unknown`() {
        assertNull(ForegroundHintFilter.presenceFrom(
            listOf(WindowEntry(TYPE_SYSTEM, SYSTEM_UI), WindowEntry(TYPE_APPLICATION, null)), APP))
    }

    @Test
    fun `presence - only unreadable windows - unknown`() {
        assertNull(ForegroundHintFilter.presenceFrom(
            listOf(WindowEntry(TYPE_APPLICATION, null), WindowEntry(TYPE_SYSTEM, null)), APP))
    }

    @Test
    fun `presence - an unreadable system window does not hide the answer`() {
        assertFalse(ForegroundHintFilter.presenceFrom(
            listOf(WindowEntry(TYPE_SYSTEM, null), WindowEntry(TYPE_APPLICATION, LAUNCHER)), APP)!!)
    }

    private companion object {
        const val MAIN_DISPLAY = 0
        const val CLUSTER_DISPLAY = 2
        const val LAUNCHER = "com.android.launcher3"
        const val SYSTEM_UI = "com.android.systemui"
        const val APP = "com.google.android.youtube"
    }
}
