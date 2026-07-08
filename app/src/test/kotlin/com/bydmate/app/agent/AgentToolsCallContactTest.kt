package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsCallContactTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>()
    private val ruleDao = mockk<RuleDao>()
    private val engine = mockk<AutomationEngine>()
    private val places = mockk<PlaceRepository>()
    private val weather = mockk<WeatherClient>()
    private val exa = mockk<ExaSearchClient>()
    private val openRouterClient = mockk<OpenRouterClient>()
    private val settingsRepository = mockk<SettingsRepository>()
    private val contactLookup = mockk<ContactLookup>()

    private val context = mockk<Context>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, tripDao, chargeDao, dispatcher, ruleDao, engine, places, weather,
        exa, openRouterClient, settingsRepository, contactLookup, context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    private val phone = "+79995551234"

    @Test fun missing_name_reports_error_before_any_lookup() = runTest {
        val out = JSONObject(tools().execute(call("call_contact", "{}")))
        assertEquals("не указано имя", out.getString("error"))
        coVerify(exactly = 0) { contactLookup.hasPermission() }
    }

    @Test fun permission_denied_reports_error() = runTest {
        every { contactLookup.hasPermission() } returns false
        val out = JSONObject(tools().execute(call("call_contact", """{"name":"Мама"}""")))
        assertEquals("нет доступа к контактам, включите в Настройках", out.getString("error"))
        coVerify(exactly = 0) { contactLookup.findByName(any()) }
    }

    @Test fun no_matches_reports_error() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Никто") } returns emptyList()
        val out = JSONObject(tools().execute(call("call_contact", """{"name":"Никто"}""")))
        assertEquals("контакт не найден", out.getString("error"))
    }

    // Single match: dispatches via ActionDispatcher with kind="call" + autoDial=true,
    // and the phone number must never surface in the tool's own response.
    @Test fun single_match_dispatches_call_and_hides_phone_number() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } returns listOf(ContactLookup.Match("Мама", phone))
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val raw = tools().execute(call("call_contact", """{"name":"Мама"}"""))
        val out = JSONObject(raw)
        assertTrue(out.getBoolean("ok"))
        assertEquals("Мама", out.getString("calling"))
        assertFalse(raw.contains(phone))
        coVerify {
            dispatcher.dispatch(match {
                it.kind == "call" &&
                    JSONObject(it.payload!!).getString("phone") == phone &&
                    JSONObject(it.payload!!).getBoolean("autoDial")
            }, null)
        }
    }

    @Test fun single_match_dispatch_failure_reports_fixed_error() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } returns listOf(ContactLookup.Match("Мама", phone))
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(false, "Нет приложения для обработки")
        val raw = tools().execute(call("call_contact", """{"name":"Мама"}"""))
        assertEquals("не удалось позвонить", JSONObject(raw).getString("error"))
        assertFalse(raw.contains(phone))
    }

    // Pin: ActionDispatcher's DispatchResult.reason can embed the raw phone number (its
    // "dial:<phone>"/"tel:<phone>" activity-not-found label) — that reason must never be
    // forwarded verbatim, only the fixed Russian string.
    @Test fun single_match_dispatch_failure_reason_with_phone_number_never_leaks() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } returns listOf(ContactLookup.Match("Мама", phone))
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Нет приложения для обработки: dial:$phone failed")
        val raw = tools().execute(call("call_contact", """{"name":"Мама"}"""))
        assertEquals("""{"error":"не удалось позвонить"}""", raw)
        assertFalse(raw.contains(phone))
    }

    // Pin: an exception thrown by dispatch() (not just a DispatchResult(false, ...)) must
    // also collapse to the fixed Russian string, never propagate.
    @Test fun single_match_dispatch_throwing_reports_fixed_error() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } returns listOf(ContactLookup.Match("Мама", phone))
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } throws RuntimeException("dial:$phone crashed")
        val raw = tools().execute(call("call_contact", """{"name":"Мама"}"""))
        assertEquals("""{"error":"не удалось позвонить"}""", raw)
        assertFalse(raw.contains(phone))
    }

    // Ambiguous match: only names go back to the LLM, никогда номера.
    @Test fun multiple_matches_returns_names_only_no_numbers() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Андрей") } returns listOf(
            ContactLookup.Match("Андрей Иванов", "+70001112233"),
            ContactLookup.Match("Андрей Петров", "+70004445566"),
        )
        val raw = tools().execute(call("call_contact", """{"name":"Андрей"}"""))
        val out = JSONObject(raw)
        val matches = out.getJSONArray("matches")
        assertEquals(2, matches.length())
        assertTrue((0 until matches.length()).map { matches.getString(it) }
            .containsAll(listOf("Андрей Иванов", "Андрей Петров")))
        assertFalse(raw.contains("+70001112233"))
        assertFalse(raw.contains("+70004445566"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun resolver_throwing_reports_russian_error_not_exception() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } throws RuntimeException("provider gone")
        val out = JSONObject(tools().execute(call("call_contact", """{"name":"Мама"}""")))
        assertEquals("не удалось прочитать контакты", out.getString("error"))
    }

    @Test fun schemas_lists_call_contact() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        val arr = tools().schemas()
        var found = false
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            if (obj.has("function") && obj.getJSONObject("function").getString("name") == "call_contact") {
                found = true
            }
        }
        assertTrue(found)
    }
}
