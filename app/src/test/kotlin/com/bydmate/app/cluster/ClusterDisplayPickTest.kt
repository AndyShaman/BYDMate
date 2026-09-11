package com.bydmate.app.cluster

import com.bydmate.app.helper.ClusterDisplayDiag
import com.bydmate.app.helper.DisplayDumpFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which display of the daemon's inventory the projection targets (#194). */
class ClusterDisplayPickTest {

    private fun pick(dump: String, preferFull: Boolean = false) =
        pickClusterFromDaemon(ClusterDisplayDiag.parseDisplayDevices(dump), preferFull)

    @Test
    fun `DiLink 4 hidden cluster surface is picked`() {
        val picked = pick(DisplayDumpFixtures.DILINK4)
        assertEquals(1, picked?.id)
        assertEquals("fission_bg_xdjaVirtualSurface", picked?.name)
    }

    @Test
    fun `Leopard 3 resolves to the same surface the app-uid path picks today`() {
        val devices = ClusterDisplayDiag.parseDisplayDevices(DisplayDumpFixtures.LEOPARD3)
        val appUidName = pickProjectionDisplayName(
            devices.filter { it.name.contains("XDJAScreenProjection", ignoreCase = true) }.map { it.name },
            preferFull = false,
        )
        assertEquals(appUidName, pick(DisplayDumpFixtures.LEOPARD3)?.name)
        assertEquals(4, pick(DisplayDumpFixtures.LEOPARD3)?.id)
    }

    @Test
    fun `the full-cluster preference flips the Leopard 3 pick to the underscore zero mirror`() {
        assertEquals(3, pick(DisplayDumpFixtures.LEOPARD3, preferFull = true)?.id)
    }

    @Test
    fun `a private display owned by another app is never picked`() {
        assertNull(pick(DisplayDumpFixtures.FOREIGN_PRIVATE_VD))
    }

    @Test
    fun `a car with only the head unit screen has no cluster`() {
        assertNull(pick(DisplayDumpFixtures.MAIN_DISPLAY_ONLY))
    }

    @Test
    fun `an unknown display name is not taken as the cluster`() {
        val dump = """
            DisplayDeviceInfo{"HDMI Screen": uniqueId="local:1", 1280 x 720, density 320, type HDMI, state ON}
            mDisplayId=7
            mBaseDisplayInfo=DisplayInfo{"HDMI Screen, displayId 7", uniqueId "local:1", app 1280 x 720, real 1280 x 720, density 320}
        """.trimIndent()
        assertNull(pick(dump))
    }
}
