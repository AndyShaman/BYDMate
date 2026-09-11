package com.bydmate.app.helper

/**
 * `dumpsys display` fragments the display parser and the cluster pick are tested against.
 *
 * [DILINK4] is copied verbatim out of a field log (Алимжан, Song Plus Champion 2021, DiLink 4.0,
 * 2026-09-11, `.research/fid-dumps/bydmate_logs_alimzhan_dilink4_20260911.txt`) — including the
 * 300-character truncation the cdiag log budget applied to it, which is why the virtual display
 * carries no owner, flags or density there. Only `$` had to be escaped for Kotlin.
 */
internal object DisplayDumpFixtures {

    val DILINK4 = """
        DisplayDeviceInfo{"Встроенный экран": uniqueId="local:19260656133175937", 1920 x 1080, modeId 1, defaultModeId 1, supportedModes [{id=1, width=1920, height=1080, fps=60.000004}], colorMode 0, supportedColorModes [0], HdrCapabilities android.view.Display${'$'}HdrCapabilities@4a41fe79, density 240
        PhysicalDisplayInfo{1920 x 1080, 60.000004 fps, density 1.5, 320.842 x 318.976 dpi, secure true, appVsyncOffset 1000000, bufferDeadline 16666666}
        DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface": uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0", 1920 x 720, modeId 2, defaultModeId 2, supportedModes [{id=2, width=1920, height=720, fps=60.0}], colorMode 0, supportedColorModes [0], HdrCapabilities null,
        mCurrentSurface=Surface(name=null)/@0x4a5d750
        mBaseDisplayInfo=DisplayInfo{"Встроенный экран, displayId 0", uniqueId "local:19260656133175937", app 1920 x 1080, real 1920 x 1080, largest app 1920 x 1080, smallest app 1920 x 1080, mode 1, defaultMode 1, modes [{id=1, width=1920, height=1080, fps=60.000004}], colorMode 0, supportedColorM
        mOverrideDisplayInfo=DisplayInfo{"Встроенный экран, displayId 0", uniqueId "local:19260656133175937", app 1920 x 990, real 1920 x 1080, largest app 1920 x 1782, smallest app 1080 x 942, mode 1, defaultMode 1, modes [{id=1, width=1920, height=1080, fps=60.000004}], colorMode 0, supportedColo
        mDisplayId=1
        mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface, displayId 1", uniqueId "virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0", app 1920 x 720, real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, mode 2, defaultMode 2, modes [{id=2, width=1920, he
    """.trimIndent()

    /**
     * Leopard 3 shape (memory `reference_cluster_projection_phase0`, research 2026-08-19): the bare
     * cluster surface plus its two mirrors, all owned by com.byd.containerservice under a system uid.
     * Untruncated, as the daemon reads it.
     */
    val LEOPARD3 = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:0", 1920 x 1080, density 240, type INTERNAL, state ON, FLAG_DEFAULT_DISPLAY}
        DisplayDeviceInfo{"fission_bg_XDJAScreenProjection": uniqueId="virtual:com.byd.containerservice,1000,fission_bg_XDJAScreenProjection,0", 1280 x 480, density 320, type VIRTUAL, state ON, owner com.byd.containerservice (uid 1000), FLAG_PRESENTATION}
        DisplayDeviceInfo{"shared_fission_bg_XDJAScreenProjection_0": uniqueId="virtual:com.byd.containerservice,1000,shared_fission_bg_XDJAScreenProjection_0,0", 1280 x 480, density 320, type VIRTUAL, state ON, owner com.byd.containerservice (uid 1000), FLAG_PRESENTATION}
        DisplayDeviceInfo{"shared_fission_bg_XDJAScreenProjection_1": uniqueId="virtual:com.byd.containerservice,1000,shared_fission_bg_XDJAScreenProjection_1,0", 1280 x 480, density 320, type VIRTUAL, state ON, owner com.byd.containerservice (uid 1000), FLAG_PRESENTATION}
        mDisplayId=0
        mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", uniqueId "local:0", app 1920 x 1080, real 1920 x 1080, density 240}
        mDisplayId=2
        mBaseDisplayInfo=DisplayInfo{"fission_bg_XDJAScreenProjection, displayId 2", uniqueId "virtual:com.byd.containerservice,1000,fission_bg_XDJAScreenProjection,0", app 1280 x 480, real 1280 x 480, density 320}
        mDisplayId=3
        mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_0, displayId 3", uniqueId "virtual:com.byd.containerservice,1000,shared_fission_bg_XDJAScreenProjection_0,0", app 1280 x 480, real 1280 x 480, density 320}
        mDisplayId=4
        mBaseDisplayInfo=DisplayInfo{"shared_fission_bg_XDJAScreenProjection_1, displayId 4", uniqueId "virtual:com.byd.containerservice,1000,shared_fission_bg_XDJAScreenProjection_1,0", app 1280 x 480, real 1280 x 480, density 320}
    """.trimIndent()

    /**
     * A head unit with a third-party projection app running: `com.dudu.autoui` owns a private
     * virtual display of its own (the case byd-dashcast's enumerator excludes by owner uid).
     */
    val FOREIGN_PRIVATE_VD = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:0", 1920 x 1080, density 240, type INTERNAL, state ON}
        DisplayDeviceInfo{"dudu_cluster": uniqueId="virtual:com.dudu.autoui,10071,dudu_cluster,0", 1280 x 480, density 320, type VIRTUAL, state ON, owner com.dudu.autoui (uid 10071), FLAG_PRIVATE, FLAG_OWN_CONTENT_ONLY}
        mDisplayId=0
        mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", uniqueId "local:0", app 1920 x 1080, real 1920 x 1080, density 240}
        mDisplayId=6
        mBaseDisplayInfo=DisplayInfo{"dudu_cluster, displayId 6", uniqueId "virtual:com.dudu.autoui,10071,dudu_cluster,0", app 1280 x 480, real 1280 x 480, density 320}
    """.trimIndent()

    /** Song / DiLink 3.0 (#182): the head unit screen and nothing else. */
    val MAIN_DISPLAY_ONLY = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:0", 1920 x 1080, density 240, type INTERNAL, state ON}
        mDisplayId=0
        mBaseDisplayInfo=DisplayInfo{"Built-in Screen, displayId 0", uniqueId "local:0", app 1920 x 1080, real 1920 x 1080, density 240}
    """.trimIndent()
}
