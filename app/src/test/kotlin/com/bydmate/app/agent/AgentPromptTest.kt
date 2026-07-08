package com.bydmate.app.agent

import org.junit.Assert.assertTrue
import org.junit.Test

class AgentPromptTest {

    @Test fun prompt_mentions_places_tools() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("list_places"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("create_place"))
    }

    @Test fun prompt_pins_unknown_semantics() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("НЕИЗВЕСТЕН"))
    }

    @Test fun prompt_has_compound_few_shot() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("seat_heat_driver_3"))
    }

    @Test fun prompt_explains_exact_time_trigger() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("time_range"))
    }
}
