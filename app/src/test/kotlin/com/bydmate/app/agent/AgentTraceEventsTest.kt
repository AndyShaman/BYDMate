package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.diagnostics.TraceRecorder
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The agent's part of the trace: the ask, every tool call with its fixed error code, and the
 *  server calls a tool makes, each linked to what caused it. */
class AgentTraceEventsTest {

    @get:Rule val trace = TraceRecorder()

    /** Two rounds: the model calls where_am_i, then answers. */
    private class ScriptedBackend : AgentBackend {
        private val replies = ArrayDeque(listOf(
            AgentReply(null, listOf(AgentToolCall("c1", "where_am_i", "{}"))),
            AgentReply("Не могу определить, где мы.", emptyList(), finishReason = "stop"),
        ))
        override suspend fun isConfigured() = true
        override suspend fun chat(messages: List<AgentMessage>, tools: JSONArray?, onDelta: ((String) -> Unit)?) =
            Result.success(replies.removeFirst())
    }

    private val repo = mockk<SettingsRepository>().also { coEvery { it.isAgentEnabled() } returns true }

    private fun orchestrator(tools: AgentTools) =
        AgentOrchestrator(ScriptedBackend(), tools, repo).also { it.nowMs = { 1_000_000L } }

    private fun realTools() = AgentTools(
        mockk<VoiceGate>(relaxed = true), mockk<BatteryStateRepository>(relaxed = true),
        mockk<RangeCalculator>(relaxed = true), mockk<TripDao>(relaxed = true),
        mockk<ChargeDao>(relaxed = true), mockk<ActionDispatcher>(relaxed = true),
        mockk<RuleDao>(relaxed = true), mockk<AutomationEngine>(relaxed = true),
        mockk<PlaceRepository>(relaxed = true), mockk<WeatherClient>(relaxed = true),
        mockk<ExaSearchClient>(relaxed = true), mockk<OpenRouterClient>(relaxed = true),
        mockk<SettingsRepository>(relaxed = true), mockk<ContactLookup>(relaxed = true),
        mockk<Context>(relaxed = true), mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true), mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true), mockk<LlmConnectionResolver>(relaxed = true),
    ).also { it.gpsFixProvider = { AgentTools.GpsFix(54.0, 27.0, ageMs = 5_000L, live = true) } }

    private fun events(): List<String> = trace.events().map { it.replace(Regex(""" ms=\d+"""), " ms=N") }

    /** A minimal OpenAI-shaped tool schema, enough for [AgentOrchestrator]'s name check. */
    private fun toolSchema(name: String) =
        JSONObject().put("type", "function").put("function", JSONObject().put("name", name))

    @Test fun `a failed tool call carries a fixed error code and hangs off the phrase`() = runTest {
        val tools = mockk<AgentTools>()
        coEvery { tools.schemas() } returns JSONArray().put(toolSchema("where_am_i"))
        coEvery { tools.execute(any(), any()) } returns """{"error":"сервис карт недоступен, не могу определить, где мы"}"""

        val heard = Trace.event(TraceArea.VOICE, "heard", "route" to "agent")
        withContext(Trace.causedBy(heard)) { orchestrator(tools).ask("где мы") }

        assertEquals(
            listOf(
                "voice  heard route=agent #1",
                "agent  ask-start #2 by=#1",
                "agent  tool-start name=where_am_i #3 by=#2",
                "agent  tool name=where_am_i verdict=error ms=N code=maps_unavailable #4 by=#3",
                "agent  ask-end outcome=answer rounds=2 finish=stop ms=N #5 by=#2",
            ),
            events(),
        )
    }

    @Test fun `a tool name the model invented is not written, a real one is`() = runTest {
        val backend = object : AgentBackend {
            private val replies = ArrayDeque(listOf(
                AgentReply(null, listOf(AgentToolCall("c1", "call_John", "{}"))),
                AgentReply("Готово.", emptyList(), finishReason = "stop"),
            ))
            override suspend fun isConfigured() = true
            override suspend fun chat(messages: List<AgentMessage>, tools: JSONArray?, onDelta: ((String) -> Unit)?) =
                Result.success(replies.removeFirst())
        }
        val tools = mockk<AgentTools>()
        coEvery { tools.schemas() } returns JSONArray().put(toolSchema("where_am_i"))
        coEvery { tools.execute(any(), any()) } returns """{"ok":true}"""

        AgentOrchestrator(backend, tools, repo).also { it.nowMs = { 1_000_000L } }.ask("кто ты")

        assertEquals(
            listOf(
                "agent  ask-start #1",
                "agent  tool-start name=unknown #2 by=#1",
                "agent  tool name=unknown verdict=ok ms=N #3 by=#2",
                "agent  ask-end outcome=answer rounds=2 finish=stop ms=N #4 by=#1",
            ),
            events(),
        )
    }

    @Test fun `error texts map to fixed codes and never leak into the trace`() {
        assertNull(AgentToolErrorCode.of("""{"ok":true}"""))
        assertNull(AgentToolErrorCode.of("not json"))
        assertEquals("maps_unavailable", AgentToolErrorCode.of("""{"error":"сервис карт недоступен, не могу определить, где мы"}"""))
        assertEquals("no_gps", AgentToolErrorCode.of("""{"error":"нет GPS-позиции машины"}"""))
        assertEquals("speed_unknown", AgentToolErrorCode.of("""{"error":"скорость неизвестна, окна и люк не открываю"}"""))
        assertEquals("automation_not_found", AgentToolErrorCode.of("""{"error":"автоматизация не найдена: Утро дома"}"""))
        assertEquals("exists", AgentToolErrorCode.of("""{"error":"место с именем «Дача» уже существует"}"""))
        assertEquals("other", AgentToolErrorCode.of("""{"error":"что-то новое"}"""))
    }

    // --- Field log 28.09: where_am_i, Overpass answers 406, the Nominatim fallback fails too ---

    @Test fun `where_am_i with both map servers down reads as one chain`() = runTest {
        val overpass = MockWebServer().apply { enqueue(MockResponse().setResponseCode(406)) }
        val nominatim = MockWebServer().apply { enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)) }
        try {
            val tools = realTools()
            tools.injectSettlementSearch(SettlementSearchClient(OkHttpClient()).also {
                it.endpoints = listOf(overpass.url("/api/interpreter").toString())
                it.nominatimUrl = nominatim.url("/reverse").toString()
            })
            orchestrator(tools).ask("где мы")
        } finally {
            overpass.shutdown()
            nominatim.shutdown()
        }

        val events = events()
        val host = overpass.hostName
        assertEquals("agent  ask-start #1", events[0])
        assertEquals("agent  tool-start name=where_am_i #2 by=#1", events[1])
        assertEquals("net    overpass host=$host code=406 ms=N #3 by=#2", events[2])
        assertTrue(events[3], events[3].matches(Regex("""net {4}nominatim host=\Q$host\E error=\w+ ms=N #4 by=#2""")))
        assertEquals("agent  tool name=where_am_i verdict=error ms=N code=maps_unavailable #5 by=#2", events[4])
        assertTrue(events[5], events[5].startsWith("agent  ask-end outcome=answer rounds=2 "))
    }
}
