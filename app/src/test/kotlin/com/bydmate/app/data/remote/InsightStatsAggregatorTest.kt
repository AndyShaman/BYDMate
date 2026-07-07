package com.bydmate.app.data.remote

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.IdleDrainEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class InsightStatsAggregatorTest {

    @Test
    fun `night drain counts only sessions started at night`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.JUNE, 10, 23, 30, 0)
        val nightStart = cal.timeInMillis
        cal.set(2026, Calendar.JUNE, 10, 14, 0, 0)
        val dayStart = cal.timeInMillis

        val drains = listOf(
            IdleDrainEntity(startTs = nightStart, kwhConsumed = 2.0),
            IdleDrainEntity(startTs = dayStart, kwhConsumed = 1.0),
        )
        assertEquals(2.0, InsightStatsAggregator.nightDrainKwh(drains), 0.001)
    }

    @Test
    fun `isNightHour boundaries`() {
        val cal = Calendar.getInstance()
        cal.set(2026, Calendar.JANUARY, 1, 22, 0, 0)
        assertTrue(InsightStatsAggregator.isNightHour(cal, cal.timeInMillis))
        cal.set(2026, Calendar.JANUARY, 1, 5, 59, 0)
        assertTrue(InsightStatsAggregator.isNightHour(cal, cal.timeInMillis))
        cal.set(2026, Calendar.JANUARY, 1, 10, 0, 0)
        assertFalse(InsightStatsAggregator.isNightHour(cal, cal.timeInMillis))
    }

    @Test
    fun `charging week splits AC and DC cost`() {
        val charges = listOf(
            ChargeEntity(startTs = 1, kwhCharged = 10.0, type = "AC", cost = 50.0, status = "COMPLETED"),
            ChargeEntity(startTs = 2, kwhCharged = 20.0, type = "DC", cost = 200.0, status = "COMPLETED"),
        )
        val week = InsightStatsAggregator.chargingWeek(charges)
        assertEquals(10.0, week.acKwh, 0.001)
        assertEquals(20.0, week.dcKwh, 0.001)
        assertEquals(5.0, week.acCostPerKwh!!, 0.001)
        assertEquals(10.0, week.dcCostPerKwh!!, 0.001)
        assertEquals(250.0, week.totalCost, 0.001)
    }
}
