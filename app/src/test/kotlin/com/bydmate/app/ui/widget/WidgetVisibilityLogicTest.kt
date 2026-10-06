package com.bydmate.app.ui.widget

import com.bydmate.app.data.camera.CameraStateMonitor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetVisibilityLogicTest {

    @Test fun `camera always hides regardless of toggle`() {
        assertTrue(WidgetController.shouldHideOverlay(
            cameraActive = true, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = null, hideInApps = emptySet()))
    }

    @Test fun `youtube hides only when toggle is on`() {
        assertTrue(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = true, hideOnYoutube = true,
            foregroundPkg = null, hideInApps = emptySet()))
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = true, hideOnYoutube = false,
            foregroundPkg = null, hideInApps = emptySet()))
    }

    @Test fun `nothing foreground shows widget`() {
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = true,
            foregroundPkg = null, hideInApps = emptySet()))
    }

    @Test fun `known youtube clients are recognized`() {
        assertTrue(CameraStateMonitor.isYoutubePackage("anddea.youtube"))
        assertTrue(CameraStateMonitor.isYoutubePackage("com.google.android.youtube"))
        assertTrue(CameraStateMonitor.isYoutubePackage("app.revanced.android.youtube"))
        assertFalse(CameraStateMonitor.isYoutubePackage("ru.yandex.yandexnavi"))
        assertFalse(CameraStateMonitor.isYoutubePackage(null))
    }

    @Test fun `foreground app on the hide list hides the widget`() {
        assertTrue(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.android.chrome", hideInApps = setOf("com.android.chrome")))
    }

    @Test fun `foreground app outside the hide list keeps the widget`() {
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "ru.yandex.yandexnavi", hideInApps = setOf("com.android.chrome")))
    }

    @Test fun `empty hide list never hides`() {
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.android.chrome", hideInApps = emptySet()))
    }

    @Test fun `youtube toggle off still wins over an unrelated hide list`() {
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = true, hideOnYoutube = false,
            foregroundPkg = "anddea.youtube", hideInApps = setOf("com.android.chrome")))
    }

    @Test fun `car settings foreground hides the widget with an empty hide list`() {
        assertTrue(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.byd.carsettings", hideInApps = emptySet()))
    }

    @Test fun `a different byd package does not hide the widget`() {
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.byd.music", hideInApps = emptySet()))
    }

    @Test fun `hideReason names which rule fired`() {
        assertEquals("camera", WidgetController.hideReason(
            cameraActive = true, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = null, hideInApps = emptySet()))
        assertEquals("car_settings", WidgetController.hideReason(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.byd.carsettings", hideInApps = emptySet()))
        assertEquals("youtube", WidgetController.hideReason(
            cameraActive = false, youtubeForeground = true, hideOnYoutube = true,
            foregroundPkg = null, hideInApps = emptySet()))
        assertEquals("app:com.android.chrome", WidgetController.hideReason(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.android.chrome", hideInApps = setOf("com.android.chrome")))
        assertNull(WidgetController.hideReason(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = null, hideInApps = emptySet()))
    }

    private val launcher = setOf("com.byd.launcher")

    private fun homeOnlyReason(
        foregroundPkg: String?,
        homeOnly: Boolean = true,
        homePackages: Set<String> = launcher,
        cameraActive: Boolean = false,
    ) = WidgetController.hideReason(
        cameraActive = cameraActive, youtubeForeground = false, hideOnYoutube = false,
        foregroundPkg = foregroundPkg, hideInApps = emptySet(),
        homeOnly = homeOnly, homePackages = homePackages)

    @Test fun `home-only off leaves another app visible`() {
        assertNull(homeOnlyReason("ru.yandex.yandexnavi", homeOnly = false))
    }

    @Test fun `home-only keeps the widget on the launcher`() {
        assertNull(homeOnlyReason("com.byd.launcher"))
    }

    @Test fun `home-only hides the widget in another app`() {
        assertEquals("not_home:ru.yandex.yandexnavi", homeOnlyReason("ru.yandex.yandexnavi"))
    }

    @Test fun `home-only keeps the widget when the foreground is unknown`() {
        assertNull(homeOnlyReason(null))
    }

    @Test fun `home-only keeps the widget when no launcher was resolved`() {
        assertNull(homeOnlyReason("ru.yandex.yandexnavi", homePackages = emptySet()))
    }

    @Test fun `camera still wins over home-only`() {
        assertEquals("camera", homeOnlyReason("com.byd.launcher", cameraActive = true))
    }

    @Test fun `hide list still names its own reason with home-only on`() {
        assertEquals("app:com.android.chrome", WidgetController.hideReason(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = false,
            foregroundPkg = "com.android.chrome", hideInApps = setOf("com.android.chrome"),
            homeOnly = true, homePackages = launcher))
    }
}
