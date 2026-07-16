package com.bydmate.app.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class HudSpeedSignTest {

    @Test fun `renders png for positive limit`() {
        val png = HudSpeedSign.render(60)!!
        assertEquals(0x89.toByte(), png[0])   // PNG magic
        assertEquals(0x50.toByte(), png[1])
        // Robolectric's ShadowBitmap.compress emits UNCOMPRESSED png data:
        // 96x96 ARGB = ~37 KB here; a real device deflates the same sign to ~5 KB.
        assertTrue(png.size in 100..40_000)
    }

    @Test fun `zero and negative limits render nothing`() {
        assertNull(HudSpeedSign.render(0))
        assertNull(HudSpeedSign.render(-5))
    }

    @Test fun `same limit returns cached instance`() {
        val a = HudSpeedSign.render(90)
        val b = HudSpeedSign.render(90)
        assertSame(a, b)
    }
}
