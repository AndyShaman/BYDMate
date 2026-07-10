package com.bydmate.app.agent

import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherClientTest {

    private val forecastFixture = """
        {
          "current": {
            "temperature_2m": 21.4,
            "apparent_temperature": 19.8,
            "weather_code": 3,
            "wind_speed_10m": 4.2,
            "relative_humidity_2m": 55
          },
          "daily": {
            "time": ["2026-07-04", "2026-07-05", "2026-07-06"],
            "temperature_2m_max": [24.0, 22.5, 19.0],
            "temperature_2m_min": [15.0, 14.2, 12.0],
            "weather_code": [1, 61, 95],
            "precipitation_probability_max": [10, 60, 80]
          }
        }
    """.trimIndent()

    private val geocodeFixture = """
        {
          "results": [
            {"name": "Москва", "latitude": 55.75222, "longitude": 37.61556},
            {"name": "Moscow", "latitude": 46.6, "longitude": -117.0}
          ]
        }
    """.trimIndent()

    private var lastUrl: String? = null

    /** Routes by host: forecast vs geocoding vs a fixed HTTP status for error-path tests. */
    private fun clientFor(status: Int = 200, forecastBody: String? = null, geocodeBody: String? = null): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor { chain ->
                val req = chain.request()
                lastUrl = req.url.toString()
                val body = if (req.url.host == "geocoding-api.open-meteo.com") geocodeBody else forecastBody
                Response.Builder()
                    .request(req)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(status).message(if (status == 200) "OK" else "Error")
                    .body((body ?: "").toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()

    // (a) forecast parses fixture response into the expected compact JSON.
    @Test fun forecast_parses_fixture_into_compact_json() = runTest {
        val client = WeatherClient(clientFor(forecastBody = forecastFixture))
        val result = client.forecast(55.75, 37.62)
        assertTrue(result.isSuccess)
        val json = JSONObject(result.getOrThrow())

        val now = json.getJSONObject("now")
        assertEquals(21.4, now.getDouble("temp_c"), 0.01)
        assertEquals(19.8, now.getDouble("feels_c"), 0.01)
        assertEquals("пасмурно", now.getString("text"))
        assertEquals(4.2, now.getDouble("wind_ms"), 0.01)
        assertEquals(55, now.getInt("humidity"))

        val days = json.getJSONArray("days")
        assertEquals(3, days.length())
        val day0 = days.getJSONObject(0)
        assertEquals("2026-07-04", day0.getString("date"))
        assertEquals(15.0, day0.getDouble("min_c"), 0.01)
        assertEquals(24.0, day0.getDouble("max_c"), 0.01)
        assertEquals("малооблачно", day0.getString("text"))
        assertEquals(10, day0.getInt("precip_prob"))
        assertEquals("гроза", days.getJSONObject(2).getString("text"))
    }

    // (b) wmoToRussian: known and unknown codes.
    @Test fun wmoToRussian_maps_known_codes_and_falls_back_for_unknown() {
        assertEquals("ясно", WeatherClient.wmoToRussian(0))
        assertEquals("сильный дождь", WeatherClient.wmoToRussian(65))
        assertEquals("гроза с сильным градом", WeatherClient.wmoToRussian(99))
        assertEquals("неизвестная погода", WeatherClient.wmoToRussian(12345))
    }

    // (c) geocode returns the first result, carrying its name for the tool to voice back.
    @Test fun geocode_returns_first_match() = runTest {
        val client = WeatherClient(clientFor(geocodeBody = geocodeFixture))
        val result = client.geocode("Москва")
        assertTrue(result.isSuccess)
        val geo = result.getOrThrow()
        assertEquals(55.75222, geo.lat, 0.0001)
        assertEquals(37.61556, geo.lon, 0.0001)
        assertEquals("Москва", geo.name)
    }

    @Test fun geocode_no_results_is_failure() = runTest {
        val client = WeatherClient(clientFor(geocodeBody = """{"results": []}"""))
        val result = client.geocode("Несуществующий Город")
        assertTrue(result.isFailure)
        // City-not-found is the one failure whose message is user-facing Russian text.
        val e = result.exceptionOrNull()
        assertTrue(e is WeatherClient.UserError)
        assertEquals("город не найден", e?.message)
    }

    // Pin: Open-Meteo defaults to km/h; the wind_ms field requires an explicit unit override.
    @Test fun forecast_requests_wind_in_meters_per_second() = runTest {
        val client = WeatherClient(clientFor(forecastBody = forecastFixture))
        client.forecast(55.75, 37.62)
        assertTrue("forecast URL must pin wind_speed_unit=ms, was: $lastUrl",
            lastUrl!!.contains("wind_speed_unit=ms"))
    }

    // (d) HTTP 500 -> Result.failure.
    @Test fun forecast_http_error_is_failure() = runTest {
        val client = WeatherClient(clientFor(status = 500, forecastBody = "boom"))
        val result = client.forecast(55.75, 37.62)
        assertTrue(result.isFailure)
    }

    @Test fun geocode_http_error_is_failure() = runTest {
        val client = WeatherClient(clientFor(status = 500, geocodeBody = "boom"))
        val result = client.geocode("Москва")
        assertFalse(result.isSuccess)
    }
}
