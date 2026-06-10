package com.bydmate.app.cluster

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

    // --- renderPlanFor (content-scale levers) ---

    private val window960 get() = geometryFor(ClusterMode.FULLSCREEN, 1920, 720, 50, 100)!!  // 960x720

    @Test fun `default render plan matches the window at native density`() {
        assertEquals(RenderPlan(960, 720, 320), renderPlanFor(window960, clusterDensityDpi = 320))
    }

    @Test fun `lower scale lowers density without resizing the buffer`() {
        assertEquals(RenderPlan(960, 720, 160), renderPlanFor(window960, 320, scalePct = 50))
    }

    @Test fun `oversample doubles the buffer and keeps density`() {
        assertEquals(RenderPlan(1920, 1440, 320), renderPlanFor(window960, 320, oversample = true))
    }

    @Test fun `scale and oversample combine independently`() {
        assertEquals(RenderPlan(1920, 1440, 240), renderPlanFor(window960, 320, scalePct = 75, oversample = true))
    }

    @Test fun `scale is clamped to the allowed range`() {
        assertEquals(
            renderPlanFor(window960, 320, scalePct = MIN_SCALE_PCT),
            renderPlanFor(window960, 320, scalePct = 10),
        )
    }
}
