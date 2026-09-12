package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `display summary keeps name resolution owner and flags for a virtual display`() {
        val raw = """DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface": uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0", 1920 x 720, modeId 2, defaultModeId 2, supportedModes [{id=2, width=1920, height=720, fps=60.0}], colorMode 0, supportedColorModes [0], HdrCapabilities null, density 320, 320.0 x 320.0 dpi, appVsyncOff 0, presDeadline 16666666, touch NONE, rotation 0, type VIRTUAL, address null, deviceProductInfo null, state ON, owner com.xdja.containerservice (uid 1000), FLAG_PRIVATE, FLAG_PRESENTATION, FLAG_OWN_CONTENT_ONLY}"""
        val summaries = ClusterDisplayDiag.displaySummaries(raw)
        assertEquals(1, summaries.size)
        val summary = summaries.single()
        assertTrue(summary.contains("1920x720"))
        assertTrue(summary.contains("owner=com.xdja.containerservice (uid 1000)"))
        assertTrue(summary.contains("flags=FLAG_PRIVATE,FLAG_PRESENTATION,FLAG_OWN_CONTENT_ONLY"))
    }

    @Test
    fun `display summary reports missing owner and flags rather than omitting them`() {
        val raw = """DisplayDeviceInfo{"Built-in Screen": uniqueId="local:0", 1920 x 1200, modeId 1, type INTERNAL, state ON}"""
        val summary = ClusterDisplayDiag.displaySummaries(raw).single()
        assertTrue(summary.contains("owner=?"))
        assertTrue(summary.contains("flags=-"))
    }

    @Test
    fun `display summary line stays within the log budget for a very long uniqueId`() {
        val raw = """DisplayDeviceInfo{"cluster": uniqueId="virtual:${"x".repeat(1000)}", 1280 x 480, type VIRTUAL, state ON, owner com.byd.cluster (uid 1000), FLAG_PRIVATE}"""
        val summary = ClusterDisplayDiag.displaySummaries(raw).single()
        assertTrue(summary.length <= ClusterDisplayDiag.MAX_LINE)
        assertTrue(summary.contains("owner=com.byd.cluster (uid 1000)"))
        assertTrue(summary.contains("flags=FLAG_PRIVATE"))
    }

    @Test
    fun `non default mDisplayId line is prioritized within the non default group`() {
        val raw = buildString {
            appendLine("mDisplayId=0 name=\"Built-in\" state ON")
            appendLine("mDisplayId=0 owner sys (uid 1000)")
            repeat(5) { appendLine("name=\"noise$it\" type NOISE") }
            appendLine("mDisplayId=1 name=\"Cluster\" state ON")
        }
        val ranked = ClusterDisplayDiag.displayLines(raw, max = 3)
        assertTrue(ranked.kept.any { it.contains("mDisplayId=1") })
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

    @Test
    fun `parses the DiLink 4 dump into the main display and the hidden cluster surface`() {
        val devices = ClusterDisplayDiag.parseDisplayDevices(DisplayDumpFixtures.DILINK4)
        assertEquals(listOf(0, 1), devices.map { it.id })
        val cluster = devices[1]
        assertEquals("fission_bg_xdjaVirtualSurface", cluster.name)
        assertEquals(1920, cluster.width)
        assertEquals(720, cluster.height)
        // The field line is cut at the cdiag budget before owner/flags/density: the display must
        // still come back, with the unreadable parts reported as absent rather than invented.
        assertEquals(0, cluster.densityDpi)
        assertNull(cluster.ownerPkg)
        assertEquals(-1, cluster.ownerUid)
        assertTrue(cluster.flags.isEmpty())
    }

    @Test
    fun `each logical display is reported once, not once per DisplayInfo field`() {
        // Every block carries mBaseDisplayInfo AND mOverrideDisplayInfo for the same display.
        val ids = ClusterDisplayDiag.parseDisplayDevices(DisplayDumpFixtures.DILINK4).map { it.id }
        assertEquals(ids.distinct(), ids)
    }

    @Test
    fun `parses the Leopard 3 dump with owner, flags and density from the device line`() {
        val devices = ClusterDisplayDiag.parseDisplayDevices(DisplayDumpFixtures.LEOPARD3)
        assertEquals(listOf(0, 2, 3, 4), devices.map { it.id })
        val mini = devices.single { it.id == 4 }
        assertEquals("shared_fission_bg_XDJAScreenProjection_1", mini.name)
        assertEquals(1280, mini.width)
        assertEquals(480, mini.height)
        assertEquals(320, mini.densityDpi)
        assertEquals("com.byd.containerservice", mini.ownerPkg)
        assertEquals(1000, mini.ownerUid)
        assertEquals(listOf("FLAG_PRESENTATION"), mini.flags)
    }

    @Test
    fun `parses a third-party private virtual display with its app uid owner`() {
        val foreign = ClusterDisplayDiag.parseDisplayDevices(DisplayDumpFixtures.FOREIGN_PRIVATE_VD)
            .single { it.id == 6 }
        assertEquals("com.dudu.autoui", foreign.ownerPkg)
        assertEquals(10071, foreign.ownerUid)
        assertTrue(foreign.flags.contains("FLAG_PRIVATE"))
    }

    // --- WindowManager readback (TX_CLUSTER_WM_DIAG) ---

    /** `dumpsys window displays` on Android 10: display 1 carries a forced density (base=), the
     *  main display does not. */
    private val WINDOW_DISPLAYS = """
        WINDOW MANAGER DISPLAY CONTENTS (dumpsys window displays)
          Display: mDisplayId=0
            init=1920x1080 320dpi cur=1920x1080 app=1920x1080 rng=1080x1080-1920x1920
            deferred=false mLayoutNeeded=false
          Display: mDisplayId=1
            init=1920x720 320dpi base=1920x720 160dpi cur=1920x720 app=1920x720 rng=1920x720-1920x720
            deferred=false mLayoutNeeded=false mTouchExcludeRegion=SkRegion()
    """.trimIndent()

    @Test
    fun `wm displays reports the init line of every display, forced and unforced`() {
        val lines = ClusterDisplayDiag.wmDisplayLines(WINDOW_DISPLAYS)
        assertEquals(2, lines.size)
        assertEquals(
            "0: init=1920x1080 320dpi cur=1920x1080 app=1920x1080 rng=1080x1080-1920x1920",
            lines[0],
        )
        assertTrue(
            "the forced display must keep its base= override: ${lines[1]}",
            lines[1].startsWith("1: init=1920x720 320dpi base=1920x720 160dpi"),
        )
    }

    @Test
    fun `wm displays caps the number of displays and the line length`() {
        val many = (0..9).joinToString("\n") { id ->
            "  Display: mDisplayId=$id\n    init=1920x720 320dpi " + "x".repeat(400)
        }
        val lines = ClusterDisplayDiag.wmDisplayLines(many)
        assertEquals(ClusterDisplayDiag.MAX_WM_DISPLAYS, lines.size)
        assertTrue(lines.all { it.length <= ClusterDisplayDiag.MAX_WM_LINE + 4 })
    }

    @Test
    fun `wm displays on a dump without display blocks is empty`() {
        assertTrue(ClusterDisplayDiag.wmDisplayLines("").isEmpty())
    }

    /** `dumpsys activity activities` on Android 10: the plural header line precedes the blob. */
    private val ACTIVITIES = """
        Display #0 (activities from top to bottom):
          Stack #4075:
            * ActivityRecord{a1b2c3 u0 ru.yandex.yandexnavi/.MainActivity t4075}
              packageName=ru.yandex.yandexnavi processName=ru.yandex.yandexnavi
              mActivityComponent=ru.yandex.yandexnavi/.MainActivity
              mLastReportedConfigurations:
               mLastReportedConfiguration={1.0 ?mcc?mnc [ru_RU] ldltr sw360dp w960dp h360dp 160dpi lrg land finger qwerty/v/h -nav/h winConfig={ mBounds=Rect(0, 0 - 1920, 720) mAppBounds=Rect(0, 0 - 1920, 720) mWindowingMode=freeform mDisplayWindowingMode=fullscreen mActivityType=standard} s.6}
              resumed=true
    """.trimIndent()

    @Test
    fun `nav task config reports the dpi the activity itself received`() {
        val lines = ClusterDisplayDiag.taskConfigLines(ACTIVITIES, "ru.yandex.yandexnavi")
        assertEquals(1, lines.size)
        assertTrue(
            "the component and its reported dpi must be on the line: ${lines[0]}",
            lines[0].startsWith("ru.yandex.yandexnavi/.MainActivity dpi=160 raw=\""),
        )
    }

    @Test
    fun `nav task config survives the singular spelling and a missing dpi`() {
        val singular = """
              * ActivityRecord{a1b2c3 u0 ru.yandex.yandexnavi/.MainActivity t4075}
                mLastReportedConfiguration={1.0 ?mcc?mnc [ru_RU] ldltr sw360dp land finger}
        """.trimIndent()
        val lines = ClusterDisplayDiag.taskConfigLines(singular, "ru.yandex.yandexnavi")
        assertEquals(1, lines.size)
        assertTrue("missing dpi must read as ?: ${lines[0]}", lines[0].contains(" dpi=? "))
    }

    @Test
    fun `nav task config names the package when no record is there`() {
        assertEquals(
            listOf("(no ActivityRecord for ru.yandex.yandexnavi.other)"),
            ClusterDisplayDiag.taskConfigLines(ACTIVITIES, "ru.yandex.yandexnavi.other"),
        )
    }

    @Test
    fun `nav task config caps the number of records`() {
        val many = (1..5).joinToString("\n") { i ->
            "    * ActivityRecord{h$i u0 ru.yandex.yandexnavi/.Act$i t40$i}\n" +
                "      mLastReportedConfiguration={1.0 160dpi}"
        }
        assertEquals(
            ClusterDisplayDiag.MAX_TASK_CONFIGS,
            ClusterDisplayDiag.taskConfigLines(many, "ru.yandex.yandexnavi").size,
        )
    }
}
