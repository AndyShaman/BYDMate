package com.bydmate.app.data.repository

import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.FakeSettingsDao
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// «Разъём для зарядки» in Settings -> «Авто и батарея»: GB/T by default (the Leopard 3 charges
// through GB/T), the choice survives a restart.
class ChargeConnectorSettingTest {

    private val dao = FakeSettingsDao()
    private val repo = SettingsRepository(dao, mockk<LocalePreferences>(relaxed = true))

    @Test fun default_is_gbt() = runTest {
        assertEquals(ChargeConnector.GBT, repo.getChargeConnector())
    }

    @Test fun unknown_stored_value_falls_back_to_gbt() = runTest {
        dao.map[SettingsRepository.KEY_CHARGE_CONNECTOR] = "tesla"
        assertEquals(ChargeConnector.GBT, repo.getChargeConnector())
    }

    @Test fun gbt_matches_both_gbt_dc_and_gbt_ac() {
        assertTrue(ChargeConnector.GBT.matches("GBT_DC"))
        assertTrue(ChargeConnector.GBT.matches("GBT_AC"))
        assertFalse(ChargeConnector.GBT.matches("CCS2"))
        assertTrue(ChargeConnector.CCS2.matches("CCS2"))
        assertFalse(ChargeConnector.TYPE2.matches("GBT_AC"))
    }

    // The tool argument: the model may say "GB/T", "ccs2" or "chademo" in any case.
    @Test fun tool_argument_parses_labels_and_keys() {
        assertEquals(ChargeConnector.GBT, ChargeConnector.parse("GB/T"))
        assertEquals(ChargeConnector.CCS2, ChargeConnector.parse("ccs2"))
        assertEquals(ChargeConnector.CHADEMO, ChargeConnector.parse(" CHAdeMO "))
        assertEquals(ChargeConnector.TYPE2, ChargeConnector.parse("type2"))
        assertNull(ChargeConnector.parse(""))
        assertNull(ChargeConnector.parse("NACS"))
    }
}
