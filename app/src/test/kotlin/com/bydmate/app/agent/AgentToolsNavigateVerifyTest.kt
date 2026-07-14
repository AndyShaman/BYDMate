package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/** Verifies the navigate foreground verification loop introduced by Fix A. */
class AgentToolsNavigateVerifyTest {

    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val places = mockk<PlaceRepository>(relaxed = true)
    private val weather = mockk<WeatherClient>(relaxed = true)

    private fun tools() = AgentTools(
        mockk<VoiceGate>(relaxed = true),
        mockk<BatteryStateRepository>(relaxed = true),
        mockk<RangeCalculator>(relaxed = true),
        mockk<TripDao>(relaxed = true),
        mockk<ChargeDao>(relaxed = true),
        dispatcher,
        mockk<RuleDao>(relaxed = true),
        mockk<AutomationEngine>(relaxed = true),
        places,
        weather,
        mockk<ExaSearchClient>(relaxed = true),
        mockk<OpenRouterClient>(relaxed = true),
        mockk<SettingsRepository>(relaxed = true),
        mockk<ContactLookup>(relaxed = true),
        mockk<Context>(relaxed = true),
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    /** Dispatcher succeeds but the Navigator never surfaces: error must explain the situation. */
    @Test fun `navigate error when navigator never surfaces`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val t = tools().also {
            it.naviForegroundCheck = { false }
            it.naviVerifyAttempts = 2
            it.naviVerifyIntervalMs = 1L
        }
        val out = JSONObject(t.execute(call("navigate_to", """{"lat":55.0,"lon":37.0}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("не вышел на передний план"))
    }

    /** Seam returns false on the first poll, true on the second: dispatch must be reported ok. */
    @Test fun `navigate ok when navigator surfaces on second attempt`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val callCount = AtomicInteger(0)
        val t = tools().also {
            it.naviForegroundCheck = { callCount.incrementAndGet() >= 2 }
            it.naviVerifyAttempts = 3
            it.naviVerifyIntervalMs = 1L
        }
        val out = JSONObject(t.execute(call("navigate_to", """{"lat":55.0,"lon":37.0}""")))
        assertTrue(out.getBoolean("ok"))
    }

    /** When dispatch itself fails the foreground seam must never be invoked. */
    @Test fun `verification skipped when dispatch fails`() = runTest {
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Нет приложения для обработки: x")
        val t = tools().also {
            it.naviForegroundCheck = { throw AssertionError("must not be called") }
            it.naviVerifyAttempts = 1
            it.naviVerifyIntervalMs = 1L
        }
        val out = JSONObject(t.execute(call("navigate_to", """{"lat":55.0,"lon":37.0}""")))
        assertTrue(out.has("error"))
        assertFalse(out.has("ok"))
    }

    /** When home shortcut dispatch succeeds but Navigator never surfaces, the error must say
     *  "не вышел на передний план" rather than the generic "адрес не найден" fallback. */
    @Test fun `home shortcut failure reports foreground reason`() = runTest {
        coEvery { places.getAllSnapshot() } returns emptyList()
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val t = tools().also {
            it.naviForegroundCheck = { false }
            it.naviVerifyAttempts = 1
            it.naviVerifyIntervalMs = 1L
        }
        val out = JSONObject(t.execute(call("navigate_to", """{"destination":"домой"}""")))
        assertTrue(out.has("error"))
        assertTrue(out.getString("error").contains("не вышел на передний план"))
        assertFalse(out.getString("error").contains("адрес не найден"))
    }
}
