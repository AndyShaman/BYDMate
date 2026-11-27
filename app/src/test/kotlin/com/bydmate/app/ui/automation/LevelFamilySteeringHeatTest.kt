package com.bydmate.app.ui.automation

import com.bydmate.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #408 Case 11: the automation catalog exposes a steering wheel heat level family
 * in the climate category supporting 1..3 (and 1..5 when config says five).
 */
class LevelFamilySteeringHeatTest {

    @Test fun `automation catalog has steering heat level family in climate category`() {
        val family = LevelFamily.entries.firstOrNull {
            it.categoryRes == R.string.auto_cat_climate &&
                it != LevelFamily.TEMPERATURE &&
                it != LevelFamily.FAN
        }
        assertNotNull("Expected a steering heat level family in auto_cat_climate", family)
        assertEquals(1, family!!.step)
        assertTrue("1 must be in range ${family.range}", 1 in family.range)
        assertTrue("3 must be in range ${family.range}", 3 in family.range)
        assertTrue("default ${family.default} must be in 1..3", family.default in 1..3)

        for (level in 1..3) {
            val cmd = family.command(level)
            assertEquals(LevelValue(family, level), LevelFamily.of(cmd))
        }
    }
}
