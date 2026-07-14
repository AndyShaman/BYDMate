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
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AgentToolsQueryDatesTest {

    private val gate = mockk<VoiceGate>(relaxed = true)
    private val battery = mockk<BatteryStateRepository>(relaxed = true)
    private val range = mockk<RangeCalculator>(relaxed = true)
    private val tripDao = mockk<TripDao>(relaxed = true)
    private val chargeDao = mockk<ChargeDao>(relaxed = true)
    private val dispatcher = mockk<ActionDispatcher>(relaxed = true)
    private val ruleDao = mockk<RuleDao>(relaxed = true)
    private val engine = mockk<AutomationEngine>(relaxed = true)
    private val places = mockk<PlaceRepository>(relaxed = true)
    private val weather = mockk<WeatherClient>(relaxed = true)
    private val exa = mockk<ExaSearchClient>(relaxed = true)
    private val openRouterClient = mockk<OpenRouterClient>(relaxed = true)
    private val settingsRepository = mockk<SettingsRepository>(relaxed = true)
    private val contactLookup = mockk<ContactLookup>(relaxed = true)

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

    private fun emptySummary() = TripSummary(totalKm = 0.0, totalKwh = 0.0)

    private fun call(name: String, args: String) = AgentToolCall("1", name, args)

    @Test fun query_trips_with_from_to_passes_bounds_to_dao() = runTest {
        // 2026-06-01 00:00 local .. 2026-06-30 last ms local (to day included by extending to end-of-day: + DAY_MS - 1)
        val slotFrom = slot<Long>(); val slotTo = slot<Long>()
        coEvery { tripDao.getPeriodSummary(capture(slotFrom), capture(slotTo)) } returns emptySummary()
        tools().execute(call("query_trips", """{"from":"2026-06-01","to":"2026-06-30"}"""))
        val cal = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 1) }
        assertEquals(cal.timeInMillis, slotFrom.captured)
        val calEnd = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 30) }
        assertEquals(calEnd.timeInMillis + 24L * 3600_000 - 1, slotTo.captured)
    }

    @Test fun query_trips_single_date_covers_that_day() = runTest {
        val slotFrom = slot<Long>(); val slotTo = slot<Long>()
        coEvery { tripDao.getPeriodSummary(capture(slotFrom), capture(slotTo)) } returns emptySummary()
        tools().execute(call("query_trips", """{"from":"2026-06-01"}"""))
        val cal = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 1) }
        assertEquals(cal.timeInMillis, slotFrom.captured)
        assertEquals(cal.timeInMillis + 24L * 3600_000 - 1, slotTo.captured)
    }

    @Test fun query_trips_bad_date_returns_russian_error() = runTest {
        val res = tools().execute(call("query_trips", """{"from":"июнь"}"""))
        assertTrue(res.contains("ГГГГ-ММ-ДД"))
    }

    @Test fun query_trips_date_with_trailing_garbage_is_rejected() = runTest {
        // SimpleDateFormat.parse accepts a valid prefix and ignores the tail, so without the
        // explicit shape check this would silently succeed as 2026-06-01.
        val res = tools().execute(call("query_trips", """{"from":"2026-06-01xyz"}"""))
        assertTrue(res.contains("ГГГГ-ММ-ДД"))
    }

    @Test fun query_trips_period_still_works_without_dates() = runTest {
        val slotFrom = slot<Long>(); val slotTo = slot<Long>()
        coEvery { tripDao.getPeriodSummary(capture(slotFrom), capture(slotTo)) } returns emptySummary()
        val t = tools()
        t.execute(call("query_trips", """{"period":"week"}"""))
        assertEquals(1_000_000_000_000L - 7 * 24L * 3600_000, slotFrom.captured)
        assertEquals(1_000_000_000_000L, slotTo.captured)
    }

    @Test fun query_trips_upper_bound_excludes_next_day_midnight() = runTest {
        val slotFrom = slot<Long>(); val slotTo = slot<Long>()
        coEvery { tripDao.getPeriodSummary(capture(slotFrom), capture(slotTo)) } returns emptySummary()
        tools().execute(call("query_trips", """{"from":"2026-06-01","to":"2026-06-30"}"""))
        val nextMidnight = Calendar.getInstance().apply { clear(); set(2026, Calendar.JULY, 1) }.timeInMillis
        // DAO compares start_ts <= :to, so :to must stop 1 ms before next-day midnight.
        assertEquals(nextMidnight - 1, slotTo.captured)
    }

    @Test fun query_charges_split_is_bounded_by_to() = runTest {
        val calEnd = Calendar.getInstance().apply { clear(); set(2026, Calendar.JUNE, 30) }
        val to = calEnd.timeInMillis + 24L * 3600_000
        // Session recorded after the requested "to" boundary must not enter the AC/DC split.
        val outOfRange = ChargeEntity(startTs = to + 60_000, type = "AC", kwhCharged = 10.0, cost = 100.0)
        coEvery { chargeDao.getPeriodSummary(any(), any()) } returns
            ChargeSummary(sessionCount = 1, totalKwh = 10.0, totalCost = 100.0)
        coEvery { chargeDao.getCompletedSince(any()) } returns listOf(outOfRange)
        val out = JSONObject(tools().execute(
            call("query_charges", """{"from":"2026-06-01","to":"2026-06-30"}""")))
        assertEquals(0.0, out.getDouble("ac_kwh"), 0.01)
        assertEquals(0, out.getInt("ac_sessions"))
    }
}
