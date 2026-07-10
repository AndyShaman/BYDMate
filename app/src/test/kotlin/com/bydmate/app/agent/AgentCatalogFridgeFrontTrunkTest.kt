package com.bydmate.app.agent

import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCatalogFridgeFrontTrunkTest {

    @Test fun fridge_cool_resolves_dynamic_temp() {
        assertEquals("冰箱制冷-4度", AgentCommandCatalog.resolve("fridge_cool", -4))
        assertEquals("冰箱制冷6度", AgentCommandCatalog.resolve("fridge_cool", 6))
    }

    @Test fun fridge_cool_rejects_out_of_range() {
        assertNull(AgentCommandCatalog.resolve("fridge_cool", 7))
        assertNull(AgentCommandCatalog.resolve("fridge_cool", null))
    }

    @Test fun fridge_heat_and_off_resolve() {
        assertEquals("冰箱制热40度", AgentCommandCatalog.resolve("fridge_heat", 40))
        assertEquals("冰箱关闭", AgentCommandCatalog.resolve("fridge_off", null))
    }

    @Test fun front_trunk_ids_resolve() {
        assertEquals("前备箱打开", AgentCommandCatalog.resolve("front_trunk_open", null))
        assertEquals("前备箱关闭", AgentCommandCatalog.resolve("front_trunk_close", null))
    }

    @Test fun trunk_descriptions_say_rear() {
        assertTrue(AgentCommandCatalog.ALL.first { it.id == "trunk_open" }.ru.contains("задний"))
        assertTrue(AgentCommandCatalog.ALL.first { it.id == "trunk_close" }.ru.contains("задний"))
    }

    @Test fun no_frunk_word_anywhere() {
        AgentCommandCatalog.ALL.forEach {
            assertFalse("id=${it.id}", it.ru.contains("фрунк", ignoreCase = true))
        }
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
