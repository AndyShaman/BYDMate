package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #200: the optional `app` parameter of the three navigation tools. It reaches the dispatcher
 * payload only when the driver asked for Maps; without it the payload must be exactly what it
 * was before the parameter existed, because that string is what the released builds send.
 */
class AgentToolsNavigateAppTest {

    // #200: dispatchNavigate now asks the dispatcher whether the route will land on Maps
    // (explicit app="maps" or that as the settings default) instead of reading the payload
    // itself, so the relaxed mock needs the same "did the payload name maps" rule wired in.
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true).also {
        every { it.willOpenMaps(any()) } answers {
            firstArg<JSONObject>().optString("app").trim().equals("maps", ignoreCase = true)
        }
        every { it.willOpenWaze(any()) } returns false
    }
    private val places = mockk<PlaceRepository>(relaxed = true)

    /** A tools instance with the production foreground check left in place. */
    private fun newTools() = AgentTools(
        mockk<VoiceGate>(relaxed = true), mockk<BatteryStateRepository>(relaxed = true),
        mockk<RangeCalculator>(relaxed = true), mockk<TripDao>(relaxed = true),
        mockk<ChargeDao>(relaxed = true), dispatcher, mockk<RuleDao>(relaxed = true),
        mockk<AutomationEngine>(relaxed = true), places, mockk<WeatherClient>(relaxed = true),
        mockk<ExaSearchClient>(relaxed = true), mockk<OpenRouterClient>(relaxed = true),
        mockk<SettingsRepository>(relaxed = true), mockk<ContactLookup>(relaxed = true),
        mockk<Context>(relaxed = true), mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true), mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true), mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.nowMs = { 1_000_000_000_000L }
        it.naviVerifyAttempts = 1
        it.naviVerifyIntervalMs = 1L
    }

    /** The payload tests do not care which app surfaced, only what was dispatched. */
    private val tools = newTools().also { it.naviForegroundCheck = { true } }

    /** The payload string the dispatcher was handed for the last navigate action. */
    private suspend fun payloadOf(tool: String, args: String): String {
        val captured = slot<ActionDef>()
        coEvery { dispatcher.dispatch(capture(captured), any()) } returns DispatchResult(true)
        tools.execute(AgentToolCall("1", tool, args))
        return captured.captured.payload!!
    }

    /** Key/value view of a payload: the JSON-java stub used in plain unit tests does not keep
     *  insertion order, so the comparison is per key rather than on the serialized string. */
    private fun fields(payload: String): Map<String, String> {
        val json = JSONObject(payload)
        return json.keys().asSequence().associateWith { json.get(it).toString() }
    }

    @Test fun `without the parameter the payload is unchanged`() = runTest {
        assertEquals(
            mapOf("lat" to "55.7", "lon" to "37.6", "go" to "false"),
            fields(payloadOf("navigate_to", """{"destination":"точка","lat":55.7,"lon":37.6}""")),
        )
        assertEquals(
            mapOf("query" to "кафе"),
            fields(payloadOf("search_on_map", """{"query":"кафе"}""")),
        )
        assertEquals(
            mapOf("show" to "true", "lat" to "55.7", "lon" to "37.6", "label" to "точка"),
            fields(payloadOf("show_point_on_map", """{"destination":"точка","lat":55.7,"lon":37.6}""")),
        )
    }

    @Test fun `navigator asked for by name is still the default path`() = runTest {
        val payload = payloadOf("navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6,"app":"navigator"}""")
        assertFalse("the default must not carry an app key: $payload", payload.contains("app"))
    }

    @Test fun `maps reaches the payload of all three tools`() = runTest {
        assertEquals("maps", JSONObject(payloadOf("navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6,"app":"maps"}""")).getString("app"))
        assertEquals("maps", JSONObject(payloadOf("search_on_map",
            """{"query":"кафе","app":"MAPS"}""")).getString("app"))
        assertEquals("maps", JSONObject(payloadOf("show_point_on_map",
            """{"destination":"точка","lat":55.7,"lon":37.6,"app":"maps"}""")).getString("app"))
    }

    @Test fun `a saved place keeps the maps choice`() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дача", lat = 55.1, lon = 37.1, radiusM = 100))
        val payload = JSONObject(payloadOf("navigate_to", """{"destination":"Дача","app":"maps"}"""))
        assertEquals("maps", payload.getString("app"))
        assertEquals(55.1, payload.getDouble("lat"), 0.0001)
    }

    @Test fun `home without a saved place keeps the maps choice on the shortcut`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        val payload = JSONObject(payloadOf("navigate_to", """{"destination":"домой","app":"maps"}"""))
        assertEquals("home", payload.getString("shortcut"))
        assertEquals("maps", payload.getString("app"))
    }

    // --- foreground verification: which app has to show up after the intent ---

    /** The production checks, with only the lowest seam (what is in front) faked. */
    private fun toolsSeeing(front: String?): AgentTools = newTools().also {
        it.foregroundPackagesSince = { _ -> listOfNotNull(front) }
    }

    @Test fun `maps in front counts as a built route`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(toolsSeeing("ru.yandex.yandexmaps").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6,"app":"maps"}""")))
        assertTrue(out.getBoolean("ok"))
    }

    @Test fun `the navigator in front does not prove a maps route`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(toolsSeeing("ru.yandex.yandexnavi").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6,"app":"maps"}""")))
        assertTrue(out.getString("error"), out.getString("error").contains("Яндекс Карты не вышел"))
    }

    @Test fun `the default path still waits for the navigator`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val ok = JSONObject(toolsSeeing("ru.yandex.yandexnavi").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6}""")))
        assertTrue(ok.getBoolean("ok"))

        val failed = JSONObject(toolsSeeing("ru.yandex.yandexmaps").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6}""")))
        assertTrue(failed.getString("error"), failed.getString("error").contains("Навигатор не вышел"))
    }

    @Test fun `search and show in maps accept any maps store variant`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val t = toolsSeeing("ru.yandex.yandexmaps.rustore")
        assertTrue(JSONObject(t.execute(AgentToolCall("1", "search_on_map",
            """{"query":"кафе","app":"maps"}"""))).getBoolean("ok"))
        assertTrue(JSONObject(t.execute(AgentToolCall("1", "show_point_on_map",
            """{"destination":"точка","lat":55.7,"lon":37.6,"app":"maps"}"""))).getBoolean("ok"))
    }

    /** #200: Maps as the settings default (no `app` in the payload) must wait for Maps too -
     *  proves dispatchNavigate asks [ActionDispatcher.willOpenMaps] and not the raw payload. */
    @Test fun `settings default maps waits for maps without an app key`() = runTest {
        every { dispatcher.willOpenMaps(any()) } returns true
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(toolsSeeing("ru.yandex.yandexmaps").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6}""")))
        assertTrue(out.getBoolean("ok"))
    }

    @Test fun `settings default waze waits for waze`() = runTest {
        every { dispatcher.willOpenWaze(any()) } returns true
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(toolsSeeing("com.waze").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"точка","lat":55.7,"lon":37.6}""")))
        assertTrue(out.getBoolean("ok"))
    }

    @Test fun `home and work in maps wait for maps`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(toolsSeeing("ru.yandex.yandexmaps").execute(AgentToolCall("1", "navigate_to",
            """{"destination":"домой","app":"maps"}""")))
        assertTrue(out.getBoolean("ok"))
    }
}
