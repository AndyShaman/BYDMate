package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 1: a finished turn must leave a readable trail — one line per model round, one per
 * tool call, one per turn. Without it a "the agent said done but nothing happened" report
 * cannot be judged from the user's log.
 */
class AgentTraceTest {

    private class FakeBackend(private val replies: ArrayDeque<Result<AgentReply>>) : AgentBackend {
        override suspend fun isConfigured() = true
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> {
            onDelta?.invoke("Готово")
            return replies.removeFirstOrNull() ?: Result.failure(IllegalStateException("no reply"))
        }
    }

    private val tools = mockk<AgentTools>()
    private val repo = mockk<SettingsRepository>()

    private fun orchestrator(backend: AgentBackend, lines: MutableList<String>): AgentOrchestrator {
        coEvery { repo.isAgentEnabled() } returns true
        coEvery { tools.schemas() } returns JSONArray()
        return AgentOrchestrator(backend, tools, repo).also { it.trace = { line -> lines += line } }
    }

    // The driver reported answers that just stop: when the provider says it ran out of tokens,
    // the trace must name it and the shown answer must end with a visible cut.
    @Test fun a_reply_cut_by_max_tokens_is_traced_and_marked() = runTest {
        val backend = FakeBackend(ArrayDeque(listOf(
            Result.success(AgentReply("Маршрут проходит через", emptyList(), finishReason = "length")),
        )))
        val lines = mutableListOf<String>()
        val result = orchestrator(backend, lines).ask("расскажи про маршрут") { }

        assertTrue(result is AgentResult.Answer)
        val answer = (result as AgentResult.Answer).text
        assertTrue("answer not marked as cut: $answer", answer.endsWith("…"))
        assertTrue("no finish=length in $lines", lines.any {
            it.startsWith("reply ") && it.contains("finish=length")
        })
        assertTrue("turn line does not name the cut in $lines", lines.any {
            it.startsWith("turn done") && it.contains("finish=length")
        })
    }

    @Test fun tool_round_and_turn_are_traced() = runTest {
        coEvery { tools.execute(any(), any()) } returns """{"ok":true}"""
        val backend = FakeBackend(ArrayDeque(listOf(
            Result.success(AgentReply(null, listOf(
                AgentToolCall("c1", "vehicle_control", """{"command":"windows_close_all"}""")))),
            Result.success(AgentReply("Окна закрыты", emptyList())),
        )))
        val lines = mutableListOf<String>()
        orchestrator(backend, lines).ask("закрой окна") { }

        assertTrue("no tool line in $lines", lines.any {
            it.startsWith("tool vehicle_control") && it.contains("windows_close_all") && it.contains("-> ok")
        })
        assertTrue("no reply line in $lines", lines.any {
            it.startsWith("reply ") && it.contains("tool_calls=") && it.contains("ttft=")
        })
        assertTrue("no turn line in $lines", lines.any {
            it.startsWith("turn done") && it.contains("rounds=2") && it.contains("outcome=answer")
        })
    }

    @Test fun failing_tool_is_traced_as_error() = runTest {
        coEvery { tools.execute(any(), any()) } returns """{"error":"нет данных"}"""
        val backend = FakeBackend(ArrayDeque(listOf(
            Result.success(AgentReply(null, listOf(AgentToolCall("c1", "get_vehicle_state", "{}")))),
            Result.success(AgentReply("Нет данных", emptyList())),
        )))
        val lines = mutableListOf<String>()
        orchestrator(backend, lines).ask("какой заряд")

        assertTrue("no failed tool line in $lines", lines.any {
            it.startsWith("tool get_vehicle_state") && it.contains("-> error")
        })
    }

    @Test fun backend_failure_ends_the_turn_with_an_error_outcome() = runTest {
        val backend = FakeBackend(ArrayDeque(listOf(Result.failure(RuntimeException("HTTP 500")))))
        val lines = mutableListOf<String>()
        orchestrator(backend, lines).ask("привет")

        assertTrue("no failed reply line in $lines", lines.any { it.startsWith("reply failed") })
        assertTrue("no turn line in $lines", lines.any {
            it.startsWith("turn done") && it.contains("outcome=error")
        })
    }

    @Test fun long_payloads_are_clipped() {
        val clipped = AgentTrace.clip("x".repeat(500))
        assertTrue(clipped.length <= AgentTrace.TRACE_CHARS + 1)
    }

    // Location in logs: where_am_i's result carries settlement names and distances close to
    // the car, and logs get shared in public GitHub issues - only its length is traced.
    @Test fun where_am_i_result_is_redacted_to_a_length_in_the_trace() = runTest {
        val settlementResult = """{"settlements":[{"name":"Северная","distance_km":1.1}]}"""
        coEvery { tools.execute(any(), any()) } returns settlementResult
        val backend = FakeBackend(ArrayDeque(listOf(
            Result.success(AgentReply(null, listOf(AgentToolCall("c1", "where_am_i", "{}")))),
            Result.success(AgentReply("Рядом Северная", emptyList())),
        )))
        val lines = mutableListOf<String>()
        orchestrator(backend, lines).ask("где мы")

        // Model answers keep their own text (out of scope, AgentTrace.reply): only the tool
        // line must not carry the raw settlement result.
        val toolLine = lines.single { it.startsWith("tool where_am_i") }
        assertTrue("result content leaked in tool line: $toolLine", !toolLine.contains("Северная"))
        assertTrue("no redacted length in tool line: $toolLine",
            toolLine.contains("<${settlementResult.length} chars, redacted>"))
    }

    // Field log 28.09: "где мы" failed and the trace said only "<N chars, redacted>". where_am_i's
    // error answers are fixed texts with no place in them, so an error is traced in full.
    @Test fun where_am_i_error_is_traced_in_full() = runTest {
        coEvery { tools.execute(any(), any()) } returns """{"error":"сервис карт недоступен, не могу определить, где мы"}"""
        val backend = FakeBackend(ArrayDeque(listOf(
            Result.success(AgentReply(null, listOf(AgentToolCall("c1", "where_am_i", "{}")))),
            Result.success(AgentReply("Не могу определить", emptyList())),
        )))
        val lines = mutableListOf<String>()
        orchestrator(backend, lines).ask("где мы")

        val toolLine = lines.single { it.startsWith("tool where_am_i") }
        assertTrue("error text missing in tool line: $toolLine",
            toolLine.contains("-> error") && toolLine.contains("сервис карт недоступен"))
    }
}
