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

    /**
     * DiLink 5.0 on Android 12 (sdk 32) with the cluster hidden from the app uid, copied verbatim
     * out of a field log (`han_bydmate_logs_20260912_174608.txt`, 2026-09-12): the cdiag display
     * lines minus our own `dev:` summaries. Android 12 closes the quote after the name and prints
     * a stray one after the id: `DisplayInfo{"<name>", displayId N", …`. Same 300-character
     * truncation as [DILINK4], and the main display's logical line fell out of the cdiag budget,
     * so only display 2 is in here, without owner, flags or density.
     */
    val ANDROID12_FIELD = """
        DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface": uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0", 1920 x 720, modeId 2, defaultModeId 2, supportedModes [{id=2, width=1920, height=720, fps=60.0, alternativeRefreshRates=[]}], colorMode 0, supportedColorMode
        mDisplayId=2
        mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface", displayId 2", displayGroupId 0, FLAG_PRESENTATION, real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720, appVsyncOff 0, presDeadline 16666666, mode 2, defaultMode 2, modes [{id=2, width=1920, height=720, fps=60.0, alt
        mOverrideDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface", displayId 2", displayGroupId 0, FLAG_PRESENTATION, real 1920 x 720, largest app 1920 x 1920, smallest app 720 x 720, appVsyncOff 0, presDeadline 16666666, mode 2, defaultMode 2, modes [{id=2, width=1920, height=720, fps=60.0,
        mDisplayId=2
        mStaticDisplayInfo=StaticDisplayInfo{isInternal=true, density=1.5, secure=true, deviceProductInfo=DeviceProductInfo{name=, manufacturerPnpId=QCM, productId=1, modelYear=null, manufactureDate=ManufactureDate{week=27, year=2006}, connectionToSinkType=0}}
        mCurrentSurface=Surface(name=null)/@0xb74a4d9
        mViewports=[DisplayViewport{type=INTERNAL, valid=true, isActive=true, displayId=0, uniqueId='local:4630946674560563842', physicalPort=130, orientation=0, logicalFrame=Rect(0, 0 - 1920, 1080), physicalFrame=Rect(0, 0 - 1920, 1080), deviceWidth=1920, deviceHeight=1080}]
    """.trimIndent()

    /**
     * The same car's dump untruncated, RECONSTRUCTED: the device lines carry exactly the fields
     * the daemon's own `dev:` summaries of that log report (name, size, type, state, owner,
     * flags, uniqueId; [ClusterDisplayDiagTest] checks that they summarize back to those lines),
     * and the logical lines follow the Android 12 wording of [ANDROID12_FIELD]. No density: the
     * log does not show one per display.
     */
    val ANDROID12_FULL = """
        DisplayDeviceInfo{"Built-in Screen": uniqueId="local:4630946674560563842", 1920 x 1080, modeId 1, defaultModeId 1, type INTERNAL, state ON, FLAG_DEFAULT_DISPLAY, FLAG_ROTATES_WITH_CONTENT, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS}
        DisplayDeviceInfo{"fission_bg_xdjaVirtualSurface": uniqueId="virtual:com.xdja.containerservice,1000,fission_bg_xdjaVirtualSurface,0", 1920 x 720, modeId 2, defaultModeId 2, type VIRTUAL, state ON, owner com.xdja.containerservice (uid 1000), FLAG_PRESENTATION, FLAG_OWN_CONTENT_ONLY}
        mDisplayId=0
        mBaseDisplayInfo=DisplayInfo{"Built-in Screen", displayId 0", displayGroupId 0, FLAG_SECURE, FLAG_SUPPORTS_PROTECTED_BUFFERS, real 1920 x 1080, largest app 1920 x 1080, smallest app 1920 x 1080}
        mDisplayId=2
        mBaseDisplayInfo=DisplayInfo{"fission_bg_xdjaVirtualSurface", displayId 2", displayGroupId 0, FLAG_PRESENTATION, real 1920 x 720, largest app 1920 x 720, smallest app 1920 x 720}
    """.trimIndent()
}
