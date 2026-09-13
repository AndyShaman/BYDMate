package com.bydmate.app.data.local

import io.kotest.property.Arb
import io.kotest.property.arbitrary.double
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.orNull
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// VadimV, 2026-09-12: energydata wrote 54681,2 kWh for a 38,2 km trip and poisoned every
// week/month/year statistic built on top of it. These properties pin down the invariants
// the point tests in TripEnergySanityTest only sample: kwhFor() must never hand out garbage,
// must not keep re-repairing its own output, and must leave the healthy fleet untouched.
class TripEnergySanityPropertyTest {

    private val electricityKwh = Arb.double(-1e3, 1e6)
    private val tripKm = Arb.double(0.0, 2000.0).orNull()
    private val soc = Arb.int(0, 100).orNull()
    private val capacityKwh = Arb.double(0.0, 120.0)

    @Test fun `kwhFor never returns garbage`(): Unit = runBlocking {
        checkAll(electricityKwh, tripKm, soc, soc, capacityKwh) { kwh, km, start, end, capacity ->
            val result = TripEnergySanity.kwhFor(kwh, km, start, end, capacity)
            if (result != null) {
                assertTrue("negative result: $result", result >= 0.0)
                assertTrue("above MAX_KWH_PER_TRIP: $result", result <= TripEnergySanity.MAX_KWH_PER_TRIP)
                if ((km ?: 0.0) >= TripEnergySanity.MIN_KM_FOR_PER100_RULE) {
                    val per100 = TripEnergySanity.per100For(result, km)
                    val withinCeiling = per100 != null && per100 <= TripEnergySanity.MAX_KWH_PER_100KM
                    // The SOC-delta estimate is the contract when the raw value is garbage,
                    // even where that estimate itself sits above the per-100 ceiling.
                    val fromSocFallback = result != kwh
                    assertTrue(
                        "per-100 $per100 above ceiling and not the SOC fallback (result=$result, kwh=$kwh)",
                        withinCeiling || fromSocFallback,
                    )
                }
            }
        }
    }

    @Test fun `kwhFor does not repair its own output`(): Unit = runBlocking {
        checkAll(electricityKwh, tripKm, soc, soc, capacityKwh) { kwh, km, start, end, capacity ->
            val once = TripEnergySanity.kwhFor(kwh, km, start, end, capacity)
            if (once != null) {
                val twice = TripEnergySanity.kwhFor(once, km, start, end, capacity)
                assertEquals(
                    "repair is not idempotent: kwhFor($once) = $twice (input was kwh=$kwh)",
                    once,
                    twice,
                )
            }
        }
    }

    @Test fun `healthy fleet passes through unchanged`(): Unit = runBlocking {
        checkAll(
            Arb.double(0.0, 30.0),
            Arb.double(5.0, 500.0),
            soc,
            soc,
            capacityKwh,
        ) { kwh, km, start, end, capacity ->
            if (kwh / km * 100.0 <= TripEnergySanity.MAX_KWH_PER_100KM) {
                assertEquals(kwh, TripEnergySanity.kwhFor(kwh, km, start, end, capacity))
            }
        }
    }
}
