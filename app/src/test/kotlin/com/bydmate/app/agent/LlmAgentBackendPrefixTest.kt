package com.bydmate.app.agent

import com.bydmate.app.data.remote.LlmHttpException
import com.bydmate.app.data.remote.OpenRouterClient
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 2: what the OpenRouter request carries beyond the messages — the cache breakpoint on
 * the static system block and latency-first provider routing. Both exist to shorten the wait
 * before the first spoken word; neither may leak to endpoints that do not speak that dialect.
 */
class LlmAgentBackendPrefixTest {

    private val client = mockk<OpenRouterClient>()
    private val resolver = mockk<LlmConnectionResolver>()
    private val backend = LlmAgentBackend(client, resolver)

    private fun conn(id: String, extraJson: String = "") =
        LlmConnection(id, id, "https://$id/v1", "key-$id", "model-$id", extraJson)

    private val messages = listOf(
        AgentMessage.System("СТАТИКА"),
        AgentMessage.System("динамика"),
        AgentMessage.User("какой заряд"),
    )

    private suspend fun wireFor(id: String): JSONArray {
        coEvery { resolver.primary() } returns conn(id)
        coEvery { resolver.fallback() } returns null
        val captured = slot<JSONArray>()
        coEvery { client.chatRaw(any(), any(), any(), capture(captured), any(), any()) } returns
            Result.success(JSONObject("""{"content":"ок"}"""))
        backend.chat(messages, null)
        return captured.captured
    }

    @Test fun openrouter_marks_only_the_static_system_block() = runTest {
        val wire = wireFor(LlmConnectionResolver.ID_OPENROUTER)
        val static = wire.getJSONObject(0).getJSONArray("content").getJSONObject(0)
        assertEquals("СТАТИКА", static.getString("text"))
        assertEquals("ephemeral", static.getJSONObject("cache_control").getString("type"))
        // The moving block sits after the breakpoint and stays a plain string.
        assertEquals("динамика", wire.getJSONObject(1).getString("content"))
    }

    // A 400 can come from the cache breakpoint itself, not only from the provider extras:
    // the plain retry must therefore drop cache_control too, or it repeats the same body.
    @Test fun the_plain_retry_drops_the_cache_breakpoint() = runTest {
        coEvery { resolver.primary() } returns conn(LlmConnectionResolver.ID_OPENROUTER)
        coEvery { resolver.fallback() } returns null
        val bodies = mutableListOf<JSONArray>()
        coEvery { client.chatRaw(any(), any(), any(), capture(bodies), any(), any()) } returnsMany listOf(
            Result.failure(LlmHttpException(400)),
            Result.success(JSONObject("""{"content":"ок"}""")),
        )
        assertTrue(backend.chat(messages, null).isSuccess)
        assertEquals(2, bodies.size)
        // First attempt carries the breakpoint, the retry sends the same system block plain.
        assertTrue(bodies[0].getJSONObject(0).get("content") is JSONArray)
        assertEquals("СТАТИКА", bodies[1].getJSONObject(0).getString("content"))
    }

    @Test fun other_endpoints_get_plain_string_content() = runTest {
        val wire = wireFor(LlmConnectionResolver.ID_CUSTOM)
        assertEquals("СТАТИКА", wire.getJSONObject(0).getString("content"))
    }

    @Test fun openrouter_sorts_providers_by_latency() {
        val extras = LlmAgentBackend.providerExtras(conn(LlmConnectionResolver.ID_OPENROUTER), streaming = false)!!
        assertEquals("latency", extras.getJSONObject("provider").getString("sort"))
    }

    @Test fun custom_extras_cannot_reroute_the_request() {
        val extras = LlmAgentBackend.providerExtras(
            conn(LlmConnectionResolver.ID_CUSTOM, """{"model":"other","tools":[],"temperature":1}"""),
            streaming = false,
        )!!
        assertFalse(extras.has("model"))
        assertFalse(extras.has("tools"))
        assertTrue(extras.has("temperature"))
        // A custom endpoint's extras are its own: they never reach the OpenRouter body.
        val openrouter = LlmAgentBackend.providerExtras(
            conn(LlmConnectionResolver.ID_OPENROUTER), streaming = false)!!
        assertEquals("latency", openrouter.getJSONObject("provider").getString("sort"))
    }
}
