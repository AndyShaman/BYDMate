package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentOrchestratorTest {

    private class FakeBackend(
        var configured: Boolean = true,
        val replies: ArrayDeque<Result<AgentReply>> = ArrayDeque(),
        val deltasPerReply: ArrayDeque<List<String>> = ArrayDeque(),
    ) : AgentBackend {
        val requests = mutableListOf<List<AgentMessage>>()
        override suspend fun isConfigured() = configured
        override suspend fun chat(
            messages: List<AgentMessage>,
            tools: JSONArray?,
            onDelta: ((String) -> Unit)?,
        ): Result<AgentReply> {
            requests += messages
            deltasPerReply.removeFirstOrNull()?.forEach { d -> onDelta?.invoke(d) }
            return replies.removeFirstOrNull() ?: Result.failure(IllegalStateException("no scripted reply"))
        }
    }

    private val tools = mockk<AgentTools>()
    private val repo = mockk<SettingsRepository>()

    private fun orchestrator(backend: AgentBackend, enabled: Boolean = true): AgentOrchestrator {
        coEvery { repo.isAgentEnabled() } returns enabled
        coEvery { tools.schemas() } returns JSONArray()
        return AgentOrchestrator(backend, tools, repo).also { it.nowMs = { clock } }
    }

    private var clock = 1_000_000L

    private fun answer(text: String) = Result.success(AgentReply(text, emptyList()))
    private fun toolCall(name: String) = Result.success(
        AgentReply(null, listOf(AgentToolCall("c1", name, "{}"))))
    private fun toolCall(call: AgentToolCall) = Result.success(AgentReply(null, listOf(call)))

    @Test fun disabled_returns_disabled_without_network() = runTest {
        val backend = FakeBackend()
        val result = orchestrator(backend, enabled = false).ask("привет")
        assertEquals(AgentResult.Disabled, result)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun unconfigured_returns_error() = runTest {
        val backend = FakeBackend(configured = false)
        val result = orchestrator(backend).ask("привет")
        assertTrue(result is AgentResult.Error)
        assertTrue(backend.requests.isEmpty())
    }

    @Test fun blank_input_returns_disabled() = runTest {
        val backend = FakeBackend()
        assertEquals(AgentResult.Disabled, orchestrator(backend).ask("   "))
    }

    @Test fun plain_answer_flows_through() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Заряд 80%"))))
        val result = orchestrator(backend).ask("какой заряд?")
        assertEquals(AgentResult.Answer("Заряд 80%"), result)
        // System prompt injected + user message present.
        val msgs = backend.requests.single()
        assertTrue(msgs.first() is AgentMessage.System)
        assertTrue(msgs.any { it is AgentMessage.User && it.content == "какой заряд?" })
    }

    @Test fun tool_call_executes_and_result_feeds_back() = runTest {
        coEvery { tools.execute(match { it.name == "get_vehicle_state" }) } returns """{"soc_percent":80}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall("get_vehicle_state"), answer("Заряд 80%"))))
        val result = orchestrator(backend).ask("какой заряд?")
        // Result now carries tool outcomes too; compare text only (Task 9).
        assertEquals("Заряд 80%", (result as AgentResult.Answer).text)
        coVerify(exactly = 1) { tools.execute(any()) }
        // Second request carries the assistant tool_calls message + tool result.
        val second = backend.requests[1]
        assertTrue(second.any { it is AgentMessage.Tool && it.content.contains("soc_percent") })
    }

    @Test fun iteration_cap_returns_error() = runTest {
        coEvery { tools.execute(any()) } returns """{"ok":true}"""
        // Distinct arguments per round: this must exercise the iteration cap, not the
        // loop-detection guard (Task 4), which would otherwise abort earlier on repeats.
        val backend = FakeBackend(replies = ArrayDeque(
            List(9) { i -> toolCall(AgentToolCall("c$i", "get_vehicle_state", """{"n":$i}""")) }
        ))
        val result = orchestrator(backend).ask("зациклись")
        assertTrue(result is AgentResult.Error)
        assertEquals(8, backend.requests.size)
    }

    @Test fun backend_failure_returns_error() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            Result.failure(RuntimeException("HTTP 500")))))
        assertTrue(orchestrator(backend).ask("привет") is AgentResult.Error)
    }

    @Test fun followup_within_ttl_keeps_history() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("80%"), answer("Открыл"))))
        val orch = orchestrator(backend)
        orch.ask("какой заряд?")
        clock += 60_000  // within 5-min TTL
        orch.ask("открой окна")
        val second = backend.requests[1]
        assertTrue(second.any { it is AgentMessage.User && it.content == "какой заряд?" })
        assertTrue(second.any { it is AgentMessage.Assistant && it.content == "80%" })
    }

    @Test fun history_cleared_after_ttl() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("80%"), answer("Ок"))))
        val orch = orchestrator(backend)
        orch.ask("какой заряд?")
        clock += 300_001  // past 5-min TTL
        orch.ask("открой окна")
        val second = backend.requests[1]
        assertTrue(second.none { it is AgentMessage.User && it.content == "какой заряд?" })
    }

    @Test fun history_trims_to_cap_and_starts_with_user() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(List(30) { answer("ответ $it") }))
        val orch = orchestrator(backend)
        repeat(15) { orch.ask("вопрос $it") }  // 30 history messages before trim
        val last = backend.requests.last()
        // System + at most MAX_HISTORY history messages.
        assertTrue(last.size <= 21)
        // First message after System must be a User message (no orphan assistant/tool head).
        assertTrue(last[1] is AgentMessage.User)
    }

    // --- Task 6: fast-path command memory + terse "moving" mode ---

    @Test fun note_action_appears_in_next_ask_request() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Ок"))))
        val orch = orchestrator(backend)
        orch.noteAction("открой окно водителя")
        orch.ask("а теперь закрой")
        val msgs = backend.requests.single()
        assertTrue(msgs.any {
            it is AgentMessage.Assistant && it.content == "[выполнено: открой окно водителя]"
        })
    }

    @Test fun note_action_survives_within_ttl() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Ок"))))
        val orch = orchestrator(backend)
        orch.noteAction("открой окно")
        clock += 60_000  // within 5-min TTL
        orch.ask("закрой")
        val msgs = backend.requests.single()
        assertTrue(msgs.any { it is AgentMessage.Assistant && it.content == "[выполнено: открой окно]" })
    }

    @Test fun note_action_expires_after_ttl() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Ок"))))
        val orch = orchestrator(backend)
        orch.noteAction("открой окно")
        clock += 300_001  // past 5-min TTL
        orch.ask("закрой")
        val msgs = backend.requests.single()
        assertTrue(msgs.none { it is AgentMessage.Assistant && it.content == "[выполнено: открой окно]" })
    }

    // Fix (Finding 1): noteAction() must apply the same stale-history TTL check ask() does,
    // BEFORE appending its note — otherwise a fast-path note after the TTL already expired both
    // resurrects the old conversation into the note's history and refreshes lastAnswerAt, hiding
    // the staleness from the very next ask().
    @Test fun stale_history_cleared_before_note_action_appends() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("80%"), answer("Ок"))))
        val orch = orchestrator(backend)
        orch.ask("какой заряд?")  // populates history + sets lastAnswerAt
        clock += 300_001  // past 5-min TTL — history is now stale
        orch.noteAction("открыл окно")  // must clear the stale history first, not resurrect it
        orch.ask("а закрой")
        val msgs = backend.requests.last()
        assertTrue(msgs.none { it is AgentMessage.User && it.content == "какой заряд?" })
        assertTrue(msgs.none { it is AgentMessage.Assistant && it.content == "80%" })
        assertTrue(msgs.any { it is AgentMessage.Assistant && it.content == "[выполнено: открыл окно]" })
    }

    // Fix (Finding 2): a stretch of >MAX_HISTORY synthetic notes with no User message anywhere
    // must fall back to a hard cap (drop oldest, keep newest) instead of finding no User boundary
    // and wiping the whole history, including the freshest notes.
    @Test fun trim_falls_back_to_hard_cap_when_no_user_boundary() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Итог"))))
        val orch = orchestrator(backend)
        repeat(25) { orch.noteAction("действие $it") }  // 25 notes, no User message at all
        orch.ask("итог")
        val msgs = backend.requests.single()
        assertTrue(msgs.none { it is AgentMessage.Assistant && it.content == "[выполнено: действие 0]" })
        assertTrue(msgs.any { it is AgentMessage.Assistant && it.content == "[выполнено: действие 24]" })
        // Pin the hard-cap boundary itself. A test that only checks the oldest-absent/newest-present
        // pair also passes under the OLD wipe-at-21 bug: each time size>MAX_HISTORY with no User
        // anywhere, the old algorithm's inner loop scans to the end of the list and wipes the WHOLE
        // history to empty (not just the excess), so only the last few notes appended after the final
        // wipe survive ("действие 21".."действие 24" — verified by tracing the old removeAt(0)-until-
        // User-or-empty loop against this exact 25-call sequence). "действие 0" is still gone and
        // "действие 24" is still present under that bug too, so that pair alone does not fail against
        // it. "действие 6" (the oldest surviving note once the followup ask() itself trims one more
        // to make room for its own User message — MAX_HISTORY=20 keeps действие 6..24 + the User,
        // verified by tracing the fixed hard-cap algorithm) is ABSENT under the old bug (which only
        // has 21..24 by then) — asserting it present catches that regression. "действие 5" (one past
        // the cap boundary on the fixed side) stays absent on both, included as a boundary sanity check.
        assertTrue(msgs.any { it is AgentMessage.Assistant && it.content == "[выполнено: действие 6]" })
        assertTrue(msgs.none { it is AgentMessage.Assistant && it.content == "[выполнено: действие 5]" })
    }

    @Test fun system_prompt_includes_current_date() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Готово"))))
        val orch = orchestrator(backend)
        clock = java.util.Calendar.getInstance().apply {
            clear(); set(2026, java.util.Calendar.JULY, 5, 12, 0)
        }.timeInMillis
        orch.ask("что там с зарядом")
        val system = backend.requests.single().first() as AgentMessage.System
        assertTrue(system.content.contains("Сегодня 5 июля 2026 года"))
    }

    @Test fun moving_true_adds_terse_instruction_to_system_prompt() = runTest {
        coEvery { repo.isAgentEnabled() } returns true
        coEvery { tools.schemas() } returns JSONArray()
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Готово"))))
        val orch = AgentOrchestrator(backend, tools, repo, isMoving = { true }).also { it.nowMs = { clock } }
        orch.ask("что там с зарядом")
        val system = backend.requests.single().first() as AgentMessage.System
        assertTrue(system.content.contains("движется"))
    }

    @Test fun moving_false_by_default_no_terse_instruction() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Готово"))))
        val orch = orchestrator(backend)
        orch.ask("что там с зарядом")
        val system = backend.requests.single().first() as AgentMessage.System
        assertTrue(!system.content.contains("движется"))
    }

    // --- Task 9: tool outcomes journal ---

    @Test fun answer_carries_tool_outcomes() = runTest {
        coEvery { tools.execute(match { it.name == "vehicle_control" }) } returns """{"error":"не выполнено"}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall("vehicle_control"), answer("Не получилось"))))
        val r = orchestrator(backend).ask("открой люк") as AgentResult.Answer
        assertEquals(listOf(AgentToolOutcome("vehicle_control", false)), r.tools)
    }

    @Test fun answer_without_tools_has_empty_outcomes() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Привет"))))
        val r = orchestrator(backend).ask("привет") as AgentResult.Answer
        assertEquals(emptyList<AgentToolOutcome>(), r.tools)
    }

    // --- Carried minor from final D1 review (M1): a disabled/unconfigured agent must not
    // swallow NLU traffic during an open follow-up window. ---

    @Test fun disabling_agent_after_question_closes_follow_up_window() = runTest {
        coEvery { repo.isAgentEnabled() } returnsMany listOf(true, false)
        coEvery { tools.schemas() } returns JSONArray()
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Какое окно, водителя или все?"))))
        val orch = AgentOrchestrator(backend, tools, repo).also { it.nowMs = { clock } }
        orch.ask("открой окно")
        assertTrue(orch.expectsFollowUp())
        orch.ask("да, водителя")  // agent disabled mid-window
        assertFalse(orch.expectsFollowUp())
    }

    // --- Task 4: loop detection ---

    @Test
    fun `third identical tool call is not executed and gets synthetic loop error`() = runTest {
        val call = AgentToolCall("c1", "get_vehicle_state", "{}")
        val executedCalls = mutableListOf<AgentToolCall>()
        coEvery { tools.execute(any()) } answers { executedCalls += firstArg<AgentToolCall>(); """{"soc_percent":80}""" }
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall(call), toolCall(call), toolCall(call), answer("готово"),
        )))
        val result = orchestrator(backend).ask("заряд?")
        assertTrue(result is AgentResult.Answer)
        assertEquals(2, executedCalls.size) // tool executed only twice
        // the 3rd round got a synthetic tool message with an error
        val lastToolMsg = backend.requests.last()
            .filterIsInstance<AgentMessage.Tool>().last()
        assertTrue(lastToolMsg.content.contains("уже выполнялся"))
    }

    @Test
    fun `two loop strikes abort the turn`() = runTest {
        val call = AgentToolCall("c1", "get_vehicle_state", "{}")
        coEvery { tools.execute(any()) } returns """{"soc_percent":80}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall(call), toolCall(call), toolCall(call), toolCall(call), answer("недостижимо"),
        )))
        val result = orchestrator(backend).ask("заряд?")
        assertTrue(result is AgentResult.Error)
        assertEquals("Модель зациклилась, попробуй переформулировать", (result as AgentResult.Error).message)
    }

    // Fix (Critical, round 1): on the abort branch the assistant tool_calls message for the
    // *current* round was appended to history before the loop-strike check returned, but the
    // paired Tool message for that round's call (and any later calls in the same multi-call
    // round) was never appended. History is an instance field that survives across ask() calls
    // (TTL-gated, not call-gated), so the very next ask() would replay that dangling
    // Assistant(tool_calls=[...]) with no matching Tool message to an OpenAI-compatible backend,
    // which rejects that shape with HTTP 400.
    @Test
    fun `history stays paired after loop abort so the next ask can reuse it`() = runTest {
        val call1 = AgentToolCall("c1", "get_vehicle_state", "{}")
        val call2 = AgentToolCall("c2", "get_vehicle_state", "{}")
        val call3 = AgentToolCall("c3", "get_vehicle_state", "{}")
        val call4 = AgentToolCall("c4", "get_vehicle_state", "{}")
        coEvery { tools.execute(any()) } returns """{"soc_percent":80}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall(call1), toolCall(call2), toolCall(call3), toolCall(call4), answer("ок"),
        )))
        val orch = orchestrator(backend)
        assertTrue(orch.ask("заряд?") is AgentResult.Error) // aborts on the 2nd loop strike

        orch.ask("ещё раз") // same clock tick: history survives the TTL and is replayed
        val sentHistory = backend.requests.last()
        val toolCallIds = sentHistory.filterIsInstance<AgentMessage.Assistant>()
            .flatMap { it.toolCalls.map { call -> call.id } }
        val toolMsgIds = sentHistory.filterIsInstance<AgentMessage.Tool>().map { it.toolCallId }
        toolCallIds.forEach { id ->
            assertTrue("tool_call id $id has no paired Tool message in history", toolMsgIds.contains(id))
        }
    }

    @Test
    fun `cancelling ask during a tool round rolls history back before next ask`() = runTest {
        val toolStarted = CompletableDeferred<Unit>()
        val releaseTool = CompletableDeferred<Unit>()
        coEvery { tools.execute(any()) } coAnswers {
            toolStarted.complete(Unit)
            releaseTool.await()
            """{"ok":true}"""
        }
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall(AgentToolCall("c1", "vehicle_control", """{"command":"windows_close_all"}""")),
            answer("Готово"),
        )))
        val orch = orchestrator(backend)

        val job = launch { orch.ask("закрой окна") }
        toolStarted.await()
        job.cancelAndJoin()

        orch.ask("повтори")
        val sentHistory = backend.requests.last()
        val toolCallIds = sentHistory.filterIsInstance<AgentMessage.Assistant>()
            .flatMap { it.toolCalls.map { call -> call.id } }
        val toolMsgIds = sentHistory.filterIsInstance<AgentMessage.Tool>().map { it.toolCallId }
        toolCallIds.forEach { id ->
            assertTrue("tool_call id $id has no paired Tool message in history", toolMsgIds.contains(id))
        }
        assertTrue(sentHistory.none { it is AgentMessage.User && it.content == "закрой окна" })
    }

    @Test
    fun `different arguments are not treated as a repeat and both execute`() = runTest {
        val call1 = AgentToolCall("c1", "vehicle_control", """{"action":"windows_open_all"}""")
        val call2 = AgentToolCall("c2", "vehicle_control", """{"action":"windows_close_all"}""")
        val executedCalls = mutableListOf<AgentToolCall>()
        coEvery { tools.execute(any()) } answers { executedCalls += firstArg<AgentToolCall>(); """{"ok":true}""" }
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            Result.success(AgentReply(null, listOf(call1, call2))), answer("готово"),
        )))
        val result = orchestrator(backend).ask("открой и закрой окна")
        assertTrue(result is AgentResult.Answer)
        assertEquals(2, executedCalls.size)
    }

    // --- Wave K Task 5: per-turn sentence streaming ---

    @Test
    fun `final answer sentences are forwarded and tail is flushed`() = runTest {
        val backend = FakeBackend(
            replies = ArrayDeque(listOf(Result.success(AgentReply("Заряд 80. Хватит до дома", emptyList())))),
            deltasPerReply = ArrayDeque(listOf(listOf("Заряд 80. ", "Хватит до дома"))),
        )
        val orch = orchestrator(backend)
        val sentences = mutableListOf<String>()
        val r = orch.ask("заряд", { s -> sentences += s })
        assertEquals(listOf("Заряд 80.", "Хватит до дома"), sentences)
        assertEquals("Заряд 80. Хватит до дома", (r as AgentResult.Answer).text)
    }

    @Test
    fun `tool round unterminated tail is not spoken`() = runTest {
        coEvery { tools.execute(any()) } returns "{}"
        val toolReply = AgentReply(null, listOf(AgentToolCall("c1", "get_vehicle_state", "{}")))
        val backend = FakeBackend(
            replies = ArrayDeque(listOf(Result.success(toolReply), Result.success(AgentReply("Готово.", emptyList())))),
            deltasPerReply = ArrayDeque(listOf(listOf("Секунду"), listOf("Готово."))),
        )
        val orch = orchestrator(backend)
        val sentences = mutableListOf<String>()
        orch.ask("проверь", { s -> sentences += s })
        assertEquals(listOf("Готово."), sentences)
    }

    @Test
    fun `ask without onSentence keeps legacy behaviour`() = runTest {
        val backend = FakeBackend(replies = ArrayDeque(listOf(Result.success(AgentReply("Ответ.", emptyList())))))
        val orch = orchestrator(backend)
        val r = orch.ask("вопрос")
        assertEquals("Ответ.", (r as AgentResult.Answer).text)
    }

    @Test
    fun `system prompt carries persona block and name`() = runTest {
        coEvery { repo.isAgentEnabled() } returns true
        coEvery { tools.schemas() } returns JSONArray()
        val backend = FakeBackend(replies = ArrayDeque(listOf(answer("Готово"))))
        val identity = { com.bydmate.app.voice.AgentIdentity("Лео", com.bydmate.app.voice.AgentPersona.SNARKY) }
        val orch = AgentOrchestrator(backend, tools, repo, identity = identity).also { it.nowMs = { clock } }
        orch.ask("привет")
        val sys = backend.requests.single().first() as AgentMessage.System
        assertTrue(sys.content.contains("ХАРАКТЕР:"))
        assertTrue(sys.content.contains("Тебя зовут Лео"))
        assertTrue(sys.content.contains("шутку или анекдот"))
        assertFalse(AgentOrchestrator.SYSTEM_PROMPT.contains("ХАРАКТЕР"))
    }
}
