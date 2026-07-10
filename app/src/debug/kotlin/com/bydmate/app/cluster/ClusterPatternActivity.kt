package com.bydmate.app.cluster

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.os.SystemClock
import android.view.View

/** Debug-only visual probe that can be launched directly on the cluster display. */
class ClusterPatternActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(ClusterPatternView())
    }

    private inner class ClusterPatternView : View(this) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val startedAt = SystemClock.elapsedRealtime()

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawColor(Color.BLACK)

            val colors = intArrayOf(
                Color.RED,
                Color.GREEN,
                Color.BLUE,
                Color.CYAN,
                Color.MAGENTA,
                Color.YELLOW,
                Color.WHITE,
            )
            val bandWidth = w / colors.size
            colors.forEachIndexed { index, color ->
                paint.color = color
                canvas.drawRect(index * bandWidth, 0f, (index + 1) * bandWidth, h * 0.22f, paint)
            }

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = Color.rgb(70, 70, 70)
            for (x in 0..10) canvas.drawLine(w * x / 10f, 0f, w * x / 10f, h, paint)
            for (y in 0..5) canvas.drawLine(0f, h * y / 5f, w, h * y / 5f, paint)

            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.textSize = (h * 0.075f).coerceIn(28f, 72f)
            val density = resources.displayMetrics.densityDpi
            canvas.drawText("BYDMate cluster probe", w * 0.04f, h * 0.42f, paint)
            canvas.drawText("display=${display?.displayId}  ${width}x${height}  dpi=$density", w * 0.04f, h * 0.54f, paint)

            val elapsed = (SystemClock.elapsedRealtime() - startedAt) / 1000f
            val markerX = w * (0.08f + 0.84f * ((elapsed % 4f) / 4f))
            paint.color = Color.WHITE
            canvas.drawCircle(markerX, h * 0.75f, h * 0.055f, paint)
            paint.color = Color.BLACK
            canvas.drawCircle(markerX, h * 0.75f, h * 0.025f, paint)

            paint.color = Color.LTGRAY
            paint.textSize = (h * 0.045f).coerceIn(20f, 48f)
            canvas.drawText("Moving marker = live rendering", w * 0.04f, h * 0.92f, paint)
            postInvalidateOnAnimation()
        }
    }
}
