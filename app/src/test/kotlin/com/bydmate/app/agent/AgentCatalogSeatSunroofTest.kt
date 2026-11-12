package com.bydmate.app.agent

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AgentCatalogSeatSunroofTest {

    @Test fun sunroof_extra_translator_values() {
        assertEquals(listOf("sunroof_stop" to 4),
            CommandTranslator.resolve("天窗停止").map { it.actionName to it.value })
        assertEquals(listOf("sunroof_updip" to 5),
            CommandTranslator.resolve("天窗通风").map { it.actionName to it.value })
        assertEquals(listOf("sunroof_comfort" to 6),
            CommandTranslator.resolve("天窗舒适打开").map { it.actionName to it.value })
    }

    // #362: the sunshade pair mirrors the sunroof half/stop.
    @Test fun sunshade_half_and_stop_resolve() {
        assertEquals("遮阳帘打开50", AgentCommandCatalog.resolve("sunshade_half", null))
        assertEquals("遮阳帘停止", AgentCommandCatalog.resolve("sunshade_stop", null))
    }

    @Test fun sunshade_half_and_stop_translator_values() {
        assertEquals(listOf("sunshade_half" to 3),
            CommandTranslator.resolve("遮阳帘打开50").map { it.actionName to it.value })
        assertEquals(listOf("sunshade_stop" to 4),
            CommandTranslator.resolve("遮阳帘停止").map { it.actionName to it.value })
    }

    @Test fun sunshade_half_and_stop_are_not_speed_gated() {
        assertFalse(ActionDispatcher.isSunroofOpenCommand("遮阳帘打开50"))
        assertFalse(ActionDispatcher.isSunroofOpenCommand("遮阳帘停止"))
    }
}
