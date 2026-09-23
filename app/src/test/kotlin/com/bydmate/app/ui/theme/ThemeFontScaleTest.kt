package com.bydmate.app.ui.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ThemeFontScaleTest {

    private val base = Density(density = 1.5f, fontScale = 1.1f)

    @Test
    fun `scale 1 returns the same density object`() {
        assertSame(base, scaledDensity(base, 1f))
    }

    @Test
    fun `scaling multiplies fontScale and keeps density`() {
        val scaled = scaledDensity(base, 1.3f)
        assertEquals(1.5f, scaled.density, 0f)
        assertEquals(1.1f * 1.3f, scaled.fontScale, 1e-6f)
    }

    @Test
    fun `scaling changes sp to px but not dp to px`() {
        val scaled = scaledDensity(base, 1.15f)
        assertEquals(with(base) { 10.dp.toPx() }, with(scaled) { 10.dp.toPx() }, 1e-4f)
        assertEquals(with(base) { 10.sp.toPx() } * 1.15f, with(scaled) { 10.sp.toPx() }, 1e-3f)
    }
}
