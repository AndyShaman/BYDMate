package com.bydmate.app.ui.widget

import com.bydmate.app.data.camera.CameraStateMonitor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetVisibilityLogicTest {

    @Test fun `camera always hides regardless of toggle`() {
        assertTrue(WidgetController.shouldHideOverlay(
            cameraActive = true, youtubeForeground = false, hideOnYoutube = false))
    }

    @Test fun `youtube hides only when toggle is on`() {
        assertTrue(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = true, hideOnYoutube = true))
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = true, hideOnYoutube = false))
    }

    @Test fun `nothing foreground shows widget`() {
        assertFalse(WidgetController.shouldHideOverlay(
            cameraActive = false, youtubeForeground = false, hideOnYoutube = true))
    }

    @Test fun `known youtube clients are recognized`() {
        assertTrue(CameraStateMonitor.isYoutubePackage("anddea.youtube"))
        assertTrue(CameraStateMonitor.isYoutubePackage("com.google.android.youtube"))
        assertTrue(CameraStateMonitor.isYoutubePackage("app.revanced.android.youtube"))
        assertFalse(CameraStateMonitor.isYoutubePackage("ru.yandex.yandexnavi"))
        assertFalse(CameraStateMonitor.isYoutubePackage(null))
    }
}
