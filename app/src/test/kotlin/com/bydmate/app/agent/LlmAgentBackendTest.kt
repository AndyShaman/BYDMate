package com.bydmate.app.agent

import com.bydmate.app.data.remote.LlmHttpException
import com.bydmate.app.data.remote.OpenRouterClient
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmAgentBackendTest {

    private val client = mockk<OpenRouterClient>()
    private val resolver = mockk<LlmConnectionResolver>()
    private val backend = LlmAgentBackend(client, resolver)

    private fun conn(id: String, base: String = "https://$id/v1") =
        LlmConnection(id, id, base, "key-$id", "model-$id")
    private fun okMessage() = JSONObject("""{"content":"привет"}""")

    @Test
    fun `transient failure retries primary once then succeeds`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns null
        coEvery { client.chatRaw(any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(LlmHttpException(500)),
            Result.success(okMessage()),
        )
        val r = backend.chat(listOf(AgentMessage.User("q")), null)
        assertEquals("привет", r.getOrThrow().content)
        coVerify(exactly = 2) { client.chatRaw("https://zai/v1", "key-zai", "model-zai", any(), any()) }
    }

    @Test
    fun `auth failure skips retry and goes to fallback`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatRaw("https://zai/v1", any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(401))
        coEvery { client.chatRaw("https://openrouter/v1", any(), any(), any(), any()) } returns
            Result.success(okMessage())
        val r = backend.chat(listOf(AgentMessage.User("q")), null)
        assertEquals("привет", r.getOrThrow().content)
        coVerify(exactly = 1) { client.chatRaw("https://zai/v1", any(), any(), any(), any()) }
    }

    @Test
    fun `all attempts fail - LlmError carries message for the LAST error`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatRaw("https://zai/v1", any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(500))
        coEvery { client.chatRaw("https://openrouter/v1", any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(429))
        val e = backend.chat(listOf(AgentMessage.User("q")), null).exceptionOrNull()
        assertEquals("Лимит запросов исчерпан, попробуй позже", (e as LlmError).userMessage)
    }

    @Test
    fun `no primary - not configured`() = runTest {
        coEvery { resolver.primary() } returns null
        assertFalse(backend.isConfigured())
        val e = backend.chat(listOf(AgentMessage.User("q")), null).exceptionOrNull()
        assertTrue(e is LlmError)
    }

    @Test fun toWire_encodes_all_roles() {
        val wire = LlmAgentBackend.toWire(listOf(
            AgentMessage.System("s"),
            AgentMessage.User("u"),
            AgentMessage.Assistant(null, listOf(AgentToolCall("id1", "f", """{"a":1}"""))),
            AgentMessage.Tool("id1", """{"ok":true}"""),
        ))
        assertEquals(4, wire.length())
        assertEquals("system", wire.getJSONObject(0).getString("role"))
        val asst = wire.getJSONObject(2)
        assertTrue(asst.isNull("content"))
        assertEquals("f", asst.getJSONArray("tool_calls").getJSONObject(0)
            .getJSONObject("function").getString("name"))
        assertEquals("id1", wire.getJSONObject(3).getString("tool_call_id"))
    }

    @Test fun parseReply_extracts_text_and_calls() {
        val msg = JSONObject("""{"content":"hello","tool_calls":[
            {"id":"c1","type":"function","function":{"name":"get_vehicle_state","arguments":"{}"}}]}""")
        val reply = LlmAgentBackend.parseReply(msg)
        assertEquals("hello", reply.content)
        assertEquals("get_vehicle_state", reply.toolCalls.single().name)
    }

    @Test fun parseReply_null_content_with_calls() {
        val msg = JSONObject("""{"content":null,"tool_calls":[
            {"id":"c1","type":"function","function":{"name":"f","arguments":"{}"}}]}""")
        val reply = LlmAgentBackend.parseReply(msg)
        assertNull(reply.content)
        assertEquals(1, reply.toolCalls.size)
    }

    @Test
    fun `onDelta routes through chatStream not chatRaw`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { client.chatStream(any(), any(), any(), any(), any(), any()) } returns
            Result.success(JSONObject("""{"content":"Привет."}"""))
        val deltas = mutableListOf<String>()
        val reply = backend.chat(listOf(AgentMessage.User("хай")), null) { deltas += it }.getOrThrow()
        assertEquals("Привет.", reply.content)
        coVerify(exactly = 1) { client.chatStream(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.chatRaw(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `transient stream failure before any delta retries and falls back`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatStream("https://zai/v1", any(), any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(500))
        coEvery { client.chatStream("https://openrouter/v1", any(), any(), any(), any(), any()) } returns
            Result.success(JSONObject("""{"content":"Ок."}"""))
        val reply = backend.chat(listOf(AgentMessage.User("хай")), null) {}.getOrThrow()
        assertEquals("Ок.", reply.content)
        coVerify(exactly = 2) { client.chatStream("https://zai/v1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.chatStream("https://openrouter/v1", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stream failure after forwarded delta does not retry and says interrupted`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatStream(any(), any(), any(), any(), any(), any()) } answers {
            val cb = arg<(String) -> Unit>(5)
            cb("Начало ответа. ")
            Result.failure(LlmHttpException(500))
        }
        val result = backend.chat(listOf(AgentMessage.User("хай")), null) {}
        val err = result.exceptionOrNull() as LlmError
        assertEquals("Ответ оборвался, попробуй ещё раз", err.userMessage)
        coVerify(exactly = 1) { client.chatStream(any(), any(), any(), any(), any(), any()) }
    }
}
