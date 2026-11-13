package com.bydmate.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BlindSpotFrameLumaTest {

    private fun luma(vararg pixels: Int): Int? = blindSpotFrameLuma(pixels)

    @Test fun `frame luma is the mean brightness of the pixels, null for an empty frame`() {
        assertEquals(0.0, luma(*IntArray(16) { 0xFF000000.toInt() })!!.toDouble(), 1.0)
        assertEquals(255.0, luma(*IntArray(16) { 0xFFFFFFFF.toInt() })!!.toDouble(), 1.0)
        assertEquals(128.0, luma(*IntArray(16) { 0xFF808080.toInt() })!!.toDouble(), 1.0)
        val half = IntArray(16) { if (it < 8) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        assertNotNull(blindSpotFrameLuma(half))
        assertEquals(127.5, blindSpotFrameLuma(half)!!.toDouble(), 1.0)
        assertNull(blindSpotFrameLuma(IntArray(0)))
    }

    @Test fun `brightness is measured only for cameras other than the working panoramic ones`() {
        assertFalse(blindSpotLumaChecked("pano_h"))
        assertFalse(blindSpotLumaChecked("pano_l"))
        assertFalse(blindSpotLumaChecked(null))
        assertTrue(blindSpotLumaChecked("apa"))
        assertTrue(blindSpotLumaChecked("byd_apa"))
    }
}
