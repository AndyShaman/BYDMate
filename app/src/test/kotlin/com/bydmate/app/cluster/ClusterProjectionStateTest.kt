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

    @Test fun `clusterModeFromRaw maps the on-car validated lever values`() {
        assertEquals(ClusterMode.OFF, clusterModeFromRaw(1))         // Off
        assertEquals(ClusterMode.OFF, clusterModeFromRaw(2))         // Simple — not projectable, tear down
        assertEquals(ClusterMode.FULLSCREEN, clusterModeFromRaw(4))  // Full
    }

    @Test fun `clusterModeFromRaw returns null for sentinel and unexpected values`() {
        assertNull(clusterModeFromRaw(-10011))  // permission sentinel
        assertNull(clusterModeFromRaw(0))
        assertNull(clusterModeFromRaw(3))
    }
}
