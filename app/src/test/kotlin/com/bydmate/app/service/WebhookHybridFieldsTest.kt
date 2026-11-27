package com.bydmate.app.service

import com.bydmate.app.data.remote.diParsData
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Test

class WebhookHybridFieldsTest {

    @Test
    fun `all valid hybrid fields are added alongside existing keys`() {
        val base = JSONObject().put("utc", 1_700_000_000L).put("soc", 64)
        val data = diParsData(soc = 64).copy(
            fuelPercent = 72,
            fuelRangeKm = 512,
            fuelTotalL = 123.4,
            evRangeKm = 46,
            mileageHevKm = 12000,
            mileageEvKm = 8500,
            engineRpm = 1450,
        )

        val out = WebhookHybridFields.addTo(base, data)

        assertEquals(1_700_000_000L, out.getLong("utc"))
        assertEquals(64, out.getInt("soc"))
        assertEquals(72, out.getInt("fuel_percent"))
        assertEquals(512, out.getInt("fuel_range_km"))
        assertEquals(123.4, out.getDouble("fuel_total_l"), 1e-9)
        assertEquals(46, out.getInt("ev_range_km"))
        assertEquals(12000, out.getInt("odometer_hev_km"))
        assertEquals(8500, out.getInt("odometer_ev_km"))
        assertEquals(1450, out.getInt("engine_rpm"))
    }

    @Test
    fun `EV snapshot with all hybrid fields null keeps exact input keys`() {
        val base = JSONObject().put("utc", 1_700_000_000L).put("soc", 80)
        val evData = diParsData(soc = 80)

        val out = WebhookHybridFields.addTo(base, evData)

        assertEquals(setOf("utc", "soc"), out.keys().asSequence().toSet())
        assertEquals(1_700_000_000L, out.getLong("utc"))
        assertEquals(80, out.getInt("soc"))
    }

    @Test
    fun `sentinel and out-of-range hybrid values are omitted`() {
        val base = JSONObject().put("soc", 64)
        val sentinels = diParsData(soc = 64).copy(
            fuelPercent = 255,
            fuelRangeKm = 2046,
            fuelTotalL = -0.1,
            evRangeKm = 2046,
            mileageHevKm = -1,
            mileageEvKm = -1,
            engineRpm = -1,
        )

        val out = WebhookHybridFields.addTo(base, sentinels)

        assertFalse(out.has("fuel_percent"))
        assertFalse(out.has("fuel_range_km"))
        assertFalse(out.has("fuel_total_l"))
        assertFalse(out.has("ev_range_km"))
        assertFalse(out.has("odometer_hev_km"))
        assertFalse(out.has("odometer_ev_km"))
        assertFalse(out.has("engine_rpm"))
        assertEquals(setOf("soc"), out.keys().asSequence().toSet())
    }

    @Test
    fun `input JSONObject is not modified by addTo`() {
        val base = JSONObject().put("utc", 1_700_000_000L).put("soc", 64)
        val data = diParsData(soc = 64).copy(
            fuelPercent = 72,
            fuelRangeKm = 512,
            fuelTotalL = 123.4,
            evRangeKm = 46,
            mileageHevKm = 12000,
            mileageEvKm = 8500,
            engineRpm = 1450,
        )

        val out = WebhookHybridFields.addTo(base, data)

        assertNotSame(base, out)
        assertEquals(setOf("utc", "soc"), base.keys().asSequence().toSet())
        assertEquals(1_700_000_000L, base.getLong("utc"))
        assertEquals(64, base.getInt("soc"))
    }
}
