package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filters of the #182 cluster-display snapshot: what survives out of a raw dumpsys. */
class ClusterDisplayDiagTest {

    @Test
    fun `props line keeps key order and shows a missing key as empty`() {
        val raw = """
            ro.build.version.sdk=29
            ro.build.version.release=10
            ro.build.display.id=DiLink3.0
            ro.build.product=song
            ro.product.model=BYD Song
            ro.board.platform=trinket
            ro.build.system.fission_single_os=
        """.trimIndent()

        assertEquals(
            "sdk=29 rel=10 id=DiLink3.0 product=song model=BYD Song platform=trinket " +
                "fission_single_os=",
            ClusterDisplayDiag.propsLine(raw),
        )
    }

    @Test
    fun `props line reports every key even when getprop printed nothing`() {
        val line = ClusterDisplayDiag.propsLine("")
        assertEquals(
            "sdk= rel= id= product= model= platform= fission_single_os=",
            line,
        )
        assertTrue(line.length <= ClusterDisplayDiag.MAX_LINE)
    }

    @Test
    fun `byd props are capped at three with a plus N more counter`() {
        val raw = (1..7).joinToString("\n") { "[ro.byd.p$it]: [$it]" }
        val line = ClusterDisplayDiag.bydPropsLine(raw)
        assertTrue(line.startsWith("byd props: [ro.byd.p1]: [1], [ro.byd.p2]: [2], [ro.byd.p3]: [3]"))
        assertTrue(line.endsWith("+4 more"))
        assertFalse(line.contains("ro.byd.p4"))
    }

    @Test
    fun `byd props reports none on empty output`() {
        assertEquals("byd props: none", ClusterDisplayDiag.bydPropsLine("\n  \n"))
    }

    @Test
    fun `services line pulls names out of service list and keeps at most four`() {
        val list = """
            12	auto_container: [android.gui.IAutoContainer]
            13	container_service: [com.byd.IContainer]
            14	c3: [x]
            15	c4: [x]
            16	c5: [x]
        """.trimIndent()
        val line = ClusterDisplayDiag.servicesLine(list, "Service auto_container: found", "", "fb0 fb1")
        assertTrue(line.contains("container=[auto_container, container_service, c3, c4]"))
        assertFalse(line.contains("c5"))
        assertTrue(line.contains("check_snake=Service auto_container: found"))
        assertTrue(line.contains("check_camel=-"))
        assertTrue(line.contains("graphics=fb0 fb1"))
    }

    @Test
    fun `display lines rank non default displays first and cap at eight`() {
        val raw = buildString {
            repeat(6) { appendLine("mDisplayId=0 name=\"Built-in Screen\" state ON") }
            repeat(5) { appendLine("DisplayDeviceInfo{cluster$it: uniqueId=\"local:2\" layerStack=2}") }
        }
        val ranked = ClusterDisplayDiag.displayLines(raw)

        assertEquals(ClusterDisplayDiag.MAX_DISPLAY_LINES, ranked.kept.size)
        assertEquals(3, ranked.dropped)
        assertTrue(ranked.kept.take(5).all { it.startsWith("DisplayDeviceInfo{cluster") })
        assertTrue(ranked.kept.drop(5).all { it.contains("Built-in") })
    }

    @Test
    fun `display lines drop unrelated dumpsys noise`() {
        val raw = """
            DISPLAY MANAGER (dumpsys display)
              mOnlyCoreApps=false
              mDisplayId=2
        """.trimIndent()
        assertEquals(listOf("mDisplayId=2"), ClusterDisplayDiag.displayLines(raw).kept)
    }

    @Test
    fun `surface flinger keeps at most six keyword lines non default first`() {
        val raw = buildString {
            repeat(4) { appendLine("Display 0 (displayId=0): name=\"Tela\"") }
            repeat(5) { appendLine("Display $it (virtual): layerStack=$it") }
            appendLine("+ Layer 0x1 not a display entry")
        }
        val ranked = ClusterDisplayDiag.surfaceFlingerLines(raw)

        assertEquals(ClusterDisplayDiag.MAX_SURFACE_FLINGER_LINES, ranked.kept.size)
        assertEquals(3, ranked.dropped)
        assertTrue(ranked.kept.take(5).all { it.contains("virtual") })
        assertFalse(ranked.kept.any { it.contains("Layer 0x1") })
    }

    @Test
    fun `surface flinger fallback is needed only for unusable output`() {
        assertTrue(ClusterDisplayDiag.surfaceFlingerFallbackNeeded(""))
        assertTrue(ClusterDisplayDiag.surfaceFlingerFallbackNeeded("unknown command --displays"))
        assertTrue(ClusterDisplayDiag.surfaceFlingerFallbackNeeded("Usage: dumpsys SurfaceFlinger"))
        assertFalse(ClusterDisplayDiag.surfaceFlingerFallbackNeeded("Display 2 (virtual): layerStack=2"))
    }

    @Test
    fun `every kept line is truncated to the log budget`() {
        val long = "mDisplayId=2 " + "x".repeat(1000)
        val displays = ClusterDisplayDiag.displayLines(long)
        val sf = ClusterDisplayDiag.surfaceFlingerLines("layerStack=2 " + "y".repeat(1000))
        val byd = ClusterDisplayDiag.bydPropsLine("[ro.byd.long]: [" + "z".repeat(1000) + "]")
        val services = ClusterDisplayDiag.servicesLine("1	a: [x]", "w".repeat(1000), "", "")

        assertTrue(displays.kept.all { it.length <= ClusterDisplayDiag.MAX_LINE })
        assertTrue(sf.kept.all { it.length <= ClusterDisplayDiag.MAX_LINE })
        assertTrue(byd.length <= ClusterDisplayDiag.MAX_LINE)
        assertTrue(services.length <= ClusterDisplayDiag.MAX_LINE)
    }
}
