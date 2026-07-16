package com.bydmate.app.hud

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import java.io.ByteArrayOutputStream

/** Speed-limit sign PNG for HUD slot f7 (port of donor buildSpeedLimitPng):
 *  96x96, red ring, white core, black bold number. Cached per limit value -
 *  the push loop asks every 300 ms while the limit changes about once a minute. */
object HudSpeedSign {

    @Volatile private var cachedLimit = -1
    @Volatile private var cachedPng: ByteArray? = null

    fun render(limit: Int): ByteArray? {
        if (limit <= 0) return null
        if (limit == cachedLimit) return cachedPng
        val png = runCatching { draw(limit) }.getOrNull()
        cachedLimit = limit
        cachedPng = png
        return png
    }

    private fun draw(limit: Int): ByteArray {
        val size = 96
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        val cx = size / 2f
        val cy = size / 2f
        val red = Paint().apply { color = Color.rgb(0xD0, 0, 0); style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, size * 0.48f, red)
        val white = Paint().apply { color = Color.WHITE; style = Paint.Style.FILL }
        canvas.drawCircle(cx, cy, size * 0.34f, white)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            textSize = if (limit >= 100) size * 0.36f else size * 0.44f
        }
        val fm = text.fontMetrics
        canvas.drawText(limit.toString(), cx, cy - (fm.ascent + fm.descent) / 2f, text)
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }
}
