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

    @Test fun `OFF has no geometry`() {
        assertNull(geometryFor(ClusterMode.OFF, 1280, 480))
    }

    @Test fun `nextMode flips the two projection states`() {
        assertEquals(ClusterMode.FULLSCREEN, nextMode(ClusterMode.OFF))
        assertEquals(ClusterMode.OFF, nextMode(ClusterMode.FULLSCREEN))
    }
}
