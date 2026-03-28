package com.bydmate.app.data.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeatherClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    private var cachedTemp: Float? = null
    private var cacheTimestamp: Long = 0
    private val cacheDurationMs = 15 * 60 * 1000L // 15 minutes

    suspend fun getTemperature(lat: Double, lon: Double): Float? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (cachedTemp != null && (now - cacheTimestamp) < cacheDurationMs) {
            return@withContext cachedTemp
        }

        try {
            val url = "https://api.open-meteo.com/v1/forecast" +
                "?latitude=$lat&longitude=$lon&current_weather=true"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            val json = JSONObject(body)
            val weather = json.optJSONObject("current_weather") ?: return@withContext null
            val temp = weather.optDouble("temperature", Double.NaN)
            if (temp.isNaN()) return@withContext null

            cachedTemp = temp.toFloat()
            cacheTimestamp = now
            cachedTemp
        } catch (e: Exception) {
            null
        }
    }
}
