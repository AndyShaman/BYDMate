package com.bydmate.app.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Open-Meteo client for the get_weather tool. No API key required for either endpoint.
 */
@Singleton
class WeatherClient @Inject constructor(private val http: OkHttpClient) {

    /** Failure whose message is user-facing Russian text, safe to surface in tool error JSON.
     *  Any other Throwable (HTTP codes, parse errors, timeouts) carries an English message and
     *  must be collapsed to a generic Russian error by the caller. */
    class UserError(message: String) : IOException(message)

    /** A geocoded location: coordinates plus the resolved place name, voiced back by the tool. */
    data class GeoPoint(val lat: Double, val lon: Double, val name: String)

    /** Current conditions + 3-day forecast, compacted into the JSON the LLM tool returns. */
    suspend fun forecast(lat: Double, lon: Double): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val url = FORECAST_URL.toHttpUrl().newBuilder()
                .addQueryParameter("latitude", lat.toString())
                .addQueryParameter("longitude", lon.toString())
                .addQueryParameter("current",
                    "temperature_2m,apparent_temperature,weather_code,wind_speed_10m,relative_humidity_2m")
                .addQueryParameter("daily",
                    "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
                .addQueryParameter("timezone", "auto")
                .addQueryParameter("forecast_days", "3")
                // Open-Meteo defaults to km/h; the tool JSON field is wind_ms.
                .addQueryParameter("wind_speed_unit", "ms")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("empty body")
                val json = JSONObject(body)
                val current = json.getJSONObject("current")
                val now = JSONObject().apply {
                    put("temp_c", current.getDouble("temperature_2m"))
                    put("feels_c", current.getDouble("apparent_temperature"))
                    put("text", wmoToRussian(current.getInt("weather_code")))
                    put("wind_ms", current.getDouble("wind_speed_10m"))
                    put("humidity", current.getInt("relative_humidity_2m"))
                }
                val daily = json.getJSONObject("daily")
                val dates = daily.getJSONArray("time")
                val maxT = daily.getJSONArray("temperature_2m_max")
                val minT = daily.getJSONArray("temperature_2m_min")
                val codes = daily.getJSONArray("weather_code")
                val precip = daily.getJSONArray("precipitation_probability_max")
                val days = JSONArray().apply {
                    for (i in 0 until dates.length()) {
                        put(JSONObject().apply {
                            put("date", dates.getString(i))
                            put("min_c", minT.getDouble(i))
                            put("max_c", maxT.getDouble(i))
                            put("text", wmoToRussian(codes.getInt(i)))
                            put("precip_prob", precip.getInt(i))
                        })
                    }
                }
                JSONObject().put("now", now).put("days", days).toString()
            }
        }
    }

    /** Open-Meteo geocoding: first match for a free-text city name. */
    suspend fun geocode(city: String): Result<GeoPoint> = withContext(Dispatchers.IO) {
        runCatching {
            val url = GEOCODE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("name", city)
                .addQueryParameter("count", "1")
                .addQueryParameter("language", "ru")
                .build()
            http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                    ?: throw IOException("empty body")
                val results = JSONObject(body).optJSONArray("results")
                if (results == null || results.length() == 0) throw UserError("город не найден")
                val r = results.getJSONObject(0)
                // admin1 is intentionally dropped: the short name reads best over TTS.
                GeoPoint(r.getDouble("latitude"), r.getDouble("longitude"), r.optString("name"))
            }
        }
    }

    companion object {
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
        private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search"

        private val WMO_RU = mapOf(
            0 to "ясно",
            1 to "малооблачно",
            2 to "переменная облачность",
            3 to "пасмурно",
            45 to "туман",
            48 to "изморозь в тумане",
            51 to "лёгкая морось",
            53 to "умеренная морось",
            55 to "сильная морось",
            56 to "лёгкая ледяная морось",
            57 to "сильная ледяная морось",
            61 to "небольшой дождь",
            63 to "умеренный дождь",
            65 to "сильный дождь",
            66 to "лёгкий ледяной дождь",
            67 to "сильный ледяной дождь",
            71 to "небольшой снег",
            73 to "умеренный снег",
            75 to "сильный снег",
            77 to "снежная крупа",
            80 to "небольшие ливни",
            81 to "умеренные ливни",
            82 to "сильные ливни",
            85 to "небольшой снегопад",
            86 to "сильный снегопад",
            95 to "гроза",
            96 to "гроза с небольшим градом",
            99 to "гроза с сильным градом",
        )

        /** [code] is the Open-Meteo WMO weather_code. Unknown codes fall back to a generic label. */
        fun wmoToRussian(code: Int): String = WMO_RU[code] ?: "неизвестная погода"
    }
}
