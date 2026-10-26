package com.bydmate.app.agent

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.vehicle.CommandTranslator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCatalogSeatSunroofTest {

    @Test fun seat_levels_4_and_5_resolve() {
        assertEquals("主驾座椅加热4档", AgentCommandCatalog.resolve("seat_heat_driver_4", null))
        assertEquals("主驾座椅加热5档", AgentCommandCatalog.resolve("seat_heat_driver_5", null))
        assertEquals("副驾座椅加热4档", AgentCommandCatalog.resolve("seat_heat_passenger_4", null))
        assertEquals("主驾座椅通风5档", AgentCommandCatalog.resolve("seat_vent_driver_5", null))
        assertEquals("副驾座椅通风4档", AgentCommandCatalog.resolve("seat_vent_passenger_4", null))
    }

    @Test fun seat_level_5_is_dispatchable() {
        assertNotNull(CommandTranslator.resolveSeat("主驾座椅加热5档"))
        assertNotNull(CommandTranslator.resolveSeat("副驾座椅通风4档"))
    }

    @Test fun sunroof_extra_commands_resolve() {
        assertEquals("天窗停止", AgentCommandCatalog.resolve("sunroof_stop", null))
        assertEquals("天窗通风", AgentCommandCatalog.resolve("sunroof_updip", null))
        assertEquals("天窗舒适打开", AgentCommandCatalog.resolve("sunroof_comfort", null))
    }

    @Test fun sunroof_extra_translator_values() {
        assertEquals(listOf("sunroof_stop" to 4),
            CommandTranslator.resolve("天窗停止").map { it.actionName to it.value })
        assertEquals(listOf("sunroof_updip" to 5),
            CommandTranslator.resolve("天窗通风").map { it.actionName to it.value })
        assertEquals(listOf("sunroof_comfort" to 6),
            CommandTranslator.resolve("天窗舒适打开").map { it.actionName to it.value })
    }

    // Safety invariant: aperture-opening sunroof commands are speed-gated by
    // isSunroofOpenCommand (wave-O split); stop must stay usable at any speed.
    @Test fun sunroof_updip_and_comfort_are_speed_gated_stop_is_not() {
        assertTrue(ActionDispatcher.isSunroofOpenCommand("天窗通风"))
        assertTrue(ActionDispatcher.isSunroofOpenCommand("天窗舒适打开"))
        assertFalse(ActionDispatcher.isSunroofOpenCommand("天窗停止"))
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
