package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.PlaceEntity
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsHomeWorkTest {

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

    private val tools = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also {
        it.nowMs = { 1_000_000_000_000L }
        it.naviForegroundCheck = { true }
        it.naviVerifyAttempts = 1
        it.naviVerifyIntervalMs = 1L
    }

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    @Test fun `домой prefers BYDMate place over navigator shortcut`() = runTest {
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дом", lat = 55.7, lon = 37.6, radiusM = 100))
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"домой"}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            it.kind == "navigate" && JSONObject(it.payload!!).getDouble("lat") == 55.7
        }, null) }
    }

    @Test fun `домой falls back to navigator home shortcut when no place`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"Дом"}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("route", out.getString("mode"))
        coVerify { dispatcher.dispatch(match {
            it.kind == "navigate" && JSONObject(it.payload!!).getString("shortcut") == "home"
        }, null) }
    }

    @Test fun `на работу uses work shortcut`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        tools.execute(call("navigate_to", """{"destination":"на работу"}"""))

        coVerify { dispatcher.dispatch(match {
            JSONObject(it.payload!!).getString("shortcut") == "work"
        }, null) }
    }

    @Test fun `домой with nothing anywhere returns actionable error`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Нет приложения для обработки")

        val out = JSONObject(tools.execute(call("navigate_to", """{"destination":"домой"}""")))

        // dispatcher failure reason is now preferred over the hardcoded "адрес не найден" message
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("Нет приложения для обработки"))
    }

    @Test fun `ordinary destination does not trigger home logic`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { weather.geocode("Тверь") } returns Result.success(
            WeatherClient.GeoPoint(lat = 56.86, lon = 35.9, name = "Тверь"))
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)

        tools.execute(call("navigate_to", """{"destination":"Тверь"}"""))

        coVerify { dispatcher.dispatch(match {
            val p = JSONObject(it.payload!!)
            p.has("lat") && !p.has("shortcut")
        }, null) }
    }
}
