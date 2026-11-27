package com.bydmate.app.ui.tech

import com.bydmate.app.data.repository.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class TyrePressureUnitTest {

    @Test
    fun `pressure unit setting key is pressure_unit`() {
        assertEquals("pressure_unit", SettingsRepository.KEY_PRESSURE_UNIT)
    }

    @Test
    fun `fromSetting defaults to BAR for null, bar, and unknown values and parses psi`() {
        assertEquals(TyrePressureUnit.Unit.BAR, TyrePressureUnit.fromSetting(null))
        assertEquals(TyrePressureUnit.Unit.BAR, TyrePressureUnit.fromSetting("bar"))
        assertEquals(TyrePressureUnit.Unit.PSI, TyrePressureUnit.fromSetting("psi"))
        assertEquals(TyrePressureUnit.Unit.BAR, TyrePressureUnit.fromSetting("kpa"))
    }

    @Test
    fun `convert keeps bar unchanged and converts bar to psi`() {
        assertEquals(2.5, TyrePressureUnit.convert(2.5, TyrePressureUnit.Unit.BAR), 1e-9)
        assertEquals(36.26, TyrePressureUnit.convert(2.5, TyrePressureUnit.Unit.PSI), 0.01)
        assertEquals(0.0, TyrePressureUnit.convert(0.0, TyrePressureUnit.Unit.PSI), 1e-9)
    }
}
