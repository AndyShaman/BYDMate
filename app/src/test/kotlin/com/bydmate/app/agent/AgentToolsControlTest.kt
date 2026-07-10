package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.RuleEntity
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
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsControlTest {

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

    // SAFETY: null snapshot + window-open command must never reach the dispatcher.
    @Test fun window_open_with_null_snapshot_fails_closed() = runTest {
        every { gate.vehicleSnapshot() } returns null
        val out = JSONObject(tools().execute(
            call("vehicle_control", """{"command":"车窗全开"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun non_window_command_with_null_snapshot_dispatches() = runTest {
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            call("vehicle_control", """{"command":"关闭空调"}""")))
        assertTrue(out.getBoolean("ok"))
    }

    @Test fun dispatcher_block_reason_propagates() = runTest {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(speed = 100)
        coEvery { dispatcher.dispatch(any(), any()) } returns
            DispatchResult(false, "Заблокировано: скорость выше 80 км/ч")
        val out = JSONObject(tools().execute(
            call("vehicle_control", """{"command":"车窗全开"}""")))
        assertEquals("Заблокировано: скорость выше 80 км/ч", out.getString("error"))
    }

    @Test fun vehicle_control_dispatches_kind_param() = runTest {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(speed = 0)
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        tools().execute(call("vehicle_control", """{"command":"设置温度22"}"""))
        coVerify { dispatcher.dispatch(match { it.kind == "param" && it.command == "设置温度22" }, any()) }
    }

    @Test fun media_volume_passes_op_as_payload() = runTest {
        every { gate.vehicleSnapshot() } returns null
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        tools().execute(call("media_volume", """{"op":"+2"}"""))
        coVerify { dispatcher.dispatch(match { it.kind == "media_volume" && it.payload == "+2" }, null) }
    }

    @Test fun run_automation_matches_name_case_insensitive() = runTest {
        every { gate.vehicleSnapshot() } returns null
        coEvery { ruleDao.getEnabled() } returns listOf(
            RuleEntity(id = 7, name = "Проветрить", triggers = "[]", actions = "[]"))
        coEvery { engine.fireVoiceRule(7, null) } returns VoiceFireResult.Fired(true)
        val out = JSONObject(tools().execute(
            call("run_automation", """{"name":"проветрить"}""")))
        assertTrue(out.getBoolean("ok"))
    }

    @Test fun run_automation_park_required_maps_to_error() = runTest {
        every { gate.vehicleSnapshot() } returns null
        coEvery { ruleDao.getEnabled() } returns listOf(
            RuleEntity(id = 7, name = "Фрунк", triggers = "[]", actions = "[]"))
        coEvery { engine.fireVoiceRule(7, null) } returns VoiceFireResult.ParkRequired
        val out = JSONObject(tools().execute(
            call("run_automation", """{"name":"Фрунк"}""")))
        assertTrue(out.has("error"))
    }

    @Test fun run_automation_unknown_name_reports_error() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        val out = JSONObject(tools().execute(
            call("run_automation", """{"name":"нет такой"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { engine.fireVoiceRule(any(), any()) }
    }

    @Test fun schemas_embed_rule_names_enum() = runTest {
        coEvery { ruleDao.getEnabled() } returns listOf(
            RuleEntity(id = 1, name = "Ночной режим", triggers = "[]", actions = "[]"))
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        val arr = tools().schemas()
        var found = false
        for (i in 0 until arr.length()) {
            val fn = arr.getJSONObject(i).getJSONObject("function")
            if (fn.getString("name") == "run_automation") {
                val enum = fn.getJSONObject("parameters").getJSONObject("properties")
                    .getJSONObject("name").getJSONArray("enum")
                assertEquals("Ночной режим", enum.getString(0))
                found = true
            }
        }
        assertTrue(found)
    }

    @Test fun vehicle_control_schema_has_readable_enum_no_chinese() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        val arr = tools().schemas()
        var found = false
        for (i in 0 until arr.length()) {
            val fn = arr.getJSONObject(i).getJSONObject("function")
            if (fn.getString("name") == "vehicle_control") {
                val commandProp = fn.getJSONObject("parameters").getJSONObject("properties").getJSONObject("command")
                val enum = commandProp.getJSONArray("enum")
                val ids = (0 until enum.length()).map { enum.getString(it) }
                assertTrue(ids.contains("windows_close_all"))
                assertTrue(ids.none { it.contains("车窗") })
                assertTrue(!fn.getString("description").contains("车窗"))
                found = true
            }
        }
        assertTrue(found)
    }

    @Test fun vehicle_control_dispatches_readable_id() = runTest {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(speed = 0)
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        tools().execute(call("vehicle_control", """{"command":"windows_close_all"}"""))
        coVerify { dispatcher.dispatch(match { it.kind == "param" && it.command == "车窗关闭" }, any()) }
    }

    @Test fun vehicle_control_unknown_command_reports_russian_error_no_chinese() = runTest {
        every { gate.vehicleSnapshot() } returns null
        val out = JSONObject(tools().execute(
            call("vehicle_control", """{"command":"nonsense_command"}""")))
        val error = out.getString("error")
        assertTrue(error.contains("nonsense_command"))
        assertTrue(!error.any { it.code > 0x2E80 })
        coVerify(exactly = 0) { dispatcher.dispatch(any(), any()) }
    }

    @Test fun vehicle_control_raw_chinese_still_dispatches_for_compat() = runTest {
        every { gate.vehicleSnapshot() } returns AgentToolsReadTest.snapshot(speed = 0)
        coEvery { dispatcher.dispatch(any(), any()) } returns DispatchResult(true)
        val out = JSONObject(tools().execute(
            call("vehicle_control", """{"command":"车窗关闭"}""")))
        assertTrue(out.getBoolean("ok"))
        coVerify { dispatcher.dispatch(match { it.command == "车窗关闭" }, any()) }
    }
}
