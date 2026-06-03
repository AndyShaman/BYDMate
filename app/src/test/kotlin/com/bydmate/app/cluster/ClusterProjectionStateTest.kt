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

    @Test fun `OFF has no geometry`() {
        assertNull(geometryFor(ClusterMode.OFF, 1280, 480))
    }

    @Test fun `nextMode flips the two projection states`() {
        assertEquals(ClusterMode.FULLSCREEN, nextMode(ClusterMode.OFF))
        assertEquals(ClusterMode.OFF, nextMode(ClusterMode.FULLSCREEN))
    }
}
