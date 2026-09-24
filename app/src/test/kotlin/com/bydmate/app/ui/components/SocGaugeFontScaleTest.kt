package com.bydmate.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The smallest SocGauge on Главная must keep «100» and «SOC %» (and the bolt while charging)
 * inside the arc. Rows here model the real text at 1 px per dp: a 22 sp theme line height,
 * monospace digits 0.6 em wide with ink 0.73 em above the baseline, baseline 0.35 em below the
 * row centre (the 32 sp glyphs overflow their 22 sp row).
 */
class SocGaugeFontScaleTest {

    private val stroke = 14f

    private fun textRow(scale: Float, fontSp: Float, chars: Int, advanceEm: Float): GaugeContentRow {
        val em = fontSp * scale
        val height = 22f * scale
        val baseline = height / 2f + 0.35f * em
        return GaugeContentRow(height, baseline - 0.73f * em, baseline + 0.02f * em, chars * advanceEm * em)
    }

    private fun minSize(scale: Float, charging: Boolean): Float {
        val rows = buildList {
            if (charging) add(GaugeContentRow(18f, 0f, 18f, 18f))
            add(textRow(scale, 32f, chars = 3, advanceEm = 0.6f))  // «100»
            add(textRow(scale, 12f, chars = 5, advanceEm = 0.55f)) // «SOC %»
        }
        return socGaugeMinSizePx(rows, stroke)
    }

    @Test fun `a single centred row needs its corner inside the arc`() {
        val row = GaugeContentRow(height = 40f, inkTop = 0f, inkBottom = 40f, width = 60f)
        assertEquals(2f * (kotlin.math.hypot(30f, 20f) + stroke), socGaugeMinSizePx(listOf(row), stroke), 1e-3f)
    }

    @Test fun `ink past the row box counts, not the box`() {
        val tight = GaugeContentRow(height = 20f, inkTop = 0f, inkBottom = 20f, width = 0f)
        val overflowing = GaugeContentRow(height = 20f, inkTop = -8f, inkBottom = 20f, width = 0f)
        assertEquals(2f * (10f + stroke), socGaugeMinSizePx(listOf(tight), stroke), 1e-3f)
        assertEquals(2f * (18f + stroke), socGaugeMinSizePx(listOf(overflowing), stroke), 1e-3f)
    }

    @Test fun `at scale 1 the 150 dp gauge still fits, charging or not`() {
        assertTrue(minSize(1f, charging = false) <= 150f)
        assertTrue(minSize(1f, charging = true) <= 150f)
    }

    @Test fun `at scale 1_3 a 100 dp gauge is too small for 100, charging or not`() {
        assertTrue(minSize(1.3f, charging = false) > 100f)
        assertTrue(minSize(1.3f, charging = true) > 100f)
    }

    @Test fun `the minimum grows with the text scale`() {
        assertTrue(minSize(1.3f, charging = false) > minSize(1.15f, charging = false))
        assertTrue(minSize(1.15f, charging = false) > minSize(1f, charging = false))
        assertTrue(minSize(1.3f, charging = true) > minSize(1f, charging = true))
    }
}
