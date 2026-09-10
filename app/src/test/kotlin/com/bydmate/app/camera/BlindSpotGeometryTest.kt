package com.bydmate.app.camera

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Geometry of the two main-screen camera windows. The left one is the whole point (#183): the
 * driver who never touches it gets a mirror of the right window, and the driver who drags it gets
 * exactly the corner they dropped it at. Both the controller and the drag overlay read these
 * functions, so a rule pinned here holds for the preview and for the placement stand-in alike.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotGeometryTest {

    private val screenW = 1920
    private val screenH = 1200
    private val unset = BlindSpotPreferences.UNSET_PX

    private fun right(x: Int = unset, y: Int = unset, widthPct: Int = 36): Rect =
        BlindSpotPreferences.placedPipRect(screenW, screenH, widthPct, x, y)

    private fun left(x: Int, y: Int, widthPct: Int = 36, rightRect: Rect = right(widthPct = widthPct)): Rect =
        BlindSpotPreferences.leftPipRect(screenW, screenH, widthPct, x, y, rightRect)

    @Test
    fun `unplaced left window mirrors the right one across the screen`() {
        val rightRect = right(x = 1400, y = 200)
        val leftRect = left(unset, unset, rightRect = rightRect)
        assertEquals(screenW - rightRect.right, leftRect.left)
        assertEquals(rightRect.top, leftRect.top)
        assertEquals(rightRect.width(), leftRect.width())
        assertEquals(rightRect.height(), leftRect.height())
    }

    @Test
    fun `unplaced left window mirrors the default slot too`() {
        // Nothing placed at all: the right window sits at the right edge, so the left one lands
        // at the left edge at the same height.
        val rightRect = right()
        val leftRect = left(unset, unset, rightRect = rightRect)
        assertEquals(0, leftRect.left)
        assertEquals(rightRect.top, leftRect.top)
    }

    @Test
    fun `one saved coordinate is not a placement - the mirror stands`() {
        val rightRect = right(x = 1400, y = 200)
        assertEquals(left(unset, unset, rightRect = rightRect), left(300, unset, rightRect = rightRect))
        assertEquals(left(unset, unset, rightRect = rightRect), left(unset, 300, rightRect = rightRect))
    }

    @Test
    fun `a placed left window keeps its own corner regardless of the right one`() {
        val leftRect = left(120, 640, rightRect = right(x = 1400, y = 200))
        assertEquals(120, leftRect.left)
        assertEquals(640, leftRect.top)
    }

    @Test
    fun `a placed left window is clamped to the screen`() {
        val size = BlindSpotPreferences.pipSize(screenW, 36)
        val leftRect = left(screenW * 2, screenH * 2)
        assertEquals(screenW - size.width, leftRect.left)
        assertEquals(screenH - size.height, leftRect.top)
    }

    @Test
    fun `the width slider sizes both windows`() {
        // One width for the pair: a wider slider must grow the placed left window as well.
        val narrowRight = right(x = 1400, y = 200, widthPct = 20)
        val wideRight = right(x = 1400, y = 200, widthPct = 50)
        val narrowLeft = left(120, 640, widthPct = 20)
        val wideLeft = left(120, 640, widthPct = 50)
        assertEquals(BlindSpotPreferences.pipSize(screenW, 20).width, narrowLeft.width())
        assertEquals(BlindSpotPreferences.pipSize(screenW, 50).width, wideLeft.width())
        assertEquals(narrowRight.width(), narrowLeft.width())
        assertEquals(wideRight.width(), wideLeft.width())
    }
}
