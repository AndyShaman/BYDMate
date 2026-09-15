package com.bydmate.app.camera

import android.graphics.Matrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Picture transform of a preview window. The matrix is what the driver actually sees, and the
 * portrait mode (#207) turns it — so the four corners of the camera buffer are pinned here: that
 * the turn follows the side's own sign from [BlindSpotCrop.rotationDegrees] (which side is the
 * mirror-correct one is settled on the car, not here), that the picture still fills the window,
 * and that it is scaled by the same factor both ways — a quarter-turn done wrong squashes it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class BlindSpotCropTest {

    private val srcW = BlindSpotCrop.SOURCE_WIDTH
    private val srcH = BlindSpotCrop.SOURCE_HEIGHT

    /** Where the four corners of the untransformed buffer land in the window. */
    private data class Corners(
        val topLeft: Pair<Float, Float>,
        val topRight: Pair<Float, Float>,
        val bottomRight: Pair<Float, Float>,
        val bottomLeft: Pair<Float, Float>,
    ) {
        fun all(): List<Pair<Float, Float>> = listOf(topLeft, topRight, bottomRight, bottomLeft)
    }

    private fun corners(matrix: Matrix, width: Int, height: Int): Corners {
        val points = floatArrayOf(
            0f, 0f,
            width.toFloat(), 0f,
            width.toFloat(), height.toFloat(),
            0f, height.toFloat(),
        )
        matrix.mapPoints(points)
        val mapped = (0 until 4).map { points[it * 2] to points[it * 2 + 1] }
        return Corners(mapped[0], mapped[1], mapped[2], mapped[3])
    }

    private fun assertPoint(expectedX: Float, expectedY: Float, actual: Pair<Float, Float>) {
        assertEquals(expectedX, actual.first, TOLERANCE)
        assertEquals(expectedY, actual.second, TOLERANCE)
    }

    @Test
    fun `without rotation the buffer keeps its orientation and fills the window`() {
        // Cluster window: much wider than the buffer, so the crop takes the top and bottom off.
        val width = 1280
        val height = 480
        val mapped = corners(
            BlindSpotCrop.cropMatrix(false, BlindSpotSide.LEFT, width, height), width, height)
        val topLeft = mapped.topLeft
        val topRight = mapped.topRight
        val bottomRight = mapped.bottomRight
        val bottomLeft = mapped.bottomLeft
        assertPoint(0f, topLeft.second, topLeft)
        assertPoint(width.toFloat(), topRight.second, topRight)
        assertTrue(topLeft.second < 0f)
        assertEquals(-topLeft.second, bottomLeft.second - height, TOLERANCE)
        assertEquals(topLeft.second, topRight.second, TOLERANCE)
        assertEquals(bottomLeft.second, bottomRight.second, TOLERANCE)
    }

    /**
     * The buffer really is turned a quarter-turn the way [BlindSpotCrop.rotationDegrees] says.
     * The window here has exactly the aspect of the turned buffer, so nothing is cropped away and
     * the four buffer corners land exactly in the four window corners.
     */
    private fun assertQuarterTurn(side: BlindSpotSide) {
        val width = (srcH / 2).toInt()
        val height = (srcW / 2).toInt()
        val mapped = corners(BlindSpotCrop.cropMatrix(true, side, width, height), width, height)
        val w = width.toFloat()
        val h = height.toFloat()
        if (BlindSpotCrop.rotationDegrees(side) > 0f) {
            assertPoint(w, 0f, mapped.topLeft)
            assertPoint(w, h, mapped.topRight)
            assertPoint(0f, h, mapped.bottomRight)
            assertPoint(0f, 0f, mapped.bottomLeft)
        } else {
            assertPoint(0f, h, mapped.topLeft)
            assertPoint(0f, 0f, mapped.topRight)
            assertPoint(w, 0f, mapped.bottomRight)
            assertPoint(w, h, mapped.bottomLeft)
        }
    }

    @Test
    fun `each side turns by its own sign`() {
        assertQuarterTurn(BlindSpotSide.RIGHT)
        assertQuarterTurn(BlindSpotSide.LEFT)
    }

    @Test
    fun `the two sides turn opposite ways`() {
        // Both windows have to read like the mirror on their own door, so one turn cannot serve
        // both: a quarter-turn each way, and the cluster window is left alone.
        val right = BlindSpotCrop.rotationDegrees(BlindSpotSide.RIGHT)
        val left = BlindSpotCrop.rotationDegrees(BlindSpotSide.LEFT)
        assertEquals(90f, abs(right), TOLERANCE)
        assertEquals(90f, abs(left), TOLERANCE)
        assertEquals(-right, left, TOLERANCE)
        assertEquals(0f, BlindSpotCrop.rotationDegrees(BlindSpotSide.NONE), TOLERANCE)
    }

    @Test
    fun `a portrait PiP is filled without distortion`() {
        // The real window: 36 % of a 1920-wide screen, standing on its short side.
        val size = BlindSpotPreferences.pipSize(1920, 1200, PipShape(36, true))
        val width = size.width
        val height = size.height
        val mapped = corners(
            BlindSpotCrop.cropMatrix(true, BlindSpotSide.RIGHT, width, height), width, height)
        val topLeft = mapped.topLeft
        val topRight = mapped.topRight
        val bottomLeft = mapped.bottomLeft
        val xs = mapped.all().map { it.first }
        val ys = mapped.all().map { it.second }
        // Covers the window: the turned buffer is wider than this window, so it spills sideways
        // by the same amount on both sides and fits the height exactly.
        assertTrue(xs.min() < 0f)
        assertTrue(xs.max() > width)
        assertEquals(-xs.min(), xs.max() - width, TOLERANCE)
        assertEquals(0f, ys.min(), TOLERANCE)
        assertEquals(height.toFloat(), ys.max(), TOLERANCE)
        // Same scale both ways: the turned buffer keeps its 1920:1300 proportion.
        val longEdge = hypot(topRight.first - topLeft.first, topRight.second - topLeft.second)
        val shortEdge = hypot(bottomLeft.first - topLeft.first, bottomLeft.second - topLeft.second)
        assertEquals(srcW / srcH, longEdge / shortEdge, 0.01f)
    }

    @Test
    fun `a window without a side is never turned`() {
        val width = 400
        val height = 700
        val turned = corners(
            BlindSpotCrop.cropMatrix(true, BlindSpotSide.NONE, width, height), width, height).all()
        val plain = corners(
            BlindSpotCrop.cropMatrix(false, BlindSpotSide.NONE, width, height), width, height).all()
        turned.forEachIndexed { index, point ->
            assertTrue(abs(point.first - plain[index].first) < TOLERANCE)
            assertTrue(abs(point.second - plain[index].second) < TOLERANCE)
        }
    }

    private companion object {
        const val TOLERANCE = 0.5f
    }
}
