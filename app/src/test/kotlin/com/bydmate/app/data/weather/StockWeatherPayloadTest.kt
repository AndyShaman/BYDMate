package com.bydmate.app.data.weather

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

class StockWeatherPayloadTest {
    @Test fun `forecast supplies the stock card with current hourly and daily weather`() {
        val aqi = JSONObject("""{"current":{"european_aqi":24,"pm2_5":8.2,"pm10":12.4}}""")
        val result = StockWeatherPayload.build(forecast(), aqi, "Minsk", NOW)
        assertEquals("0", result.getString("resultcode"))
        val data = result.getJSONObject("data")
        assertEquals("Minsk", data.getJSONObject("city").getString("name"))
        val current = data.getJSONObject("condition")
        assertEquals(21, current.getInt("temperature"))
        assertEquals(18, current.getInt("realfeel"))
        assertEquals(8, current.getInt("cnweatherid"))
        assertEquals(10, current.getInt("visibility"))
        assertEquals(55, current.getInt("humidity"))
        assertEquals("SW", current.getString("winddir"))
        assertEquals(8, data.getJSONObject("hourlys").getJSONArray("hourlyweathers").length())
        val daily = data.getJSONObject("dailys").getJSONArray("dailyweathers")
        assertEquals(16, daily.length())
        assertEquals("2026-10-10", daily.getJSONObject(0).getString("publictimeFmt"))
        assertEquals("2026-10-11", daily.getJSONObject(1).getString("publictimeFmt"))
        assertEquals(24, daily.getJSONObject(1).getInt("aqivalue"))
        assertEquals(-1, daily.getJSONObject(2).getInt("aqivalue"))
        assertEquals(24, data.getJSONObject("aqi").getInt("aqivalue"))
        assertTrue(data.getString("weatherDesc").contains("Open-Meteo"))
    }

    @Test fun `forecast timestamps use the forecast timezone rather than the Chinese head unit timezone`() {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
            val data = StockWeatherPayload.build(forecast(), null, "Minsk", NOW).getJSONObject("data")
            val current = data.getJSONObject("condition")
            assertEquals(Instant.parse("2026-10-11T09:00:00Z").toEpochMilli(), current.getLong("updatetime"))
            assertEquals("2026-10-11 12:00", current.getString("updatetimeFmt"))
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun `missing air quality stays unknown`() {
        val aqi = StockWeatherPayload.build(forecast(), null, "Minsk", NOW)
            .getJSONObject("data").getJSONObject("aqi")
        assertEquals(-1, aqi.getInt("aqivalue"))
        assertEquals("--", aqi.getString("aqivaluetext"))
        assertEquals(-1, aqi.getInt("pm25"))
    }

    @Test fun `forecast uses the API fixed offset across a daylight saving transition`() {
        val input = forecast().put("timezone", "Europe/Berlin").put("utc_offset_seconds", 7200)
        val days = StockWeatherPayload.build(input, null, "Berlin", NOW)
            .getJSONObject("data").getJSONObject("dailys").getJSONArray("dailyweathers")
        val last = days.getJSONObject(15)
        assertEquals(Instant.parse("2026-10-25T05:00:00Z").toEpochMilli(), last.getLong("sunRise"))
        assertEquals("2026-10-25 07:00", last.getString("sunRiseFmt"))
    }

    @Test fun `WMO conditions map to BYD icon identifiers`() {
        val icons = mapOf(0 to 0, 1 to 1, 2 to 1, 3 to 2, 45 to 18, 48 to 18,
            51 to 7, 53 to 8, 55 to 9, 56 to 19, 57 to 19, 61 to 7, 63 to 8, 65 to 9,
            66 to 19, 67 to 19, 71 to 14, 73 to 15, 75 to 16, 77 to 14,
            80 to 3, 81 to 3, 82 to 3, 85 to 13, 86 to 13, 95 to 4, 96 to 5, 99 to 5)
        for ((wmo, icon) in icons) {
            val input = forecast().apply { getJSONObject("current").put("weather_code", wmo) }
            assertEquals("WMO $wmo", icon, StockWeatherPayload.build(input, null, "Minsk", NOW)
                .getJSONObject("data").getJSONObject("condition").getInt("cnweatherid"))
        }
    }

    @Test fun `incomplete or invalid forecasts are rejected instead of publishing plausible zeroes`() {
        val invalid = listOf(
            forecast().apply { getJSONObject("current").remove("temperature_2m") },
            forecast().apply { getJSONObject("current").put("temperature_2m", JSONObject.NULL) },
            forecast().apply { getJSONObject("current").put("weather_code", 12345) },
            forecast().apply { getJSONObject("hourly").getJSONArray("time").remove(7) },
            forecast().apply { getJSONObject("daily").getJSONArray("time").remove(15) },
        )
        for (input in invalid) {
            assertTrue(runCatching { StockWeatherPayload.build(input, null, "Minsk", NOW) }.isFailure)
        }
    }

    companion object {
        const val NOW = 1_791_709_200_000L

        fun forecast(): JSONObject {
            val result = JSONObject().put("timezone", "Europe/Minsk").put("utc_offset_seconds", 10800)
            result.put("current", JSONObject("""{
                "time":"2026-10-11T12:00","temperature_2m":21.4,"apparent_temperature":18.2,
                "weather_code":63,"relative_humidity_2m":55,"pressure_msl":1012.5,
                "visibility":10000,"wind_speed_10m":14,"wind_direction_10m":225,
                "uv_index":2,"wind_gusts_10m":20,"cloud_cover":80,"precipitation":1.2
            }"""))
            val hourly = JSONObject().put("time", JSONArray((12..19).map { "2026-10-11T${it}:00" }))
            for ((key, value) in mapOf("temperature_2m" to 21, "weather_code" to 63,
                    "precipitation_probability" to 70, "wind_speed_10m" to 14,
                    "wind_direction_10m" to 225, "is_day" to 1)) {
                hourly.put(key, JSONArray(List(8) { value }))
            }
            result.put("hourly", hourly)
            val dates = (0..15).map { LocalDate.parse("2026-10-10").plusDays(it.toLong()).toString() }
            val daily = JSONObject().put("time", JSONArray(dates))
                .put("sunrise", JSONArray(dates.map { "${it}T07:00" }))
                .put("sunset", JSONArray(dates.map { "${it}T18:00" }))
            for ((key, value) in mapOf("weather_code" to 63, "temperature_2m_max" to 24,
                    "temperature_2m_min" to 12, "uv_index_max" to 3,
                    "wind_speed_10m_max" to 20, "wind_direction_10m_dominant" to 225)) {
                daily.put(key, JSONArray(List(16) { value }))
            }
            return result.put("daily", daily)
        }
    }
}
