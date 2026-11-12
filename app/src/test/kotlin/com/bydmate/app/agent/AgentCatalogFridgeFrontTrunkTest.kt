package com.bydmate.app.agent

import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentCatalogFridgeFrontTrunkTest {

    @Test fun fridge_cool_rejects_out_of_range() {
        assertNull(AgentCommandCatalog.resolve("fridge_cool", 7))
        assertNull(AgentCommandCatalog.resolve("fridge_cool", null))
    }

    // Translator: dynamic fridge setpoints must fan out to mode + temp with the
    // documented raw shift (cool raw = C + 19) and clamp to the validated window.
    @Test fun translator_resolves_dynamic_fridge_cool() {
        val r = CommandTranslator.resolve("冰箱制冷-4度")
        assertEquals(
            listOf("fridge_mode" to 1, "fridge_temp_cool" to 15),
            r.map { it.actionName to it.value },
        )
    }

    @Test fun translator_clamps_fridge_heat() {
        val r = CommandTranslator.resolve("冰箱制热99度")
        assertEquals(
            listOf("fridge_mode" to 2, "fridge_temp_heat" to 50),
            r.map { it.actionName to it.value },
        )
    }
}
