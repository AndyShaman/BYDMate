package com.bydmate.app.data.weather

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** Maps Open-Meteo forecasts to the stock WeatherData provider's JSON contract. */
internal object StockWeatherPayload {
    fun build(forecast: JSONObject, airQuality: JSONObject?, city: String, nowMs: Long): JSONObject {
        // Open-Meteo's ISO times use the response offset even across a DST transition.
        val zone = ZoneOffset.ofTotalSeconds(forecast.getInt("utc_offset_seconds"))
        val current = forecast.getJSONObject("current")
        val hourly = forecast.getJSONObject("hourly")
        val daily = forecast.getJSONObject("daily")
        require(hourly.getJSONArray("time").length() >= 8 && daily.getJSONArray("time").length() >= 16)
        fun time(value: String): Long = if ('T' in value) {
            LocalDateTime.parse(value).atZone(zone).toInstant().toEpochMilli()
        } else LocalDate.parse(value).atStartOfDay(zone).toInstant().toEpochMilli()
        fun formatted(value: Long, pattern: String): String = DateTimeFormatter.ofPattern(pattern)
            .format(java.time.Instant.ofEpochMilli(value).atZone(zone))
        fun wind(speed: Int, direction: Int): JSONObject = JSONObject()
            .put("windspeed", speed).put("windlevel", ((speed + 4) / 5).coerceIn(0, 10))
            .put("winddir", direction(direction))
        fun condition(wmo: Int): JSONObject = JSONObject().apply {
            val icon = icon(wmo)
            put("cnweatherid", icon).put("weatherid", icon).put("zmweatherid", icon)
            put("weathertext", description(icon))
        }
        val updatedAt = time(current.getString("time"))
        val now = condition(current.number("weather_code")).apply {
            for ((source, target) in mapOf("temperature_2m" to "temperature", "apparent_temperature" to "realfeel",
                    "uv_index" to "uVIndex", "relative_humidity_2m" to "humidity", "pressure_msl" to "pressure",
                    "wind_gusts_10m" to "windgustspeed", "cloud_cover" to "cloudCover")) {
                put(target, current.number(source))
            }
            put("visibility", current.number("visibility") / 1000)
            put("precipitation", current.finite("precipitation"))
            val speed = current.number("wind_speed_10m")
            val degrees = current.number("wind_direction_10m")
            put("windspeed", speed).put("windlevel", ((speed + 4) / 5).coerceIn(0, 10))
            put("winddegrees", degrees).put("winddir", direction(degrees)).put("winddirtext", direction(degrees))
            put("updatetime", updatedAt).put("updatetimeFmt", formatted(updatedAt, "yyyy-MM-dd HH:mm"))
        }
        val hours = JSONArray().apply {
            repeat(8) { i ->
                put(condition(hourly.numberAt("weather_code", i)).apply {
                    put("date", time(hourly.getJSONArray("time").getString(i)))
                    put("temp", hourly.numberAt("temperature_2m", i))
                    put("rainprobability", hourly.numberAt("precipitation_probability", i))
                    put("wd", direction(hourly.numberAt("wind_direction_10m", i)))
                    put("wp", ((hourly.numberAt("wind_speed_10m", i) + 4) / 5).coerceIn(0, 10))
                    put("isdaynight", hourly.numberAt("is_day", i) == 1)
                })
            }
        }
        val air = airQuality?.optJSONObject("current")
        fun airNumber(key: String): Int = air?.optDouble(key, Double.NaN)
            ?.takeIf { it.isFinite() && it >= 0 }?.roundToInt() ?: -1
        val aqi = airNumber("european_aqi")
        val days = JSONArray().apply {
            repeat(16) { i ->
                val date = time(daily.getJSONArray("time").getString(i))
                val sunrise = time(daily.getJSONArray("sunrise").getString(i))
                val sunset = time(daily.getJSONArray("sunset").getString(i))
                val sky = condition(daily.numberAt("weather_code", i)).apply {
                    val wind = wind(daily.numberAt("wind_speed_10m_max", i),
                        daily.numberAt("wind_direction_10m_dominant", i))
                    for (key in wind.keys()) put(key, wind.get(key))
                }
                val dayAqi = if (i == 1) aqi else -1
                put(JSONObject().put("publictime", date).put("publictimeFmt", formatted(date, "yyyy-MM-dd"))
                    .put("mintemp", daily.numberAt("temperature_2m_min", i))
                    .put("maxtemp", daily.numberAt("temperature_2m_max", i))
                    .put("uvIndex", daily.numberAt("uv_index_max", i))
                    .put("sunRise", sunrise).put("sunRiseFmt", formatted(sunrise, "yyyy-MM-dd HH:mm"))
                    .put("sunSet", sunset).put("sunSetFmt", formatted(sunset, "yyyy-MM-dd HH:mm"))
                    .put("aqivalue", dayAqi).put("aqivaluetext", if (dayAqi < 0) "--" else "$dayAqi")
                    .put("lv", aqiLevel(dayAqi)).put("conditionDay", sky).put("conditionNight", sky))
            }
        }
        val today = time(daily.getJSONArray("time").getString(1))
        val data = JSONObject()
            .put("city", JSONObject().put("name", city).put("englishCityName", city)
                .put("countryname", "").put("englishCountryName", "").put("countryCode", "")
                .put("timezone", forecast.getString("timezone")))
            .put("condition", now)
            .put("hourlys", JSONObject().put("expiretime", nowMs + 6 * 60 * 60 * 1000L).put("hourlyweathers", hours))
            .put("dailys", JSONObject().put("publictime", today).put("publictimeFmt", formatted(today, "yyyy-MM-dd"))
                .put("expiretime", nowMs + 24 * 60 * 60 * 1000L).put("dailyweathers", days))
            .put("aqi", JSONObject().put("aqivalue", aqi).put("aqivaluetext", if (aqi < 0) "--" else "$aqi")
                .put("lv", aqiLevel(aqi)).put("aqidesc", if (aqi < 0) "Unknown" else AQI_LABELS[aqiLevel(aqi) - 1])
                .put("pm25", airNumber("pm2_5")).put("pm10", airNumber("pm10")).put("updatetime", nowMs))
            .put("aqidays", JSONArray()).put("alarm", JSONArray()).put("liveInfos", JSONArray())
            .put("weatherDesc", "Weather data by Open-Meteo.com").put("mobilelink", "https://open-meteo.com/")
        return JSONObject().put("resultcode", "0").put("resultinfo", "success").put("servertime", nowMs)
            .put("data", data)
    }

