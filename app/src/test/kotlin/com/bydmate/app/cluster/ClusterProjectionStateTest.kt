package com.bydmate.app.cluster

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterProjectionStateTest {

    @Test fun `fullscreen geometry fills the whole cluster`() {
        assertEquals(
            ClusterGeometry(width = 1280, height = 480, xOffset = 0, yOffset = 0),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480),
        )
    }

    @Test fun `default percentages equal a full-screen window`() {
        assertEquals(
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 100, 100),
        )
    }

    @Test fun `half width centers horizontally only`() {
        assertEquals(
            ClusterGeometry(width = 640, height = 480, xOffset = 320, yOffset = 0),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 50, 100),
        )
    }

    @Test fun `half height centers vertically only`() {
        assertEquals(
            ClusterGeometry(width = 1280, height = 240, xOffset = 0, yOffset = 120),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 100, 50),
        )
    }

    @Test fun `independent axes shrink and center on both`() {
        assertEquals(
            ClusterGeometry(width = 640, height = 240, xOffset = 320, yOffset = 120),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 50, 50),
        )
    }

    @Test fun `percentages below the minimum are clamped`() {
        assertEquals(
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, MIN_PROJECTION_PCT, MIN_PROJECTION_PCT),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 5, 5),
        )
    }

    @Test fun `window can shrink to a fifth of the panel for the native mini zone`() {
        // Sea Lion 07 mini zone is ~1/3 of the 1920 px panel; the floor must allow narrower-than-half
        // windows so the tester can match the zone instead of overflowing it (#48).
        assertEquals(
            ClusterGeometry(width = 384, height = 720, xOffset = 1536, yOffset = 0),
            geometryFor(ClusterMode.FULLSCREEN, 1920, 720, 20, 100, MAX_OFFSET_PCT, MAX_OFFSET_PCT),
        )
    }

    @Test fun `offset zero pins the window to the left-top edge`() {
        assertEquals(
            ClusterGeometry(width = 640, height = 240, xOffset = 0, yOffset = 0),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 50, 50, MIN_OFFSET_PCT, MIN_OFFSET_PCT),
        )
    }

    @Test fun `offset hundred pins the window to the right-bottom edge`() {
        assertEquals(
            ClusterGeometry(width = 640, height = 240, xOffset = 640, yOffset = 240),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 50, 50, MAX_OFFSET_PCT, MAX_OFFSET_PCT),
        )
    }

    @Test fun `offset has no effect on a full-size window`() {
        assertEquals(
            ClusterGeometry(width = 1280, height = 480, xOffset = 0, yOffset = 0),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 100, 100, MAX_OFFSET_PCT, MAX_OFFSET_PCT),
        )
    }

    @Test fun `offsets outside the range are clamped`() {
        assertEquals(
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 50, 50, MIN_OFFSET_PCT, MAX_OFFSET_PCT),
            geometryFor(ClusterMode.FULLSCREEN, 1280, 480, 50, 50, -20, 180),
        )
    }

    @Test fun `OFF has no geometry`() {
        assertNull(geometryFor(ClusterMode.OFF, 1280, 480))
    }

    @Test fun `nextMode flips the two projection states`() {
        assertEquals(ClusterMode.FULLSCREEN, nextMode(ClusterMode.OFF))
        assertEquals(ClusterMode.OFF, nextMode(ClusterMode.FULLSCREEN))
    }

    // --- renderPlanFor (content scale) ---

    private val window960 get() = geometryFor(ClusterMode.FULLSCREEN, 1920, 720, 50, 100)!!  // 960x720

    @Test fun `default render plan matches the window at native density`() {
        assertEquals(RenderPlan(960, 720, 320), renderPlanFor(window960, clusterDensityDpi = 320))
    }

    @Test fun `lower scale lowers density without resizing the buffer`() {
        assertEquals(RenderPlan(960, 720, 160), renderPlanFor(window960, 320, scalePct = 50))
    }

    @Test fun `scale is clamped to the allowed range`() {
        assertEquals(
            renderPlanFor(window960, 320, scalePct = MIN_SCALE_PCT),
            renderPlanFor(window960, 320, scalePct = 10),
        )
    }

    // --- shouldRecoverCompositor (black cluster after car reboot mid-projection) ---

    @Test fun `stale marker with no live projection is recovered`() {
        assertEquals(true, shouldRecoverCompositor(markerSet = true, mode = ClusterMode.OFF, autoContainer = true))
    }

    @Test fun `live projection in this process owns the compositor - no recovery`() {
        assertEquals(false, shouldRecoverCompositor(markerSet = true, mode = ClusterMode.FULLSCREEN, autoContainer = true))
    }

    @Test fun `no marker means nothing to recover`() {
        assertEquals(false, shouldRecoverCompositor(markerSet = false, mode = ClusterMode.OFF, autoContainer = true))
    }

    @Test fun `auto-container off means the user manages compositor power manually`() {
        assertEquals(false, shouldRecoverCompositor(markerSet = true, mode = ClusterMode.OFF, autoContainer = false))
    }

    // --- shouldRecoverDirectTask (freeform task stranded on cluster display after crash) ---

    @Test fun `no marker means nothing to recover for direct task`() {
        assertEquals(false, shouldRecoverDirectTask(markerDisplayId = -1, mode = ClusterMode.OFF))
    }

    @Test fun `live direct projection owns the display - no task recovery`() {
        assertEquals(false, shouldRecoverDirectTask(markerDisplayId = 4, mode = ClusterMode.FULLSCREEN))
    }

    @Test fun `stale display marker with no live projection triggers direct task recovery`() {
        assertEquals(true, shouldRecoverDirectTask(markerDisplayId = 4, mode = ClusterMode.OFF))
    }

    // --- shouldAbsorbDisplayDensity (guard against compounding density overrides) ---

    @Test fun `live direct member blocks density absorption`() {
        assertEquals(false, shouldAbsorbDisplayDensity(liveDirectDisplayId = 4, markerDisplayId = -1, metricsDpi = 230))
    }

    @Test fun `surviving crash marker blocks density absorption`() {
        assertEquals(false, shouldAbsorbDisplayDensity(liveDirectDisplayId = -1, markerDisplayId = 4, metricsDpi = 230))
    }

    @Test fun `no member and no marker allows absorption when dpi is positive`() {
        assertEquals(true, shouldAbsorbDisplayDensity(liveDirectDisplayId = -1, markerDisplayId = -1, metricsDpi = 320))
    }

    @Test fun `zero dpi from metrics is never absorbed`() {
        assertEquals(false, shouldAbsorbDisplayDensity(liveDirectDisplayId = -1, markerDisplayId = -1, metricsDpi = 0))
    }

    // --- shouldClearDirectMarker (gate marker clear on confirmed recovery) ---

    @Test fun `failed density reset keeps the marker even when task reclaim succeeds`() {
        assertEquals(false, shouldClearDirectMarker(resetOk = false, taskFound = true, modeOk = true, moveOk = true))
    }

    @Test fun `task not found counts as gone - marker may be cleared if reset succeeded`() {
        assertEquals(true, shouldClearDirectMarker(resetOk = true, taskFound = false, modeOk = false, moveOk = false))
    }

    @Test fun `full confirmed recovery clears the marker`() {
        assertEquals(true, shouldClearDirectMarker(resetOk = true, taskFound = true, modeOk = true, moveOk = true))
    }

    @Test fun `successful reset but failed move keeps the marker`() {
        assertEquals(false, shouldClearDirectMarker(resetOk = true, taskFound = true, modeOk = true, moveOk = false))
    }

    @Test fun `successful reset but failed mode keeps the marker`() {
        assertEquals(false, shouldClearDirectMarker(resetOk = true, taskFound = true, modeOk = false, moveOk = true))
    }

    @Test
    fun `freeformBounds maps geometry to left top right bottom`() {
        // Andy's on-car preset (2026-07-15): 1280x403 window, y offset 38 -> mFrame [0,38][1280,441].
        val geo = ClusterGeometry(width = 1280, height = 403, xOffset = 0, yOffset = 38)
        assertArrayEquals(intArrayOf(0, 38, 1280, 441), freeformBounds(geo))
    }

    @Test
    fun `freeformBounds carries offsets into all four edges`() {
        val geo = ClusterGeometry(width = 640, height = 240, xOffset = 320, yOffset = 120)
        assertArrayEquals(intArrayOf(320, 120, 960, 360), freeformBounds(geo))
    }
}
