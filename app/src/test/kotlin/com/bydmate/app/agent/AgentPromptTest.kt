package com.bydmate.app.agent

import org.junit.Assert.assertFalse
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

    // Terse-while-driving is a static rule about a tag on the user turn: keeping it out of the
    // system prompt is what makes the cached prefix identical between turns.
    @Test fun prompt_has_static_moving_rule() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains(AgentOrchestrator.MOVING_TAG))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("одним подтверждением"))
    }

    // The app only sees the electric side of the car (some models in the fleet are hybrids),
    // so the prompt must scope the data instead of declaring the powertrain.
    @Test fun prompt_scopes_data_to_electric_side() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("данных о топливе"))
        assertFalse(AgentOrchestrator.SYSTEM_PROMPT.contains("Никогда не называй машину гибридом"))
    }

    @Test fun prompt_explains_pending_confirmation_status() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("ожидает подтверждения на экране"))
    }

    @Test fun prompt_refuses_steering_key_trigger_by_voice() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("клавиша руля"))
    }

    @Test fun prompt_mentions_driver_memory_tools() {
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("remember_fact"))
        assertTrue(AgentOrchestrator.SYSTEM_PROMPT.contains("forget_fact"))
    }
}
