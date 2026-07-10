package com.bydmate.app.agent

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.RuleEntity
import com.bydmate.app.data.local.entity.TriggerDef
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
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Agent-created enum triggers must persist the AutomationEngine's numeric code
 * (e.g. Gear Drive = "4"), NOT the human label the LLM sees in get_vehicle_state
 * ("D"). The engine reads value.toDoubleOrNull(); a raw label parses to null and the
 * predicate is false forever, so the rule is visible + enabled but never fires.
 *
 * Robolectric because the label->code mapping resolves enum string resources; the
 * plain relaxed-mock Context in AgentToolsAutomationTest returns empty strings.
 * Only locale-invariant tokens are asserted (Gear P/R/N/D, DriveMode ECO) so the
 * app's default "en" locale under Robolectric cannot make this flaky.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AgentToolsAutomationEnumTest {

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

    private val context = ApplicationProvider.getApplicationContext<Application>()

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

    private fun createArgs(trigger: String) =
        """{"name":"Тест","trigger":$trigger,"actions":[{"kind":"param","command_id":"windows_close_all"}]}"""

    /** Runs create_automation for [trigger], asserts success, returns the persisted TriggerDef. */
    private suspend fun savedTrigger(trigger: String): TriggerDef {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val slot = slot<RuleEntity>()
        coEvery { ruleDao.insert(capture(slot)) } returns 1L
        val out = JSONObject(tools().execute(call("create_automation", createArgs(trigger))))
        assertTrue("expected ok, got $out", out.optBoolean("ok"))
        return TriggerDef.listFromJson(slot.captured.triggers).first()
    }

    @Test fun gear_label_D_is_normalized_to_engine_code_4() = runTest {
        val t = savedTrigger("""{"kind":"param","param":"Gear","operator":"==","value":"D"}""")
        assertEquals("4", t.value)
    }

    @Test fun gear_lowercase_label_is_normalized_case_insensitively() = runTest {
        val t = savedTrigger("""{"kind":"param","param":"Gear","operator":"==","value":"d"}""")
        assertEquals("4", t.value)
    }

    @Test fun gear_numeric_code_is_kept() = runTest {
        val t = savedTrigger("""{"kind":"param","param":"Gear","operator":"==","value":"4"}""")
        assertEquals("4", t.value)
    }

    @Test fun drive_mode_label_proves_fix_is_generic_not_gear_only() = runTest {
        val t = savedTrigger("""{"kind":"param","param":"DriveMode","operator":"==","value":"ECO"}""")
        assertEquals("1", t.value)
    }

    @Test fun lockfl_runtime_code_2_locked_is_accepted() = runTest {
        // DiParsData runtime contract: lockFL 1=unlocked, 2=locked; the engine compares
        // the raw value. The catalog codes must match, so "2" (locked) is a valid code.
        // The stale 0/1 catalog rejected "2", silently killing every lock automation.
        val t = savedTrigger("""{"kind":"param","param":"LockFL","operator":"==","value":"2"}""")
        assertEquals("2", t.value)
    }

    @Test fun numeric_param_value_passes_through_unchanged() = runTest {
        val t = savedTrigger("""{"kind":"param","param":"Speed","operator":">","value":"60"}""")
        assertEquals("60", t.value)
    }

    @Test fun unknown_enum_value_is_rejected_and_nothing_is_inserted() = runTest {
        coEvery { ruleDao.getAllList() } returns emptyList()
        val out = JSONObject(tools().execute(call("create_automation",
            createArgs("""{"kind":"param","param":"Gear","operator":"==","value":"Zzz"}"""))))
        assertTrue("expected error, got $out", out.has("error"))
        coVerify(exactly = 0) { ruleDao.insert(any()) }
    }
}
