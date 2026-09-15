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

    private fun right(
        x: Int = unset,
        y: Int = unset,
        widthPct: Int = 36,
        rotated: Boolean = false,
    ): Rect = BlindSpotPreferences.placedPipRect(screenW, screenH, PipShape(widthPct, rotated), x, y)

    private fun left(
        x: Int,
        y: Int,
        widthPct: Int = 36,
        rightRect: Rect = right(widthPct = widthPct),
        rotated: Boolean = false,
    ): Rect = BlindSpotPreferences.leftPipRect(
        screenW, screenH, PipShape(widthPct, rotated), x, y, rightRect)

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
        val size = BlindSpotPreferences.pipSize(screenW, screenH, PipShape(36, false))
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
        assertEquals(BlindSpotPreferences.pipSize(screenW, screenH, PipShape(20, false)).width, narrowLeft.width())
        assertEquals(BlindSpotPreferences.pipSize(screenW, screenH, PipShape(50, false)).width, wideLeft.width())
        assertEquals(narrowRight.width(), narrowLeft.width())
        assertEquals(wideRight.width(), wideLeft.width())
    }

    @Test
    fun `the portrait window is the same window on its short side`() {
        // #207: the width slider still means the same window, it just stands upright.
        val flat = BlindSpotPreferences.pipSize(screenW, screenH, PipShape(36, false))
        val upright = BlindSpotPreferences.pipSize(screenW, screenH, PipShape(36, true))
        assertEquals(flat.height, upright.width)
        assertEquals(flat.width, upright.height)
    }

    @Test
    fun `a portrait window taller than the screen is scaled down to fit`() {
        // A short screen and a wide slider: upright, the window would be 1152 px tall on a
        // 720 px screen, so it shrinks whole and still fits 16:9.
        val size = BlindSpotPreferences.pipSize(screenW, 720, PipShape(60, true))
        assertEquals(720, size.height)
        assertEquals(405, size.width)
        val rect = BlindSpotPreferences.defaultPipRect(screenW, 720, PipShape(60, true))
        assertEquals(720, rect.height())
        assertEquals(405, rect.width())
        assertEquals(0, rect.top)
    }

    @Test
    fun `the portrait default slot stays at the right edge, vertically centered`() {
        val rect = BlindSpotPreferences.defaultPipRect(screenW, screenH, PipShape(36, true))
        val size = BlindSpotPreferences.pipSize(screenW, screenH, PipShape(36, true))
        assertEquals(screenW - size.width, rect.left)
        assertEquals((screenH - size.height) / 2, rect.top)
        assertEquals(size.width, rect.width())
        assertEquals(size.height, rect.height())
    }

    @Test
    fun `a placed portrait window is clamped by its own upright size`() {
        val size = BlindSpotPreferences.pipSize(screenW, screenH, PipShape(36, true))
        val rect = right(x = screenW * 2, y = screenH * 2, rotated = true)
        assertEquals(screenW - size.width, rect.left)
        assertEquals(screenH - size.height, rect.top)
    }

    @Test
    fun `the portrait left window mirrors the portrait right one`() {
        val rightRect = right(x = 1700, y = 200, rotated = true)
        val leftRect = left(unset, unset, rightRect = rightRect, rotated = true)
        assertEquals(screenW - rightRect.right, leftRect.left)
        assertEquals(rightRect.width(), leftRect.width())
        assertEquals(rightRect.height(), leftRect.height())
    }
}
