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

/**
 * Speed-up wave: when round 1 asks for a slow tool (web search, weather, chargers, trip/
 * charge stats, range, navigation), the orchestrator speaks one short persona filler phrase
 * before executing it, so the driver hears something before the real answer. Persona filler
 * pools and phrases() are covered in AgentPersonaTest; this file covers the firing rule.
 */
class AgentOrchestratorFillerTest {

    private class FakeBackend(
        val replies: ArrayDeque<Result<AgentReply>> = ArrayDeque(),
        val deltasPerReply: ArrayDeque<List<String>> = ArrayDeque(),
    ) : AgentBackend {
        val requests = mutableListOf<List<AgentMessage>>()
        override suspend fun isConfigured() = true
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
    private var clock = 1_000_000L

    private fun orchestrator(backend: AgentBackend, lines: MutableList<String> = mutableListOf()): AgentOrchestrator {
        coEvery { repo.isAgentEnabled() } returns true
        coEvery { tools.schemas() } returns JSONArray()
        return AgentOrchestrator(backend, tools, repo).also {
            it.nowMs = { clock }
            it.trace = { line -> lines += line }
        }
    }

    private fun answer(text: String) = Result.success(AgentReply(text, emptyList()))
    private fun toolCall(name: String) = Result.success(AgentReply(null, listOf(AgentToolCall("c1", name, "{}"))))

    // The default identity's persona is NAVIGATOR (AgentOrchestrator's identity default).
    private val navigatorFillers = listOf("Сейчас посмотрю.", "Секунду, проверяю.", "Минутку.", "Уже смотрю.")

    @Test fun `filler is spoken once when round 1 calls a slow tool, through onFiller only`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("web_search"), answer("Нашёл."))))
        val lines = mutableListOf<String>()
        val fillers = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        orchestrator(backend, lines).ask("что там на трассе", onFiller = { fillers += it }, onSentence = { sentences += it })
        assertEquals(1, fillers.size)
        assertTrue(fillers[0], fillers[0] in navigatorFillers)
        assertTrue("$sentences", sentences.none { it in navigatorFillers })
        assertTrue("$lines", lines.any { it == "filler: \"${fillers[0]}\" tool=web_search" })
    }

    @Test fun `filler is not spoken for a fast tool`() = runTest {
        coEvery { tools.execute(any()) } returns """{"ok":true}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("vehicle_control"), answer("Готово."))))
        val lines = mutableListOf<String>()
        val fillers = mutableListOf<String>()
        orchestrator(backend, lines).ask("закрой окна", onFiller = { fillers += it }, onSentence = {})
        assertTrue(fillers.isEmpty())
        assertFalse(lines.any { it.startsWith("filler:") })
    }

    @Test fun `filler is not spoken when the model already streamed a sentence this turn`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(
            replies = ArrayDeque(listOf(
                Result.success(AgentReply(null, listOf(AgentToolCall("c1", "web_search", "{}")))),
                answer("Нашёл."),
            )),
            // Terminator + trailing space -> the chunker emits this sentence mid-round, before
            // the filler gate downstream is even checked for this round's tool calls.
            deltasPerReply = ArrayDeque(listOf(listOf("Секунду, ищу. "))),
        )
        val lines = mutableListOf<String>()
        val fillers = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        orchestrator(backend, lines).ask("что там на трассе", onFiller = { fillers += it }, onSentence = { sentences += it })
        assertEquals(listOf("Секунду, ищу."), sentences)
        assertTrue(fillers.isEmpty())
        assertFalse(lines.any { it.startsWith("filler:") })
    }

    @Test fun `filler is not spoken when onFiller is null`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("web_search"), answer("Нашёл."))))
        val lines = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        val result = orchestrator(backend, lines).ask("что там на трассе") { sentences += it }
        assertEquals("Нашёл.", (result as AgentResult.Answer).text)
        assertTrue("$sentences", sentences.none { it in navigatorFillers })
        assertFalse(lines.any { it.startsWith("filler:") })
    }

    @Test fun `at most one filler across multiple slow rounds`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall("get_weather"), toolCall("find_chargers"), answer("Готово."),
        )))
        val lines = mutableListOf<String>()
        val fillers = mutableListOf<String>()
        orchestrator(backend, lines).ask("погода и зарядки", onFiller = { fillers += it }, onSentence = {})
        assertEquals(1, fillers.size)
        assertTrue(fillers[0], fillers[0] in navigatorFillers)
        assertEquals(1, lines.count { it.startsWith("filler:") })
    }

    @Test fun `filler never reaches history or the answer text`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("web_search"), answer("Нашёл."), answer("Ещё раз нашёл."))))
        val orch = orchestrator(backend)
        val fillers = mutableListOf<String>()
        val result = orch.ask("что там на трассе", onFiller = { fillers += it }, onSentence = {}) as AgentResult.Answer
        assertEquals(1, fillers.size)
        assertEquals("Нашёл.", result.text)
        assertFalse(result.text.contains(fillers[0]))

        orch.ask("а ещё раз")  // same clock tick: history survives the TTL and is replayed
        // The first turn made two requests (tool round + answer); the next turn's request is the third.
        assertEquals(3, backend.requests.size)
        val replayed = backend.requests[2]
        assertTrue(replayed.any { it is AgentMessage.Assistant && it.content == "Нашёл." })
        replayed.filterIsInstance<AgentMessage.Assistant>().forEach { msg ->
            assertFalse(msg.content?.contains(fillers[0]) == true)
        }
        replayed.filterIsInstance<AgentMessage.Tool>().forEach { msg ->
            assertFalse(msg.content.contains(fillers[0]))
        }
    }
}
