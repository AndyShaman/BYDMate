package com.bydmate.app.domain.cost

import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_CHARGES
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_DC
import com.bydmate.app.data.local.entity.TariffPeriodEntity.Companion.TRIP_RULE_HOME
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure pricing rules: no Room, no Android. */
class CostCalculatorTest {

    private val day = 24 * 60 * 60 * 1000L

    private val august = TariffPeriodEntity(
        id = 1, startTs = 0L, homeRate = 0.10, dcRate = 0.50, tripRule = TRIP_RULE_HOME,
    )
    private val september = TariffPeriodEntity(
        id = 2, startTs = 1_000_000L, homeRate = 0.20, dcRate = 0.70, tripRule = TRIP_RULE_HOME,
    )
    private val schedule = TariffSchedule(listOf(september, august))

    @Test fun `periodAt picks the last period that already started`() {
        assertEquals(august.id, schedule.periodAt(999_999L)!!.id)
        assertEquals(september.id, schedule.periodAt(1_000_000L)!!.id)
        assertEquals(september.id, schedule.periodAt(5_000_000L)!!.id)
    }

    @Test fun `a timestamp before the first period still uses the first one`() {
        val later = TariffSchedule(listOf(september))
        assertEquals(september.id, later.periodAt(1L)!!.id)
    }

    @Test fun `a period dated in the future is ignored until its date`() {
        val future = september.copy(id = 3, startTs = 9_000_000L, homeRate = 0.99)
        val withFuture = TariffSchedule(listOf(august, september, future))
        assertEquals(september.id, withFuture.periodAt(5_000_000L)!!.id)
        assertEquals(future.id, withFuture.periodAt(9_000_001L)!!.id)
    }

    @Test fun `AC losses gross the pack intake up before the rate is applied`() {
        // 9 kWh into the pack at 10% losses = 10 kWh paid for, at 0.20 = 2.00
        val cost = TariffSchedule.chargeCost(september, "AC", kwhCharged = 9.0, meterKwh = null)
        assertEquals(2.0, cost!!, 1e-9)
    }

    @Test fun `DC losses default to 5 percent`() {
        val cost = TariffSchedule.chargeCost(september, "DC", kwhCharged = 19.0, meterKwh = null)
        assertEquals(19.0 / 0.95 * 0.70, cost!!, 1e-9)
    }

    @Test fun `a meter reading replaces the loss estimate`() {
        val cost = TariffSchedule.chargeCost(september, "AC", kwhCharged = 9.0, meterKwh = 12.0)
        assertEquals(12.0 * 0.20, cost!!, 1e-9)
    }

    @Test fun `no kWh means no cost`() {
        assertNull(TariffSchedule.chargeCost(september, "AC", kwhCharged = null, meterKwh = null))
        assertNull(TariffSchedule.chargeCost(september, "AC", kwhCharged = 0.0, meterKwh = null))
    }

    @Test fun `fixed trip rules read straight off the period`() {
        assertEquals(0.20, TariffSchedule.fixedTripRate(september)!!, 1e-9)
        assertEquals(0.70, TariffSchedule.fixedTripRate(september.copy(tripRule = TRIP_RULE_DC))!!, 1e-9)
        assertEquals(0.33, TariffSchedule.fixedTripRate(september.copy(tripRule = "0.33"))!!, 1e-9)
        assertNull(TariffSchedule.fixedTripRate(september.copy(tripRule = TRIP_RULE_CHARGES)))
    }

    @Test fun `an unparsable custom rule falls back to the home rate`() {
        assertEquals(0.20, TariffSchedule.fixedTripRate(september.copy(tripRule = "abc"))!!, 1e-9)
    }

    @Test fun `weighted average mixes the new charge into what was left in the pack`() {
        val capacity = 100.0
        val timeline = BatteryPriceTimeline(
            listOf(
                // Empty pack, 50 kWh at 1.00 per kWh -> price 1.00
                charge(ts = 1000L, socStart = 0, kwh = 50.0, cost = 50.0),
                // 50 kWh left at 1.00, another 50 kWh at 3.00 -> (50*1 + 150) / 100 = 2.00
                charge(ts = 2000L, socStart = 50, kwh = 50.0, cost = 150.0),
            ),
            capacity,
        )
        assertEquals(1.0, timeline.priceAt(1500L)!!.pricePerKwh, 1e-9)
        assertEquals(2.0, timeline.priceAt(2500L)!!.pricePerKwh, 1e-9)
    }

    @Test fun `a trip before any charge has no weighted price`() {
        val timeline = BatteryPriceTimeline(
            listOf(charge(ts = 5000L, socStart = 0, kwh = 10.0, cost = 10.0)), 100.0)
        assertNull(timeline.tripPriceAt(4000L))
    }

    @Test fun `a charge older than 60 days does not price a trip`() {
        val chargeTs = 10 * day
        val timeline = BatteryPriceTimeline(
            listOf(charge(ts = chargeTs, socStart = 0, kwh = 10.0, cost = 10.0)), 100.0)
        assertTrue(timeline.tripPriceAt(chargeTs + 59 * day) != null)
        assertNull(timeline.tripPriceAt(chargeTs + 61 * day))
    }

    @Test fun `loss percentage is clamped so a broken setting cannot divide by zero`() {
        val insane = september.copy(acLossPct = 100.0)
        val cost = TariffSchedule.chargeCost(insane, "AC", kwhCharged = 10.0, meterKwh = null)
        assertTrue(cost!!.isFinite())
    }

    private fun charge(ts: Long, socStart: Int, kwh: Double, cost: Double) = ChargeEntity(
        startTs = ts, endTs = ts, socStart = socStart, kwhCharged = kwh, cost = cost, type = "AC",
    )
}