    private fun JSONObject.finite(key: String): Double = getDouble(key).also { require(it.isFinite()) }
    private fun JSONObject.number(key: String): Int = finite(key).roundToInt()
    private fun JSONObject.numberAt(key: String, i: Int): Int = getJSONArray(key).getDouble(i)
        .also { require(it.isFinite()) }.roundToInt()
    private fun direction(degrees: Int): String = WIND[((Math.floorMod(degrees, 360) + 22) / 45) % 8]
    private fun aqiLevel(aqi: Int): Int = if (aqi < 0) 0 else ((aqi.coerceAtLeast(1) - 1) / 20 + 1).coerceAtMost(6)

    private fun icon(wmo: Int): Int = when (wmo) {
        0 -> 0
        1, 2 -> 1
        3 -> 2
        45, 48 -> 18
        56, 57, 66, 67 -> 19
        51, 61 -> 7
        53, 63 -> 8
        55, 65 -> 9
        71, 77 -> 14
        73 -> 15
        75 -> 16
        80, 81, 82 -> 3
        85, 86 -> 13
        95 -> 4
        96, 99 -> 5
        else -> error("unsupported weather code")
    }

    private fun description(icon: Int): String = when (icon) {
        0 -> "Clear"
        1 -> "Cloudy"
        2 -> "Overcast"
        18 -> "Fog"
        19 -> "Freezing rain"
        3, 7, 8, 9 -> "Rain"
        13, 14, 15, 16 -> "Snow"
        else -> "Thunderstorm"
    }

    private val WIND = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    private val AQI_LABELS = arrayOf("Good", "Fair", "Moderate", "Poor", "Very poor", "Extremely poor")
}
