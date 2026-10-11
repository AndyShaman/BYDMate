package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.VehicleKindMonitor
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.domain.calculator.RangeEstimate
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** get_vehicle_state on a DM-i: the car's own tank and ranges; on an EV the answer is as before. */
class AgentToolsHybridStateTest {

    private val gate = mockk<VoiceGate>().also { every { it.snapshotAgeMs() } returns null }
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val context = mockk<Context>(relaxed = true)

    private fun tools(kind: VehicleKind?) = AgentTools(
        gate, battery, range, mockk<TripDao>(), mockk<ChargeDao>(),
        mockk<ActionDispatcher>(relaxed = true), mockk<RuleDao>(), mockk<AutomationEngine>(),
        mockk<PlaceRepository>(), mockk<WeatherClient>(), mockk<ExaSearchClient>(),
        mockk<OpenRouterClient>(), mockk<SettingsRepository>(), mockk<ContactLookup>(), context,
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true),
        mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also { t ->
        if (kind != null) {
            t.injectVehicleKind(mockk { every { this@mockk.kind } returns MutableStateFlow(kind) })
        }
    }

    private suspend fun state(kind: VehicleKind?, data: DiParsData): String {
        every { gate.vehicleSnapshot() } returns data
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimateDetailed(any(), any(), any()) } returns
            RangeEstimate(rangeKm = 300.4, avgKwhPer100 = 18.0, capacityKwh = 72.9, remainingKwh = 43.7)
        return tools(kind).execute(AgentToolCall("1", "get_vehicle_state", "{}"))
    }

    private fun hybridSnapshot() = AgentToolsReadTest.snapshot(soc = 60).copy(
        fuelPercent = 64, fuelRangeKm = 512, evRangeKm = 46, engineRpm = 1450,
        mileageHevKm = 3120, mileageEvKm = 8400,
    )

    @Test fun an_ev_answer_is_the_same_as_without_the_car_type() = runTest {
        val before = state(null, AgentToolsReadTest.snapshot(soc = 60))
        assertEquals(before, state(VehicleKind.EV, AgentToolsReadTest.snapshot(soc = 60)))
        assertEquals(before, state(VehicleKind.UNKNOWN, AgentToolsReadTest.snapshot(soc = 60)))
        assertEquals(300, JSONObject(before).getInt("range_km"))
    }

    @Test fun an_ev_ignores_fuel_fields_even_when_present() = runTest {
        val out = JSONObject(state(VehicleKind.EV, hybridSnapshot()))
        assertFalse(out.has("fuel_percent"))
        assertFalse(out.has("ev_range_km"))
        assertEquals(300, out.getInt("range_km"))
    }

    @Test fun a_hybrid_gets_tank_ranges_and_engine_from_the_car() = runTest {
        val out = JSONObject(state(VehicleKind.HYBRID, hybridSnapshot()))
        assertEquals(64, out.getInt("fuel_percent"))
        assertEquals(512, out.getInt("fuel_range_km"))
        assertEquals(46, out.getInt("ev_range_km"))
        assertEquals(1450, out.getInt("engine_rpm"))
        assertEquals(3120, out.getInt("mileage_hev_km"))
        assertEquals(8400, out.getInt("mileage_ev_km"))
        assertEquals("range is the car's electric range, not SOC x capacity", 46, out.getInt("range_km"))
    }

    @Test fun a_hybrid_without_an_electric_range_reports_no_range() = runTest {
        val out = JSONObject(state(VehicleKind.HYBRID, hybridSnapshot().copy(evRangeKm = null)))
        assertFalse(out.has("range_km"))
        assertFalse(out.has("ev_range_km"))
        assertEquals(512, out.getInt("fuel_range_km"))
    }
}
