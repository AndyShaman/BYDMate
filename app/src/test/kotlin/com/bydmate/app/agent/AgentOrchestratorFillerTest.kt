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

    @Test fun `filler is spoken once when round 1 calls a slow tool`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("web_search"), answer("Нашёл."))))
        val lines = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        orchestrator(backend, lines).ask("что там на трассе", { s -> sentences += s })
        assertEquals(1, sentences.size)
        assertTrue(sentences[0], sentences[0] in navigatorFillers)
        assertTrue("$lines", lines.any { it == "filler: \"${sentences[0]}\" tool=web_search" })
    }

    @Test fun `filler is not spoken for a fast tool`() = runTest {
        coEvery { tools.execute(any()) } returns """{"ok":true}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("vehicle_control"), answer("Готово."))))
        val lines = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        orchestrator(backend, lines).ask("закрой окна", { s -> sentences += s })
        assertTrue(sentences.isEmpty())
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
        val sentences = mutableListOf<String>()
        orchestrator(backend, lines).ask("что там на трассе", { s -> sentences += s })
        assertEquals(listOf("Секунду, ищу."), sentences)
        assertFalse(lines.any { it.startsWith("filler:") })
    }

    @Test fun `filler is not spoken when onSentence is null`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("web_search"), answer("Нашёл."))))
        val lines = mutableListOf<String>()
        val result = orchestrator(backend, lines).ask("что там на трассе")
        assertEquals("Нашёл.", (result as AgentResult.Answer).text)
        assertFalse(lines.any { it.startsWith("filler:") })
    }

    @Test fun `at most one filler across multiple slow rounds`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(
            toolCall("get_weather"), toolCall("find_chargers"), answer("Готово."),
        )))
        val lines = mutableListOf<String>()
        val sentences = mutableListOf<String>()
        orchestrator(backend, lines).ask("погода и зарядки", { s -> sentences += s })
        assertEquals(1, sentences.size)
        assertTrue(sentences[0], sentences[0] in navigatorFillers)
        assertEquals(1, lines.count { it.startsWith("filler:") })
    }

    @Test fun `filler never reaches history or the answer text`() = runTest {
        coEvery { tools.execute(any()) } returns """{"results":[]}"""
        val backend = FakeBackend(replies = ArrayDeque(listOf(toolCall("web_search"), answer("Нашёл."))))
        val orch = orchestrator(backend)
        val result = orch.ask("что там на трассе") { } as AgentResult.Answer
        assertEquals("Нашёл.", result.text)
        navigatorFillers.forEach { assertFalse(result.text.contains(it)) }

        orch.ask("а ещё раз")  // same clock tick: history survives the TTL and is replayed
        val replayed = backend.requests[1]
        replayed.filterIsInstance<AgentMessage.Assistant>().forEach { msg ->
            navigatorFillers.forEach { filler -> assertFalse(msg.content?.contains(filler) == true) }
        }
        replayed.filterIsInstance<AgentMessage.Tool>().forEach { msg ->
            navigatorFillers.forEach { filler -> assertFalse(msg.content.contains(filler)) }
        }
    }
}
