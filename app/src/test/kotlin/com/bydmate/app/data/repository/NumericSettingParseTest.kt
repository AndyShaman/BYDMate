package com.bydmate.app.data.repository

import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * issue #19 — Russian numeric keyboards emit "71,8" with a comma.
 * Bare toDoubleOrNull() returned null on it, sending Settings.getBatteryCapacity()
 * to its 72.9 fallback. ABRP, Charges and SoH then ignored the user's input.
 */
class NumericSettingParseTest {
    @Test fun comma_decimal_parses() = assertEquals(71.8, "71,8".parseNumericSetting()!!, 0.0001)
    @Test fun dot_decimal_parses() = assertEquals(71.8, "71.8".parseNumericSetting()!!, 0.0001)
    @Test fun whitespace_trimmed() = assertEquals(71.8, " 71,8 ".parseNumericSetting()!!, 0.0001)
    @Test fun integer_parses() = assertEquals(72.0, "72".parseNumericSetting()!!, 0.0001)
    @Test fun blank_returns_null() = assertNull("".parseNumericSetting())
    @Test fun garbage_returns_null() = assertNull("abc".parseNumericSetting())
    @Test fun mixed_garbage_returns_null() = assertNull("71,8x".parseNumericSetting())
}
