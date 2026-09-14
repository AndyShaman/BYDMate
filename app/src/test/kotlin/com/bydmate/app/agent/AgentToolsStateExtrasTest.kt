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
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wave 4: readings the «Техника» screen already shows but the agent could not see — the motor
 * split, the BMS limits, tyre temperatures, the blow direction it can already set, and the
 * blind-spot camera setting the driver asks about by voice.
 */
class AgentToolsStateExtrasTest {

    private val gate = mockk<VoiceGate>().also { every { it.snapshotAgeMs() } returns null }
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val context = mockk<Context>(relaxed = true)

    private fun tools() = AgentTools(
        gate, battery, range, mockk<TripDao>(), mockk<ChargeDao>(),
        mockk<ActionDispatcher>(relaxed = true), mockk<RuleDao>(), mockk<AutomationEngine>(),
        mockk<PlaceRepository>(), mockk<WeatherClient>(), mockk<ExaSearchClient>(),
        mockk<OpenRouterClient>(), mockk<SettingsRepository>(), mockk<ContactLookup>(), context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    )

    private suspend fun state(data: com.bydmate.app.data.remote.DiParsData): JSONObject {
        every { gate.vehicleSnapshot() } returns data
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimateDetailed(any(), any(), any()) } returns null
        return JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
    }

    private fun snapshot() = AgentToolsReadTest.snapshot(soc = 60)

    @Test fun motor_split_is_reported_as_percentages() = runTest {
        val out = state(snapshot().copy(motorCurrentFront = 7.9f, motorCurrentRear = 45.3f))
        assertEquals(15, out.getInt("motor_share_front_pct"))
        assertEquals(85, out.getInt("motor_share_rear_pct"))
    }

    @Test fun a_standing_car_gets_no_made_up_split() = runTest {
        val out = state(snapshot().copy(motorCurrentFront = 0.1f, motorCurrentRear = 0.1f))
        assertFalse(out.has("motor_share_front_pct"))
        assertTrue(out.getString("motor_share").contains("не тянут"))
    }

    @Test fun a_single_motor_car_reports_no_split_at_all() = runTest {
        val out = state(snapshot().copy(motorCurrentFront = null, motorCurrentRear = 45.3f))
        assertFalse(out.has("motor_share_front_pct"))
        assertFalse(out.has("motor_share"))
    }

    @Test fun bms_limits_reach_the_model() = runTest {
        val out = state(snapshot().copy(bmsMaxChargeKw = 88.5, bmsMaxDischargeKw = 210))
        assertEquals(88.5, out.getDouble("bms_max_charge_kw"), 0.01)
        assertEquals(210, out.getInt("bms_max_discharge_kw"))
    }

    @Test fun tyre_temperatures_reach_the_model() = runTest {
        val out = state(snapshot().copy(
            tyreTempFL = 31, tyreTempFR = 32, tyreTempRL = 30, tyreTempRR = 45))
        assertEquals(31, out.getInt("tire_temp_fl_c"))
        assertEquals(45, out.getInt("tire_temp_rr_c"))
    }

    // The agent can set the blow direction since 5e96ad5d; reading it back uses the same enum.
    @Test fun blow_direction_comes_back_as_a_russian_label() = runTest {
        assertEquals("в ноги", state(snapshot().copy(acWindMode = 3)).getString("ac_wind_mode"))
        assertEquals("на стекло", state(snapshot().copy(acWindMode = 5)).getString("ac_wind_mode"))
    }

    @Test fun an_unknown_blow_direction_is_omitted() = runTest {
        assertFalse(state(snapshot().copy(acWindMode = null)).has("ac_wind_mode"))
    }

    // The relaxed Context hands out a relaxed SharedPreferences, i.e. the feature reads as off.
    @Test fun blind_spot_camera_state_is_visible() = runTest {
        val out = state(snapshot())
        assertFalse(out.getBoolean("blind_spot_cameras_enabled"))
        assertFalse("the speed threshold is meaningless while the feature is off",
            out.has("blind_spot_cameras_from_kmh"))
    }
}
