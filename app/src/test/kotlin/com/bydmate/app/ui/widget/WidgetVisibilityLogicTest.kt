package com.bydmate.app.ui.widget

import com.bydmate.app.data.camera.CameraStateMonitor
import org.junit.Assert.assertFalse
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
}
