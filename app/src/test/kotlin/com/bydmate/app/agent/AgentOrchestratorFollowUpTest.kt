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

class AgentOrchestratorFollowUpTest {

    private class FakeBackend(
        var configured: Boolean = true,
        val replies: ArrayDeque<Result<AgentReply>> = ArrayDeque(),
    ) : AgentBackend {
        override suspend fun isConfigured() = configured
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> =
            replies.removeFirstOrNull() ?: Result.failure(IllegalStateException("no scripted reply"))
    }

    private val tools = mockk<AgentTools>()
    private val repo = mockk<SettingsRepository>()
    private var clock = 1_000_000L

    private fun orchestrator(backend: AgentBackend): AgentOrchestrator {
        coEvery { repo.isAgentEnabled() } returns true
        coEvery { tools.schemas() } returns JSONArray()
        return AgentOrchestrator(backend, tools, repo).also { it.nowMs = { clock } }
    }

    private fun answer(text: String) = Result.success(AgentReply(text, emptyList()))

    @Test fun follow_up_expected_after_clarifying_question() = runTest {
        val o = orchestrator(FakeBackend(replies = ArrayDeque(listOf(answer("Какое окно, водителя или все?")))))
        o.ask("открой окно")
        assertTrue(o.expectsFollowUp())
    }

    @Test fun no_follow_up_after_plain_answer() = runTest {
        val o = orchestrator(FakeBackend(replies = ArrayDeque(listOf(answer("Готово")))))
        o.ask("закрой окна")
        assertFalse(o.expectsFollowUp())
    }

    @Test fun no_follow_up_before_any_turn() = runTest {
        assertFalse(orchestrator(FakeBackend()).expectsFollowUp())
    }

    @Test fun follow_up_expires_after_window() = runTest {
        val o = orchestrator(FakeBackend(replies = ArrayDeque(listOf(answer("Какое окно?")))))
        o.ask("открой окно")
        clock += 61_000
        assertFalse(o.expectsFollowUp())
    }

    @Test fun offline_error_message_asks_for_simple_command() = runTest {
        val o = orchestrator(FakeBackend(replies = ArrayDeque(listOf(Result.failure(RuntimeException("io"))))))
        assertEquals(
            AgentResult.Error("Нет связи с сервером, скажи простую команду"),
            o.ask("что нового в мире"),
        )
    }
}
