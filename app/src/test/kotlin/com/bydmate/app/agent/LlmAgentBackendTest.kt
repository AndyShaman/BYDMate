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

    private fun conn(id: String, base: String = "https://$id/v1", extraJson: String = "") =
        LlmConnection(id, id, base, "key-$id", "model-$id", extraJson)
    private fun okMessage() = JSONObject("""{"content":"привет"}""")

    @Test
    fun `transient failure retries primary once then succeeds`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns null
        coEvery { client.chatRaw(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(LlmHttpException(500)),
            Result.success(okMessage()),
        )
        val r = backend.chat(listOf(AgentMessage.User("q")), null)
        assertEquals("привет", r.getOrThrow().content)
        coVerify(exactly = 2) { client.chatRaw("https://zai/v1", "key-zai", "model-zai", any(), any(), any()) }
    }

    @Test
    fun `auth failure skips retry and goes to fallback`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(401))
        coEvery { client.chatRaw("https://openrouter/v1", any(), any(), any(), any(), any()) } returns
            Result.success(okMessage())
        val r = backend.chat(listOf(AgentMessage.User("q")), null)
        assertEquals("привет", r.getOrThrow().content)
        coVerify(exactly = 1) { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `all attempts fail - LlmError carries message for the LAST error`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(500))
        coEvery { client.chatRaw("https://openrouter/v1", any(), any(), any(), any(), any()) } returns
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

    // --- provider extras: reasoning/thinking off, usage in the stream ---

    @Test
    fun `providerExtras disables reasoning for openrouter and thinking for zai`() {
        val or = LlmAgentBackend.providerExtras(conn(LlmConnectionResolver.ID_OPENROUTER), streaming = true)!!
        assertEquals("minimal", or.getJSONObject("reasoning").getString("effort"))
        assertTrue(or.getJSONObject("reasoning").getBoolean("exclude"))
        assertTrue(or.getJSONObject("stream_options").getBoolean("include_usage"))
        val zai = LlmAgentBackend.providerExtras(conn(LlmConnectionResolver.ID_ZAI), streaming = true)!!
        assertEquals("disabled", zai.getJSONObject("thinking").getString("type"))
    }

    // stream_options is only legal with stream=true — a non-streaming turn (askDetached, i.e.
    // automations) would be rejected outright if it carried the field.
    @Test
    fun `providerExtras omits stream_options off the streaming path`() {
        val or = LlmAgentBackend.providerExtras(conn(LlmConnectionResolver.ID_OPENROUTER), streaming = false)!!
        assertEquals("minimal", or.getJSONObject("reasoning").getString("effort"))
        assertFalse(or.has("stream_options"))
    }

    @Test
    fun `providerExtras sends nothing extra to a custom endpoint`() {
        assertNull(LlmAgentBackend.providerExtras(conn(LlmConnectionResolver.ID_CUSTOM), streaming = true))
    }

    // #167: the custom slot sends whatever the user typed, so a DeepSeek-style endpoint can be
    // told to skip reasoning.
    @Test
    fun `providerExtras forwards user extras for a custom endpoint`() {
        val extras = LlmAgentBackend.providerExtras(
            conn(LlmConnectionResolver.ID_CUSTOM, extraJson = """{"thinking": false, "top_p": 0.5}"""),
            streaming = true,
        )!!
        assertFalse(extras.getBoolean("thinking"))
        assertEquals(0.5, extras.getDouble("top_p"), 0.0001)
    }

    @Test
    fun `parseExtraJson strips reserved request fields but keeps provider knobs`() {
        val obj = LlmAgentBackend.parseExtraJson(
            """{"model":"x","messages":[],"tools":[],"stream":false,"thinking":false,"top_p":0.5}"""
        )!!
        assertFalse(obj.has("model"))
        assertFalse(obj.has("messages"))
        assertFalse(obj.has("tools"))
        assertFalse(obj.has("stream"))
        assertEquals(false, obj.getBoolean("thinking"))
        assertEquals(0.5, obj.getDouble("top_p"), 0.0)
    }

    @Test
    fun `parseExtraJson returns null for blank and malformed input`() {
        assertNull(LlmAgentBackend.parseExtraJson(""))
        assertNull(LlmAgentBackend.parseExtraJson("   \n  "))
        assertNull(LlmAgentBackend.parseExtraJson("{\"thinking\": "))
        assertNull(LlmAgentBackend.parseExtraJson("не json"))
        // A bare array is valid JSON but cannot be merged into the request body.
        assertNull(LlmAgentBackend.parseExtraJson("[1, 2]"))
    }

    @Test
    fun `parseExtraJson keeps every field of a valid object`() {
        val o = LlmAgentBackend.parseExtraJson("""  {"thinking": false, "nested": {"a": 1}}  """)!!
        assertFalse(o.getBoolean("thinking"))
        assertEquals(1, o.getJSONObject("nested").getInt("a"))
    }

    @Test
    fun `zai extras reach chatRaw`() = runTest {
        coEvery { resolver.primary() } returns conn(LlmConnectionResolver.ID_ZAI)
        coEvery { resolver.fallback() } returns null
        coEvery { client.chatRaw(any(), any(), any(), any(), any(), any()) } returns Result.success(okMessage())
        backend.chat(listOf(AgentMessage.User("q")), null).getOrThrow()
        coVerify(exactly = 1) {
            client.chatRaw(any(), any(), any(), any(), any(), match<JSONObject> {
                it.getJSONObject("thinking").getString("type") == "disabled"
            })
        }
    }

    @Test
    fun `custom connection gets null extras`() = runTest {
        coEvery { resolver.primary() } returns conn(LlmConnectionResolver.ID_CUSTOM)
        coEvery { resolver.fallback() } returns null
        coEvery { client.chatRaw(any(), any(), any(), any(), any(), any()) } returns Result.success(okMessage())
        backend.chat(listOf(AgentMessage.User("q")), null).getOrThrow()
        coVerify(exactly = 1) { client.chatRaw(any(), any(), any(), any(), any(), isNull()) }
    }

    // A 400 on a request carrying extras is retried once WITHOUT them: an upstream that does
    // not know "reasoning"/"thinking" must not silence the agent.
    @Test
    fun `400 with extras retries the same connection plain`() = runTest {
        coEvery { resolver.primary() } returns conn(LlmConnectionResolver.ID_OPENROUTER)
        coEvery { resolver.fallback() } returns null
        coEvery { client.chatRaw(any(), any(), any(), any(), any(), any()) } returnsMany listOf(
            Result.failure(LlmHttpException(400)),
            Result.success(okMessage()),
        )
        val r = backend.chat(listOf(AgentMessage.User("q")), null)
        assertEquals("привет", r.getOrThrow().content)
        coVerify(exactly = 1) {
            client.chatRaw(any(), any(), any(), any(), any(), match<JSONObject> { it.has("reasoning") })
        }
        coVerify(exactly = 1) { client.chatRaw(any(), any(), any(), any(), any(), isNull()) }
    }

    @Test
    fun `400 without extras is not retried`() = runTest {
        coEvery { resolver.primary() } returns conn(LlmConnectionResolver.ID_CUSTOM)
        coEvery { resolver.fallback() } returns null
        coEvery { client.chatRaw(any(), any(), any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(400))
        assertTrue(backend.chat(listOf(AgentMessage.User("q")), null).isFailure)
        coVerify(exactly = 1) { client.chatRaw(any(), any(), any(), any(), any(), any()) }
    }

    // --- retry/fallback time budget: a dead network must not cost the driver minutes of silence ---

    @Test
    fun `slow transient failure skips the retry but still tries the fallback`() = runTest {
        var now = 0L
        backend.nowMs = { now }
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) } answers {
            now += 15_000  // past RETRY_BUDGET_MS, still inside FALLBACK_BUDGET_MS
            Result.failure(LlmHttpException(500))
        }
        coEvery { client.chatRaw("https://openrouter/v1", any(), any(), any(), any(), any()) } returns
            Result.success(okMessage())
        val r = backend.chat(listOf(AgentMessage.User("q")), null)
        assertEquals("привет", r.getOrThrow().content)
        coVerify(exactly = 1) { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.chatRaw("https://openrouter/v1", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `attempt past the fallback budget fails fast with no further calls`() = runTest {
        var now = 0L
        backend.nowMs = { now }
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) } answers {
            now += 25_000  // the call timeout burned the whole budget
            Result.failure(LlmHttpException(500))
        }
        val e = backend.chat(listOf(AgentMessage.User("q")), null).exceptionOrNull()
        assertEquals("Сервер модели недоступен, попробуй позже", (e as LlmError).userMessage)
        coVerify(exactly = 1) { client.chatRaw("https://zai/v1", any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.chatRaw("https://openrouter/v1", any(), any(), any(), any(), any()) }
    }

    @Test
    fun `onDelta routes through chatStream not chatRaw`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { client.chatStream(any(), any(), any(), any(), any(), any(), any()) } returns
            Result.success(JSONObject("""{"content":"Привет."}"""))
        val deltas = mutableListOf<String>()
        val reply = backend.chat(listOf(AgentMessage.User("хай")), null) { deltas += it }.getOrThrow()
        assertEquals("Привет.", reply.content)
        coVerify(exactly = 1) { client.chatStream(any(), any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { client.chatRaw(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `transient stream failure before any delta retries and falls back`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatStream("https://zai/v1", any(), any(), any(), any(), any(), any()) } returns
            Result.failure(LlmHttpException(500))
        coEvery { client.chatStream("https://openrouter/v1", any(), any(), any(), any(), any(), any()) } returns
            Result.success(JSONObject("""{"content":"Ок."}"""))
        val reply = backend.chat(listOf(AgentMessage.User("хай")), null) {}.getOrThrow()
        assertEquals("Ок.", reply.content)
        coVerify(exactly = 2) { client.chatStream("https://zai/v1", any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 1) { client.chatStream("https://openrouter/v1", any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stream failure after forwarded delta does not retry and says interrupted`() = runTest {
        coEvery { resolver.primary() } returns conn("zai")
        coEvery { resolver.fallback() } returns conn("openrouter")
        coEvery { client.chatStream(any(), any(), any(), any(), any(), any(), any()) } answers {
            val cb = arg<(String) -> Unit>(6)
            cb("Начало ответа. ")
            Result.failure(LlmHttpException(500))
        }
        val result = backend.chat(listOf(AgentMessage.User("хай")), null) {}
        val err = result.exceptionOrNull() as LlmError
        assertEquals("Ответ оборвался, попробуй ещё раз", err.userMessage)
        coVerify(exactly = 1) { client.chatStream(any(), any(), any(), any(), any(), any(), any()) }
    }
}
