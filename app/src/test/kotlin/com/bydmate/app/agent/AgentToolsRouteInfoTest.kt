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
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.media.NaviNotificationParser
import com.bydmate.app.media.NaviRouteHolder
import com.bydmate.app.media.NaviScreenReader
import com.bydmate.app.navdata.NavGuidance
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AgentToolsRouteInfoTest {

    private val gate = mockk<VoiceGate>(relaxed = true)
    private val battery = mockk<BatteryStateRepository>(relaxed = true)
    private val range = mockk<RangeCalculator>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val chargeDao = mockk<ChargeDao>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val engine = mockk<AutomationEngine>(relaxed = true)
    private val places = mockk<PlaceRepository>(relaxed = true)
    private val weather = mockk<WeatherClient>(relaxed = true)
    private val exa = mockk<ExaSearchClient>(relaxed = true)
    private val openRouterClient = mockk<OpenRouterClient>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val contactLookup = mockk<ContactLookup>(relaxed = true)

    private val context = mockk<Context>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also { it.nowMs = { 1_000_000_000_000L } }

    private fun fullScreen() = NaviScreenReader.ScreenInfo(
        speedLimit = "60",
        exitNumber = null,
        maneuverDistance = "250 м",
        remainingDistance = "28 км",
        remainingTime = "27 мин",
        arrivalTime = "10:10",
        street = "ул. Качаны",
    )

    @Before
    @After
    fun resetHolder() {
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
        NavGuidanceHub.reset()
    }

    @Test fun get_route_info_errors_when_holder_empty() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_route_info", "{}")))
        assertTrue(out.has("error"))
    }

    @Test fun get_route_info_returns_parsed_and_raw_fields() = runTest {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", "Через 300 м направо", null,
            1_000_000_000_000L - 3 * 60_000L,
            NaviNotificationParser.Parsed(
                maneuver = "направо", maneuverResource = "notification_right_sdl",
                distance = "300 м", street = "улица Ленина", bigTexts = listOf("18:40", "42 км"),
            ),
        )
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_route_info", "{}")))
        assertEquals("направо", out.getString("maneuver"))
        assertEquals("300 м", out.getString("maneuver_distance"))
        assertEquals("улица Ленина", out.getString("street"))
        assertEquals("18:40", out.getJSONArray("route_lines").getString(0))
        assertEquals("5 км", out.getString("raw_title"))
        assertEquals(3, out.getLong("age_min"))
        assertTrue(out.has("note"))
        assertFalse(out.has("maneuver_icon"))
    }

    @Test fun `get_route_info exposes maneuver_icon when icon is unknown`() = runTest {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", null, null,
            1_000_000_000_000L,
            NaviNotificationParser.Parsed(
                maneuver = null, maneuverResource = "notification_right_sdl",
                distance = "350 м", street = null, bigTexts = emptyList(),
            ),
        )
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_route_info", "{}")))
        assertEquals("notification_right_sdl", out.getString("maneuver_icon"))
        assertFalse(out.has("maneuver"))
    }

    @Test fun get_route_info_extras_only_fallback_keeps_raw_fields() = runTest {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", "Через 300 м направо", null,
            1_000_000_000_000L,
        )
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_route_info", "{}")))
        assertEquals("5 км", out.getString("raw_title"))
        assertFalse(out.has("maneuver"))
    }

    @Test fun get_route_info_appends_soc_and_range() = runTest {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", null, null, 1_000_000_000_000L,
        )
        val t = tools()
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(soc = 80, totalElec = 1234.5)
        coEvery { range.estimate(80, 1234.5) } returns 320.0
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertEquals(80, out.getInt("soc"))
        assertEquals(320, out.getInt("range_km"))
    }

    @Test fun `get_route_info from screen when holder is empty`() = runTest {
        val t = tools()
        t.naviScreenProvider = { fullScreen() }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("error"))
        assertFalse(out.has("maneuver"))
        assertFalse(out.has("raw_title"))
        assertEquals("250 м", out.getString("maneuver_distance"))
        assertEquals("28 км", out.getString("remaining_distance"))
        assertEquals("27 мин", out.getString("remaining_time"))
        assertEquals("10:10", out.getString("arrival_time"))
        assertEquals("ул. Качаны", out.getString("street"))
        assertEquals("60", out.getString("speed_limit"))
        assertEquals(0L, out.getLong("age_min"))
    }

    @Test fun `notification street wins over screen street`() = runTest {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", null, null,
            1_000_000_000_000L,
            NaviNotificationParser.Parsed(
                maneuver = null, maneuverResource = null,
                distance = null, street = "улица Ленина", bigTexts = emptyList(),
            ),
        )
        val t = tools()
        t.naviScreenProvider = { fullScreen() }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertEquals("улица Ленина", out.getString("street"))
        assertEquals("28 км", out.getString("remaining_distance"))
    }

    @Test fun `screen provider throws - notification fields still returned`() = runTest {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", null, null,
            1_000_000_000_000L,
            NaviNotificationParser.Parsed(
                maneuver = "прямо", maneuverResource = null,
                distance = "500 м", street = "пр. Мира", bigTexts = emptyList(),
            ),
        )
        val t = tools()
        t.naviScreenProvider = { throw RuntimeException("a11y tree unavailable") }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("error"))
        assertEquals("прямо", out.getString("maneuver"))
        assertEquals("500 м", out.getString("maneuver_distance"))
        assertEquals("пр. Мира", out.getString("street"))
    }

    @Test fun `hub numerics fill gaps when notification and screen are gone`() = runTest {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 300, road = "пр. Мира", speedLimit = 60),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000_000_000_000L - 30_000L,
        )
        val t = tools()
        t.naviScreenProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertFalse(out.has("error"))
        assertEquals("направо", out.getString("maneuver"))
        assertEquals("300 м", out.getString("maneuver_distance"))
        assertEquals("пр. Мира", out.getString("street"))
        assertEquals("60", out.getString("speed_limit"))
        assertEquals(30L, out.getLong("hub_age_sec"))
    }

    @Test fun `stale hub does not rescue empty sources`() = runTest {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 2, distanceMeters = 300),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000_000_000_000L - NavGuidanceHub.ACTIVE_TIMEOUT_MS - 1,
        )
        val t = tools()
        t.naviScreenProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertTrue(out.has("error"))
    }

    @Test fun `notification fields win over hub`() = runTest {
        NavGuidanceHub.update(
            NavGuidance(maneuverGaode = 1, distanceMeters = 999, road = "хаб-улица"),
            NavGuidanceHub.Source.A11Y,
            nowMs = 1_000_000_000_000L - 5_000L,
        )
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, "5 км", null, null,
            1_000_000_000_000L,
            NaviNotificationParser.Parsed(
                maneuver = "направо", maneuverResource = null,
                distance = "300 м", street = "улица Ленина", bigTexts = emptyList(),
            ),
        )
        val t = tools()
        t.naviScreenProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_route_info", "{}")))
        assertEquals("направо", out.getString("maneuver"))
        assertEquals("300 м", out.getString("maneuver_distance"))
        assertEquals("улица Ленина", out.getString("street"))
    }
}
