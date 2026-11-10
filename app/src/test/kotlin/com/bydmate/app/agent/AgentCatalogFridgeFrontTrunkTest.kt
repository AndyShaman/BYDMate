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

    @Test fun translator_clamps_fridge_heat() {
        val r = CommandTranslator.resolve("冰箱制热99度")
        assertEquals(
            listOf("fridge_mode" to 2, "fridge_temp_heat" to 50),
            r.map { it.actionName to it.value },
        )
    }
}
