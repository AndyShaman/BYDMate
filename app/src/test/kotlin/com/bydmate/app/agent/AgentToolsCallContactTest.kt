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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // П7 origin-based defense: kind="call" is always dangerous, so the single match now
    // goes through confirmDangerous (overlay confirm) instead of dispatching immediately.
    // The phone number must never surface in the tool's own response.
    @Test fun single_match_confirms_before_dispatching_and_hides_phone_number() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } returns listOf(ContactLookup.Match("Мама", phone))
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val t = tools()
        t.confirmScope = CoroutineScope(Dispatchers.Unconfined)
        var gateInvoked = false
        t.confirmGate = { _, _, _, onConfirm, _ -> gateInvoked = true; onConfirm(); true }
        val raw = t.execute(call("call_contact", """{"name":"Мама"}"""))
        val out = JSONObject(raw)
        assertTrue(gateInvoked)
        assertEquals("ожидает подтверждения на экране", out.getString("status"))
        assertFalse(raw.contains(phone))
        coVerify {
            dispatcher.dispatch(match {
                it.kind == "call" &&
                    JSONObject(it.payload!!).getString("phone") == phone &&
                    JSONObject(it.payload!!).getBoolean("autoDial")
            }, null)
        }
    }

    // Fail-closed: when the overlay cannot be shown (no SYSTEM_ALERT_WINDOW), the call
    // is refused, never dialed silently, and the phone number still never leaks.
    @Test fun single_match_fail_closed_when_overlay_cannot_show() = runTest {
        every { contactLookup.hasPermission() } returns true
        coEvery { contactLookup.findByName("Мама") } returns listOf(ContactLookup.Match("Мама", phone))
        every { gate.vehicleSnapshot() } returns null
        val t = tools()
        t.confirmGate = { _, _, _, _, _ -> false }
        val raw = t.execute(call("call_contact", """{"name":"Мама"}"""))
        assertFalse(JSONObject(raw).getBoolean("ok"))
        assertFalse(raw.contains(phone))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
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
