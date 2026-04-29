package com.bydmate.app.data.remote

import android.location.Location
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Отправляет живые показатели автомобиля в Iternio Telemetry API (A Better Route Planner).
 *
 * Документация: https://documenter.getpostman.com/view/7396339/SWTK5a8w
 * Формат тела: application/x-www-form-urlencoded:
 * - token: пользовательский токен живых данных из ABRP
 * - tlm: JSON-объект с телеметрией в метрических единицах
 */
@Singleton
class IternioTelemetryClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "IternioTelemetry"
        private const val SEND_URL = "https://api.iternio.com/1/tlm/send"
    }

    /**
     * @param apiKey API-ключ Iternio для приложения.
     * @param userToken Токен живых данных автомобиля из ABRP.
     */
    suspend fun send(
        apiKey: String,
        userToken: String,
        data: DiParsData,
        location: Location?,
        carModel: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val token = userToken.trim()
        if (token.isEmpty()) return@withContext Result.failure(IllegalArgumentException("пустой токен"))

        try {
            val telemetry = JSONObject()
            telemetry.put("utc", System.currentTimeMillis() / 1000)

            data.soc?.let { telemetry.put("soc", it) }
            data.speed?.let { telemetry.put("speed", it) }
            data.power?.let { p -> telemetry.put("power", p) }

            data.avgBatTemp?.let { telemetry.put("batt_temp", it) }
            data.exteriorTemp?.let { telemetry.put("ext_temp", it) }
            data.batteryCapacityKwh?.let { telemetry.put("capacity", it) }
            data.mileage?.let { telemetry.put("odometer", it) }
            data.insideTemp?.let { telemetry.put("cabin_temp", it) }
            data.tirePressFL?.let { telemetry.put("tire_pressure_fl", it) }
            data.tirePressFR?.let { telemetry.put("tire_pressure_fr", it) }
            data.tirePressRL?.let { telemetry.put("tire_pressure_rl", it) }
            data.tirePressRR?.let { telemetry.put("tire_pressure_rr", it) }

            telemetry.put("is_charging", if (isCharging(data)) 1 else 0)
            data.gear?.let { telemetry.put("is_parked", if (it == 1) 1 else 0) }

            location?.let { loc ->
                telemetry.put("lat", loc.latitude)
                telemetry.put("lon", loc.longitude)
                if (loc.hasAltitude()) {
                    telemetry.put("elevation", loc.altitude)
                }
            }

            carModel?.trim()?.takeIf { it.isNotEmpty() }?.let { telemetry.put("car_model", it) }

            val form = FormBody.Builder()
                .add("token", token)
                .add("tlm", telemetry.toString())

            val url = SEND_URL.toHttpUrl().newBuilder().apply {
                val key = apiKey.trim()
                if (key.isNotEmpty()) addQueryParameter("api_key", key)
            }.build()

            val request = Request.Builder()
                .url(url)
                .post(form.build())
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Log.w(TAG, "HTTP ${response.code}: $body")
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}")
                    )
                }
                try {
                    val status = JSONObject(body).optString("status")
                    if (status.isNotBlank() && !status.equals("ok", ignoreCase = true)) {
                        Log.w(TAG, "API error: $body")
                        return@withContext Result.failure(IllegalStateException(body))
                    }
                } catch (_: Exception) { /* Пустой или не-JSON ответ считаем успешным при HTTP 2xx. */ }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "отправка не удалась: ${e.message}")
            Result.failure(e)
        }
    }

    private fun isCharging(data: DiParsData): Boolean {
        if (data.chargeGunState == 2) return true
        val p = data.power
        if (p != null && p < 0) return true
        // Значения chargingStatus отличаются между прошивками, поэтому активные коды трактуем мягко.
        val cs = data.chargingStatus
        if (cs != null && cs > 0) return true
        return false
    }
}
