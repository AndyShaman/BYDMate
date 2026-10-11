package com.bydmate.app.agent

import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The fuel sentence of the system prompt depends on the car type; the EV prompt is frozen. */
class AgentPromptHybridTest {

    private class FakeBackend : AgentBackend {
        val requests = mutableListOf<List<AgentMessage>>()
        override suspend fun isConfigured() = true
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> {
            requests += messages
            return Result.success(AgentReply("ок", emptyList()))
        }
    }

    private suspend fun staticPrompt(kind: VehicleKind?): String {
        val tools = mockk<AgentTools>()
        val repo = mockk<SettingsRepository>()
        coEvery { repo.isAgentEnabled() } returns true
        coEvery { tools.schemas() } returns JSONArray()
        val monitor = kind?.let { k ->
            mockk<VehicleKindMonitor> { every { this@mockk.kind } returns MutableStateFlow(k) }
        }
        val backend = FakeBackend()
        AgentOrchestrator(backend, tools, repo).also { o -> monitor?.let(o::injectVehicleKind) }.ask("привет")
        return (backend.requests.single().first() as AgentMessage.System).content
    }

    @Test fun ev_prompt_is_byte_identical_to_the_baseline() {
        val baseline = javaClass.classLoader!!.getResource("ev-baseline/agent-system-prompt.txt")!!.readText()
        assertEquals(baseline, AgentOrchestrator.SYSTEM_PROMPT)
    }

    @Test fun ev_and_unknown_cars_get_the_ev_prompt() = runTest {
        assertEquals(AgentOrchestrator.SYSTEM_PROMPT, staticPrompt(null))
        assertEquals(AgentOrchestrator.SYSTEM_PROMPT, staticPrompt(VehicleKind.EV))
        assertEquals(AgentOrchestrator.SYSTEM_PROMPT, staticPrompt(VehicleKind.UNKNOWN))
    }

    @Test fun a_hybrid_may_speak_about_the_tank() = runTest {
        val prompt = staticPrompt(VehicleKind.HYBRID)
        assertEquals(AgentOrchestrator.HYBRID_SYSTEM_PROMPT, prompt)
        assertNotEquals(AgentOrchestrator.SYSTEM_PROMPT, prompt)
        assertFalse(prompt.contains("данных о топливе и ДВС у тебя нет"))
        assertTrue(prompt.contains("подключаемый гибрид"))
        assertTrue(prompt.contains("fuel_range_km"))
        assertTrue(prompt.contains("ev_range_km"))
    }
}
