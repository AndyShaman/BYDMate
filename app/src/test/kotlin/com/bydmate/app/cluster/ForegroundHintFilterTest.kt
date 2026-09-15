package com.bydmate.app.cluster

import org.junit.Assert.assertFalse
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

    private companion object {
        const val MAIN_DISPLAY = 0
        const val CLUSTER_DISPLAY = 2
    }
}
