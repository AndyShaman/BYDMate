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
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** where_am_i: on-car log 25.09 had the model web-search raw coordinates and name wrong villages. */
class AgentToolsWhereAmITest {

    private val settlements = mockk<SettlementSearchClient>()

    private val tools = AgentTools(
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
    ).also {
        it.injectSettlementSearch(settlements)
        it.locationProvider = { 54.0 to 27.0 }
    }

    private suspend fun whereAmI() = JSONObject(tools.execute(AgentToolCall("1", "where_am_i", "{}")))

    private fun s(name: String, place: String, lat: Double, lon: Double) =
        SettlementSearchClient.Settlement(name, place, lat, lon)

    @Test fun nearest_first_with_distance_direction_and_nearest_town() = runTest {
        coEvery { settlements.search(54.0, 27.0, 15_000, 50_000) } returns Result.success(listOf(
            s("Город", "town", 53.9, 27.1),
            s("Западная", "hamlet", 54.0, 26.97),
            s("Северная", "village", 54.01, 27.0),
            s("Хутор", "isolated_dwelling", 54.001, 27.0),
        ))
        val out = whereAmI()
        val list = out.getJSONArray("settlements")
        assertEquals(3, list.length())
        val first = list.getJSONObject(0)
        assertEquals("Северная", first.getString("name"))
        assertEquals(1.1, first.getDouble("distance_km"), 0.001)
        assertEquals("север", first.getString("direction_from_car"))
        assertEquals("деревня или посёлок", first.getString("type"))
        assertEquals("Западная", list.getJSONObject(1).getString("name"))
        assertEquals("запад", list.getJSONObject(1).getString("direction_from_car"))
        assertEquals("юго-восток", list.getJSONObject(2).getString("direction_from_car"))
        val town = out.getJSONObject("nearest_town")
        assertEquals("Город", town.getString("name"))
        assertEquals("город", town.getString("type"))
        assertFalse(out.toString().contains("Хутор"))
    }

    @Test fun isolated_dwelling_is_used_when_nothing_else_is_around() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            Result.success(listOf(s("Хутор", "isolated_dwelling", 54.001, 27.0)))
        val list = whereAmI().getJSONArray("settlements")
        assertEquals("Хутор", list.getJSONObject(0).getString("name"))
        assertEquals("хутор", list.getJSONObject(0).getString("type"))
    }

    @Test fun caps_the_list_at_five() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns
            Result.success((1..9).map { s("Д$it", "hamlet", 54.0 + it / 100.0, 27.0) })
        val list = whereAmI().getJSONArray("settlements")
        assertEquals(5, list.length())
        assertEquals("Д1", list.getJSONObject(0).getString("name"))
    }

    @Test fun empty_result_tells_the_model_not_to_guess() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns Result.success(emptyList())
        val out = whereAmI()
        assertEquals(0, out.getJSONArray("settlements").length())
        assertTrue(out.getString("note").contains("не называй место наугад"))
    }

    @Test fun search_failure_is_an_error() = runTest {
        coEvery { settlements.search(any(), any(), any(), any()) } returns Result.failure(IOException("timeout"))
        assertTrue(whereAmI().getString("error").contains("не могу определить, где мы"))
    }

    @Test fun no_gps_fix_is_an_error_without_a_network_call() = runTest {
        tools.locationProvider = { null }
        assertTrue(whereAmI().getString("error").contains("нет GPS-позиции"))
        coVerify(exactly = 0) { settlements.search(any(), any(), any(), any()) }
    }

    @Test fun tool_is_declared_and_steers_away_from_web_search() = runTest {
        val schemas = tools.schemas(includeAutomationTools = false)
        val fn = (0 until schemas.length()).map { schemas.getJSONObject(it).getJSONObject("function") }
            .single { it.getString("name") == "where_am_i" }
        assertTrue(fn.getString("description").contains("где я"))
        assertTrue(fn.getString("description").contains("не ищи координаты через web_search"))
    }
}
