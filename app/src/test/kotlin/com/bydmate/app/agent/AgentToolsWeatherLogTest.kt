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
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.net.SocketTimeoutException

/**
 * Field log 28.09: "what's the weather" failed and the log said nothing about why. A failed
 * weather request leaves one line with the failure class, HTTP code and time - never the URL or
 * the exception text, the forecast query carries the car's coordinates.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AgentToolsWeatherLogTest {

    private val weather = mockk<WeatherClient>()
    private val places = mockk<PlaceRepository> { coEvery { getAllSnapshot() } returns emptyList() }

    private fun tools() = AgentTools(
        mockk<VoiceGate>(), mockk<BatteryStateRepository>(), mockk<RangeCalculator>(),
        mockk<TripDao>(), mockk<ChargeDao>(), mockk<ActionDispatcher>(relaxed = true),
        mockk<RuleDao>(), mockk<AutomationEngine>(), places, weather,
        mockk<ExaSearchClient>(), mockk<OpenRouterClient>(), mockk<SettingsRepository>(),
        mockk<ContactLookup>(), mockk<Context>(relaxed = true),
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private fun weatherLog(): List<String> =
        ShadowLog.getLogsForTag("AgentTools").map { it.msg }.filter { it.startsWith("get_weather") }

    @Before fun setUp() {
        ShadowLog.clear()
    }

    @Test fun a_failed_forecast_logs_class_http_code_and_time_but_no_coordinates() = runTest {
        val t = tools()
        t.locationProvider = { 55.7512 to 37.6184 }
        coEvery { weather.forecast(55.7512, 37.6184) } returns Result.failure(
            WeatherClient.HttpError(503))

        t.execute(AgentToolCall("1", "get_weather", "{}"))

        val line = weatherLog().single()
        assertTrue(line, line.contains("forecast") && line.contains("HttpError") && line.contains("http=503"))
        assertTrue(line, Regex("took=\\d+ms").containsMatchIn(line))
        assertFalse(line, line.contains("55.75") || line.contains("37.61"))
    }

    @Test fun a_timeout_logs_its_class_without_the_message() = runTest {
        val t = tools()
        coEvery { weather.geocode("Сочи") } returns Result.failure(
            SocketTimeoutException("timeout https://geocoding-api.open-meteo.com/v1/search?name=Сочи"))

        t.execute(AgentToolCall("1", "get_weather", """{"city":"Сочи"}"""))

        val line = weatherLog().single()
        assertTrue(line, line.contains("geocode") && line.contains("SocketTimeoutException") && line.contains("http=-"))
        assertFalse(line, line.contains("open-meteo") || line.contains("Сочи"))
    }
}
