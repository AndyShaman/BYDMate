package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.dao.TripSummary
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryState
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

class AgentToolsReadTest {

    private val gate = mockk<VoiceGate>()
    private val battery = mockk<BatteryStateRepository>()
    private val range = mockk<RangeCalculator>()
    private val tripDao = mockk<TripDao>()
    private val chargeDao = mockk<ChargeDao>()
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
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
    ).also { it.nowMs = { 1_000_000_000_000L } }

    @Test fun schemas_lists_read_tools() = runTest {
        coEvery { ruleDao.getEnabled() } returns emptyList()
        coEvery { settingsRepository.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        val names = mutableListOf<String>()
        val arr = tools().schemas()
        for (i in 0 until arr.length()) {
            names += arr.getJSONObject(i).getJSONObject("function").getString("name")
        }
        assertTrue(names.containsAll(listOf("get_vehicle_state", "query_trips", "query_charges")))
    }

    @Test fun vehicle_state_null_snapshot_reports_error() = runTest {
        every { gate.vehicleSnapshot() } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertTrue(out.has("error"))
    }

    @Test fun vehicle_state_maps_fields() = runTest {
        every { gate.vehicleSnapshot() } returns snapshot(
            soc = 80, speed = 0, gear = 1, seatHeatDriver = 2, totalElec = 1234.5,
        )
        coEvery { battery.refresh() } returns
            BatteryState(80f, 12.5f, 100f, null, null, autoserviceAvailable = true)
        coEvery { range.estimate(80, 1234.5) } returns 321.4
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertEquals(80, out.getInt("soc_percent"))
        assertEquals("P", out.getString("gear"))
        assertEquals(2, out.getInt("seat_heat_driver_level"))
        assertEquals(321, out.getInt("range_km"))
        assertEquals(100.0, out.getDouble("soh_percent"), 0.01)
    }

    @Test fun vehicle_state_survives_battery_refresh_failure() = runTest {
        every { gate.vehicleSnapshot() } returns snapshot(soc = 50)
        coEvery { battery.refresh() } throws RuntimeException("daemon down")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertEquals(50, out.getInt("soc_percent"))
        assertTrue(!out.has("soh_percent"))
    }

    @Test fun vehicle_state_includes_gps_and_place_inside_radius() = runTest {
        val t = tools()
        t.locationProvider = { 55.751244 to 37.618423 }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дом", lat = 55.751244, lon = 37.618423, radiusM = 100),
        )
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertEquals(55.751244, out.getDouble("gps_lat"), 0.0001)
        assertEquals(37.618423, out.getDouble("gps_lon"), 0.0001)
        assertEquals("Дом", out.getString("place"))
    }

    @Test fun vehicle_state_gps_without_places_omits_place() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertTrue(out.has("gps_lat"))
        assertTrue(out.has("gps_lon"))
        assertTrue(!out.has("place"))
    }

    @Test fun vehicle_state_without_gps_omits_gps_and_place() = runTest {
        val t = tools()
        t.locationProvider = { null }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertTrue(!out.has("gps_lat"))
        assertTrue(!out.has("gps_lon"))
        assertTrue(!out.has("place"))
    }

    @Test fun vehicle_state_outside_place_radius_omits_place() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Далеко", lat = 56.0, lon = 38.0, radiusM = 50),
        )
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertTrue(out.has("gps_lat"))
        assertTrue(!out.has("place"))
    }

    @Test fun vehicle_state_survives_place_lookup_failure() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        coEvery { places.getAllSnapshot() } throws RuntimeException("db down")
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertEquals(80, out.getInt("soc_percent"))
        assertTrue(out.has("gps_lat"))
        assertTrue(out.has("gps_lon"))
        assertTrue(!out.has("place"))
    }

    // Honest sensors: absent door data must be ABSENT, not "0 doors open" — the LLM
    // reads a missing key as unknown, but a literal 0 as "all doors closed".
    @Test fun doors_open_omitted_when_all_door_sensors_unknown() = runTest {
        val t = tools()
        t.locationProvider = { null }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertTrue(!out.has("doors_open"))
    }

    @Test fun doors_open_counts_when_sensors_present() = runTest {
        val t = tools()
        t.locationProvider = { null }
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80, doorFL = 1, doorFR = 0)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(t.execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertEquals(1, out.getInt("doors_open"))
    }

    // Deterministic low-pressure flag: any wheel below TIRE_WARN_MIN_KPA (210) must
    // surface a Russian warning the model reliably voices.
    @Test fun vehicle_state_flags_low_tire_pressure() = runTest {
        every { gate.vehicleSnapshot() } returns snapshot(
            soc = 80, tirePressFL = 250, tirePressFR = 190, tirePressRL = 255, tirePressRR = 252)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertTrue(out.has("tire_pressure_warning"))
        assertEquals(190, out.getInt("tire_press_fr_kpa"))
    }

    @Test fun vehicle_state_no_tire_warning_when_normal() = runTest {
        every { gate.vehicleSnapshot() } returns snapshot(
            soc = 80, tirePressFL = 250, tirePressFR = 248, tirePressRL = 255, tirePressRR = 252)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertFalse(out.has("tire_pressure_warning"))
    }

    @Test fun vehicle_state_no_tire_warning_when_unknown() = runTest {
        every { gate.vehicleSnapshot() } returns snapshot(soc = 80)
        coEvery { battery.refresh() } throws RuntimeException("n/a")
        coEvery { range.estimate(any(), any()) } returns null
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{}")))
        assertFalse(out.has("tire_pressure_warning"))
    }

    // (д) get_weather without GPS and without city -> error-JSON, no HTTP call.
    @Test fun get_weather_without_gps_and_city_reports_error() = runTest {
        val t = tools()
        t.locationProvider = { null }
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", "{}")))
        assertEquals("нет GPS и не указан город", out.getString("error"))
        coVerify(exactly = 0) { weather.forecast(any(), any()) }
        coVerify(exactly = 0) { weather.geocode(any()) }
    }

    @Test fun get_weather_uses_gps_when_no_city_given() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.forecast(55.0, 37.0) } returns Result.success("""{"now":{}}""")
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", "{}")))
        assertTrue(out.has("now"))
    }

    @Test fun get_weather_geocodes_explicit_city() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.geocode("Сочи") } returns Result.success(WeatherClient.GeoPoint(43.6, 39.7, "Сочи"))
        coEvery { weather.forecast(43.6, 39.7) } returns Result.success("""{"now":{}}""")
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", """{"city":"Сочи"}""")))
        assertTrue(out.has("now"))
    }

    // (a) City path names the place from the geocoder result.
    @Test fun get_weather_city_path_reports_geocoded_name() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.geocode("Краснодар") } returns
            Result.success(WeatherClient.GeoPoint(45.035, 38.975, "Краснодар"))
        coEvery { weather.forecast(45.035, 38.975) } returns Result.success("""{"now":{}}""")
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", """{"city":"Краснодар"}""")))
        assertEquals("Краснодар", out.getString("location"))
    }

    // (b) GPS path inside a saved place names that place.
    @Test fun get_weather_gps_path_names_saved_place() = runTest {
        val t = tools()
        t.locationProvider = { 55.751244 to 37.618423 }
        coEvery { weather.forecast(55.751244, 37.618423) } returns Result.success("""{"now":{}}""")
        coEvery { places.getAllSnapshot() } returns listOf(
            PlaceEntity(name = "Дом", lat = 55.751244, lon = 37.618423, radiusM = 100),
        )
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", "{}")))
        assertEquals("Дом", out.getString("location"))
    }

    // (c) GPS path outside any saved place omits the location key entirely.
    @Test fun get_weather_gps_path_without_place_omits_location() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.forecast(55.0, 37.0) } returns Result.success("""{"now":{}}""")
        coEvery { places.getAllSnapshot() } returns emptyList()
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", "{}")))
        assertTrue(!out.has("location"))
    }

    // Pin: raw English exception messages ("timeout") must never leak into the error JSON.
    @Test fun get_weather_forecast_failure_reports_russian_error() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.forecast(55.0, 37.0) } returns Result.failure(RuntimeException("timeout"))
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", "{}")))
        assertEquals("погода недоступна", out.getString("error"))
    }

    // Pin: WeatherClient.UserError is the one failure whose Russian message passes through.
    @Test fun get_weather_city_not_found_passes_russian_message_through() = runTest {
        val t = tools()
        t.locationProvider = { 55.0 to 37.0 }
        coEvery { weather.geocode("Хзгородск") } returns
            Result.failure(WeatherClient.UserError("город не найден"))
        val out = JSONObject(t.execute(AgentToolCall("1", "get_weather", """{"city":"Хзгородск"}""")))
        assertEquals("город не найден", out.getString("error"))
    }

    @Test fun query_trips_computes_avg_consumption() = runTest {
        coEvery { tripDao.getPeriodSummary(any(), any()) } returns
            TripSummary(totalKm = 100.0, totalKwh = 18.0, tripCount = 3, totalCost = 90.0)
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "query_trips", """{"period":"week"}""")))
        assertEquals(3, out.getInt("trips"))
        assertEquals(18.0, out.getDouble("avg_kwh_per_100km"), 0.01)
    }

    @Test fun query_trips_bad_period_reports_error() = runTest {
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "query_trips", """{"period":"year"}""")))
        assertEquals("укажи period (day/week/month) или даты from и to в формате ГГГГ-ММ-ДД", out.getString("error"))
    }

    @Test fun query_charges_includes_ac_dc_split() = runTest {
        coEvery { chargeDao.getPeriodSummary(any(), any()) } returns
            ChargeSummary(sessionCount = 2, totalKwh = 40.0, totalCost = 200.0)
        coEvery { chargeDao.getCompletedSince(any()) } returns emptyList()
        val out = JSONObject(tools().execute(
            AgentToolCall("1", "query_charges", """{"period":"month"}""")))
        assertEquals(2, out.getInt("sessions"))
        assertEquals(0.0, out.getDouble("ac_kwh"), 0.01)
    }

    @Test fun unknown_tool_reports_error() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "nope", "{}")))
        assertEquals("неизвестный инструмент nope", out.getString("error"))
    }

    // Pin: malformed tool-call JSON must report a fixed Russian error, never leak the
    // raw parser exception (which would be English).
    @Test fun malformed_arguments_json_reports_russian_error() = runTest {
        val out = JSONObject(tools().execute(AgentToolCall("1", "get_vehicle_state", "{not json")))
        assertEquals("некорректные аргументы", out.getString("error"))
    }

    companion object {
        /** Minimal DiParsData with every field null except the named overrides. */
        internal fun snapshot(
            soc: Int? = null, speed: Int? = null, gear: Int? = null,
            seatHeatDriver: Int? = null, totalElec: Double? = null,
            doorFL: Int? = null, doorFR: Int? = null,
            tirePressFL: Int? = null, tirePressFR: Int? = null,
            tirePressRL: Int? = null, tirePressRR: Int? = null,
        ) = com.bydmate.app.data.remote.DiParsData(
            soc = soc, speed = speed, mileage = null, power = null, chargeGunState = null,
            maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = null,
            batteryCapacityKwh = null, totalElecConsumption = totalElec, voltage12v = null,
            maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null,
            gear = gear, powerState = null, insideTemp = null, acStatus = null, acTemp = null,
            fanLevel = null, acCirc = null, doorFL = doorFL, doorFR = doorFR, doorRL = null,
            doorRR = null, windowFL = null, windowFR = null, windowRL = null, windowRR = null,
            sunroof = null, trunk = null, hood = null, seatbeltFL = null, lockFL = null,
            tirePressFL = tirePressFL, tirePressFR = tirePressFR,
            tirePressRL = tirePressRL, tirePressRR = tirePressRR,
            driveMode = null, workMode = null, autoPark = null, rain = null,
            lightLow = null, drl = null, seatHeatDriver = seatHeatDriver,
        )
    }
}
