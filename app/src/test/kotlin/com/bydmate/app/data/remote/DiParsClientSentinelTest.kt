package com.bydmate.app.data.remote

import okhttp3.OkHttpClient
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/**
 * issue #19 — DiPlus on Song Plus returns sentinel values like -2000 for
 * unavailable temperatures. Without a guard the cabin temp on the dashboard
 * read "-2000°C". DiParsClient.parse() must drop physically impossible values
 * for InsideTemp / ExtTemp (cabin) and battery temps.
 */
class DiParsClientSentinelTest {

    private val client = DiParsClient(OkHttpClient())

    private fun encode(map: Map<String, String>): String =
        map.entries.joinToString("|") { "${it.key}:${it.value}" }

    @Test fun cabin_temp_sentinel_dropped() {
        val raw = encode(mapOf("InsideTemp" to "-2000"))
        assertNull(client.parse(raw).insideTemp)
    }

    @Test fun cabin_temp_within_range_kept() {
        val raw = encode(mapOf("InsideTemp" to "23"))
        assertEquals(23, client.parse(raw).insideTemp)
    }

    @Test fun cabin_temp_extreme_negative_dropped() {
        val raw = encode(mapOf("InsideTemp" to "-32768"))
        assertNull(client.parse(raw).insideTemp)
    }

    @Test fun cabin_temp_extreme_positive_dropped() {
        val raw = encode(mapOf("InsideTemp" to "32767"))
        assertNull(client.parse(raw).insideTemp)
    }

    @Test fun cabin_temp_boundary_kept_low() {
        val raw = encode(mapOf("InsideTemp" to "-50"))
        assertEquals(-50, client.parse(raw).insideTemp)
    }

    @Test fun cabin_temp_boundary_kept_high() {
        val raw = encode(mapOf("InsideTemp" to "80"))
        assertEquals(80, client.parse(raw).insideTemp)
    }

    @Test fun exterior_temp_sentinel_dropped() {
        val raw = encode(mapOf("ExtTemp" to "-2000"))
        assertNull(client.parse(raw).exteriorTemp)
    }

    @Test fun exterior_temp_below_range_dropped() {
        val raw = encode(mapOf("ExtTemp" to "-51"))
        assertNull(client.parse(raw).exteriorTemp)
    }

    @Test fun battery_temp_sentinel_dropped() {
        val raw = encode(mapOf(
            "MaxBatTemp" to "-2000",
            "AvgBatTemp" to "-2000",
            "MinBatTemp" to "-2000"
        ))
        val parsed = client.parse(raw)
        assertNull(parsed.maxBatTemp)
        assertNull(parsed.avgBatTemp)
        assertNull(parsed.minBatTemp)
    }

    @Test fun battery_temp_within_range_kept() {
        val raw = encode(mapOf("AvgBatTemp" to "25", "MaxBatTemp" to "30", "MinBatTemp" to "20"))
        val parsed = client.parse(raw)
        assertEquals(30, parsed.maxBatTemp)
        assertEquals(25, parsed.avgBatTemp)
        assertEquals(20, parsed.minBatTemp)
    }

    @Test fun unrelated_fields_unaffected_by_sentinel_filter() {
        val raw = encode(mapOf("InsideTemp" to "-2000", "SOC" to "55", "Speed" to "60"))
        val parsed = client.parse(raw)
        assertNull(parsed.insideTemp)
        assertEquals(55, parsed.soc)
        assertEquals(60, parsed.speed)
    }
}
