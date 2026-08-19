package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** askDetached(): automation-origin agent turn — isolated from the live conversation. */
class AgentOrchestratorDetachedTest {

    // Reuse the fake backend / settings factory pattern from AgentOrchestratorTest.kt:
    // a stub AgentBackend whose chat() returns a scripted reply, mockk SettingsRepository
    // with isAgentEnabled() true, mockk AgentTools with schemas() returning JSONArray().

    private class FakeBackend(
        var configured: Boolean = true,
        val reply: Result<AgentReply>? = null,
    ) : AgentBackend {
        val requests = mutableListOf<List<AgentMessage>>()
        override suspend fun isConfigured() = configured
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> {
            requests += messages
            return reply ?: Result.failure(IllegalStateException("no scripted reply"))
        }
    }

    private fun answer(text: String) = Result.success(AgentReply(text, emptyList()))

    private fun defaultTools(): AgentTools = mockk<AgentTools>().also { t ->
        coEvery { t.schemas(includeAutomationTools = false) } returns JSONArray()
    }

    private fun makeOrchestrator(
        reply: Result<AgentReply>? = null,
        agentEnabled: Boolean = true,
        configured: Boolean = true,
        tools: AgentTools = defaultTools(),
        backend: FakeBackend = FakeBackend(configured = configured, reply = reply),
    ): AgentOrchestrator {
        val repo = mockk<SettingsRepository>()
        coEvery { repo.isAgentEnabled() } returns agentEnabled
        return AgentOrchestrator(backend, tools, repo, isMoving = { moving })
    }

    private var moving = false

    // A detached turn talks to an automation rule, not the driver: the memory tools are
    // withheld there, and the driver facts must not ride in its system prompt either.
    @Test
    fun `detached prompt carries no driver memory block`() = runTest {
        val backend = FakeBackend(reply = answer("ок"))
        val repo = mockk<SettingsRepository>()
        coEvery { repo.isAgentEnabled() } returns true
        val orch = AgentOrchestrator(
            backend, defaultTools(), repo, isMoving = { false },
            memoryBlock = { "\nО ВОДИТЕЛЕ (факты):\n- Водителя зовут Андрей" },
        )
        orch.askDetached("сводка")
        val sys = (backend.requests.single().first() as AgentMessage.System).content
        assertFalse(sys.contains("Водителя зовут Андрей"))
    }

    @Test
    fun `answer is returned without touching the shared history`() = runTest {
        val orch = makeOrchestrator(reply = answer("Погода солнечная"))
        val r = orch.askDetached("сводка погоды")
        assertEquals("Погода солнечная", (r as AgentResult.Answer).text)
        // Shared conversation stays untouched: no follow-up window opened.
        assertFalse(orch.expectsFollowUp())
    }

    @Test
    fun `detached question does not open a follow-up window`() = runTest {
        val orch = makeOrchestrator(reply = answer("Какое окно?"))  // ends with "?"
        orch.askDetached("открой окно")
        assertFalse(orch.expectsFollowUp())
    }

    @Test
    fun `disabled agent returns Disabled`() = runTest {
        val orch = makeOrchestrator(agentEnabled = false)
        assertEquals(AgentResult.Disabled, orch.askDetached("привет"))
    }

    @Test
    fun `unconfigured backend returns Error`() = runTest {
        val orch = makeOrchestrator(configured = false)
        assertTrue(orch.askDetached("привет") is AgentResult.Error)
    }

    @Test
    fun `blank prompt returns Disabled`() = runTest {
        assertEquals(AgentResult.Disabled, makeOrchestrator(reply = answer("x")).askDetached("   "))
    }

    // An automation prompt is not the driver talking: the moving tag would be a lie about who
    // is asking, and it would push the answer to a single word.
    @Test
    fun `detached prompt is not tagged while the car moves`() = runTest {
        moving = true
        val backend = FakeBackend(reply = answer("Погода солнечная"))
        makeOrchestrator(backend = backend).askDetached("сводка погоды")
        val user = backend.requests.single().last() as AgentMessage.User
        assertEquals("сводка погоды", user.content)
    }

    @Test
    fun `schemas are requested without automation tools`() = runTest {
        // verify tools.schemas(includeAutomationTools = false) was called (mockk coVerify)
        val tools = mockk<AgentTools>(relaxed = true)
        coEvery { tools.schemas(includeAutomationTools = false) } returns JSONArray()
        val orch = makeOrchestrator(reply = answer("ок"), tools = tools)
        orch.askDetached("тест")
        io.mockk.coVerify(exactly = 1) { tools.schemas(includeAutomationTools = false) }
    }
}
