package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.SettingsRepository.Currency
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolsAddChargeTest {

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

    @Test fun add_charge_soc_path_inserts_derived_kwh_and_cost() = runTest {
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { settingsRepository.getDcTariff() } returns 17.0
        coEvery { settingsRepository.getCurrency() } returns Currency("RUB", "₽")
        val slot = slot<ChargeEntity>()
        coEvery { chargeDao.insert(capture(slot)) } returns 1L

        val out = JSONObject(tools().execute(call("add_charge",
            """{"type":"DC","soc_start":30,"soc_end":80}""")))

        assertTrue(out.getBoolean("ok"))
        val e = slot.captured
        assertEquals("DC", e.type)
        assertEquals(30, e.socStart)
        assertEquals(80, e.socEnd)
        assertEquals(36.45, e.kwhCharged!!, 0.01)      // (80-30)/100*72.9
        assertEquals(36.45, e.kwhChargedSoc!!, 0.01)
        assertEquals(619.65, e.cost!!, 0.5)            // 36.45*17.0
        assertEquals("manual", e.detectionSource)
        assertEquals(36.45, out.getDouble("kwh"), 0.01)
    }

    @Test fun add_charge_kwh_path_uses_home_tariff_for_ac() = runTest {
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { settingsRepository.getHomeTariff() } returns 5.0
        coEvery { settingsRepository.getCurrency() } returns Currency("RUB", "₽")
        val slot = slot<ChargeEntity>()
        coEvery { chargeDao.insert(capture(slot)) } returns 1L

        val out = JSONObject(tools().execute(call("add_charge", """{"kwh":20}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals("AC", slot.captured.type)
        assertEquals(20.0, slot.captured.kwhCharged!!, 0.001)
        assertEquals(100.0, slot.captured.cost!!, 0.001)
        assertNull(slot.captured.socStart)
    }

    @Test fun add_charge_explicit_tariff_overrides_settings() = runTest {
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { settingsRepository.getCurrency() } returns Currency("RUB", "₽")
        val slot = slot<ChargeEntity>()
        coEvery { chargeDao.insert(capture(slot)) } returns 1L

        val out = JSONObject(tools().execute(call("add_charge", """{"kwh":10,"tariff":25}""")))

        assertTrue(out.getBoolean("ok"))
        assertEquals(250.0, slot.captured.cost!!, 0.001)
        coVerify(exactly = 0) { settingsRepository.getHomeTariff() }
    }

    @Test fun add_charge_backdated_via_date_arg() = runTest {
        coEvery { settingsRepository.getBatteryCapacity() } returns 72.9
        coEvery { settingsRepository.getHomeTariff() } returns 5.0
        coEvery { settingsRepository.getCurrency() } returns Currency("RUB", "₽")
        val slot = slot<ChargeEntity>()
        coEvery { chargeDao.insert(capture(slot)) } returns 1L

        JSONObject(tools().execute(call("add_charge", """{"kwh":5,"date":"2026-07-01"}""")))

        val cal = java.util.Calendar.getInstance().apply { timeInMillis = slot.captured.startTs }
        assertEquals(2026, cal.get(java.util.Calendar.YEAR))
        assertEquals(java.util.Calendar.JULY, cal.get(java.util.Calendar.MONTH))
        assertEquals(1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        assertEquals(slot.captured.startTs + 3_600_000L, slot.captured.endTs)
    }

    @Test fun add_charge_rejects_missing_amount() = runTest {
        val out = JSONObject(tools().execute(call("add_charge", """{"type":"AC"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { chargeDao.insert(any()) }
    }

    @Test fun add_charge_rejects_bad_soc_range() = runTest {
        val out = JSONObject(tools().execute(call("add_charge",
            """{"soc_start":80,"soc_end":30}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { chargeDao.insert(any()) }
    }

    @Test fun add_charge_rejects_bad_date() = runTest {
        val out = JSONObject(tools().execute(call("add_charge",
            """{"kwh":5,"date":"01.07.2026"}""")))
        assertTrue(out.has("error"))
        coVerify(exactly = 0) { chargeDao.insert(any()) }
    }
}
