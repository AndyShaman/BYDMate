package com.bydmate.app.camera

import android.graphics.Matrix

/**
 * Picture transform of a blind-spot preview window.
 *
 * A TextureView with no transform stretches the whole camera buffer over the view, so everything
 * here is expressed in view coordinates: the matrix turns that stretched buffer back into a
 * centered crop of the source that fills the window without distortion, optionally standing the
 * picture on its side (#207).
 */
object BlindSpotCrop {
    /** AVM side-camera buffer (Leopard 3, on-car 2026-07-31). */
    const val SOURCE_WIDTH = 1920f
    const val SOURCE_HEIGHT = 1300f

    /**
     * Quarter-turn the picture of [side] gets in a portrait window, in degrees (positive =
     * clockwise). The upright window has to read like the door mirror it stands next to: what is
     * behind the car at the top of the window, the near end at the bottom, the car's own body
     * along the inner edge. Which axis of the buffer runs front-to-rear is not visible from the
     * code, so the sign of each side lives here alone and is CONFIRMED ON THE CAR by Andy (#207) —
     * if a side comes out upside down, flip that one sign and nothing else.
     * NONE never turns: the cluster window keeps its own geometry.
     */
    fun rotationDegrees(side: BlindSpotSide): Float = when (side) {
        BlindSpotSide.RIGHT -> 90f
        BlindSpotSide.LEFT -> -90f
        BlindSpotSide.NONE -> 0f
    }

    /**
     * Transform for a [width]×[height] window. With [rotated] off this is the plain centered crop;
     * with it on the frame is turned by [rotationDegrees] and cropped to the upright window.
     */
    fun cropMatrix(rotated: Boolean, side: BlindSpotSide, width: Int, height: Int): Matrix {
        val matrix = Matrix()
        if (width <= 0 || height <= 0) return matrix
        val angle = if (rotated) rotationDegrees(side) else 0f
        val quarterTurn = angle != 0f
        // Size of the picture after the turn: a quarter-turn swaps the buffer's sides.
        val turnedW = if (quarterTurn) SOURCE_HEIGHT else SOURCE_WIDTH
        val turnedH = if (quarterTurn) SOURCE_WIDTH else SOURCE_HEIGHT
        // Centered crop = scale by the larger of the two ratios; the excess falls outside the view.
        val scale = maxOf(width / turnedW, height / turnedH)
        // View coordinates -> buffer coordinates, turn around the buffer centre, then put that
        // centre in the middle of the window at the crop scale.
        matrix.postScale(SOURCE_WIDTH / width, SOURCE_HEIGHT / height)
        if (quarterTurn) matrix.postRotate(angle, SOURCE_WIDTH / 2f, SOURCE_HEIGHT / 2f)
        matrix.postTranslate(-SOURCE_WIDTH / 2f, -SOURCE_HEIGHT / 2f)
        matrix.postScale(scale, scale)
        matrix.postTranslate(width / 2f, height / 2f)
        return matrix
    }
}
