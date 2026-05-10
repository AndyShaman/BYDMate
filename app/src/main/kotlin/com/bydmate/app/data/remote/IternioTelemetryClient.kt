package com.bydmate.app.data.remote

import android.util.Log
import com.bydmate.app.data.autoservice.BatteryReading
import com.bydmate.app.data.autoservice.ChargingReading
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
 * Sends live vehicle telemetry to the legacy Iternio Telemetry API
 * (`/1/tlm/send`) for A Better Route Planner.
 *
 * Spec: https://documenter.getpostman.com/view/7396339/SWTK5a8w
 *
 * Body: `application/x-www-form-urlencoded` with `token` (user) + `tlm` (JSON).
 * `api_key` (developer) is sent as a query parameter.
 *
 * Power sign: BYD instantaneous power matches Iternio convention.
 * Positive = consumption (battery → motor/HVAC), negative = regen/charging
 * (motor/grid → battery). Pass through without inverting.
 *
 * GPS (lat/lon/elevation) is intentionally NOT sent — ABRP runs as a native
 * Android app on DiLink and reads location from the OS itself; sending GPS
 * would duplicate the signal and leak position to a third-party server.
 */
@Singleton
class IternioTelemetryClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "IternioTelemetry"
        private const val SEND_URL = "https://api.iternio.com/1/tlm/send"
        // Gun-state values that mean the car is on a DC fast charger.
        // 3=DC, 4=AC_DC (combo CCS), 5=VTOL — all bypass the onboard AC charger.
        private val DCFC_GUN_STATES = setOf(3, 4, 5)
        // Iternio rejects /1/tlm/send with HTTP 401 "Unauthorized Key" when no
        // api_key query param is provided. This is the long-standing community
        // key used by OVMS and teslamate-abrp — both major open-source ABRP
        // bridges. We embed it as a fallback so users don't need to register
        // their own developer key. Custom key from Settings still takes
        // precedence when set.
        const val DEFAULT_API_KEY = "32b2162f-9599-4647-8139-66e9f9528370"
    }

    /**
     * @param apiKey Developer API key issued by Iternio. Optional from caller
     *               perspective — when blank, falls back to [DEFAULT_API_KEY]
     *               so the request still passes Iternio's `api_key` gate.
     * @param userToken Per-vehicle live-data token from ABRP "Generic" provider.
     * @param data Live DiPars snapshot. SOC must be present — without it the
     *             call returns a failure without hitting the network.
     * @param nominalCapacityKwh Nominal battery capacity (user setting; 72.9 on
     *                           Leopard 3). Sent as Iternio `capacity` so ABRP
     *                           can translate SOC% to kWh. We do NOT use the
     *                           DiPars `batteryCapacityKwh` field — on Leopard
     *                           3 it returns ~4.5 (not nominal capacity).
     * @param battery Optional autoservice battery snapshot (Leopard 3 only).
     *                Adds `soh` when SoH is readable.
     * @param charging Optional autoservice charging snapshot (Leopard 3 only).
     *                 Adds `is_dcfc` and `kwh_charged` when readable.
     * @param carModel Optional ABRP car-model code from settings.
     */
    suspend fun send(
        apiKey: String,
        userToken: String,
        data: DiParsData,
        nominalCapacityKwh: Double,
        battery: BatteryReading?,
        charging: ChargingReading?,
        carModel: String?,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val token = userToken.trim()
        if (token.isEmpty()) return@withContext Result.failure(IllegalArgumentException("пустой токен"))
        // Iternio docs: SOC is the one truly required telemetry field. Without
        // it the route planner has nothing to update, so we skip the call.
        val soc = data.soc ?: return@withContext Result.failure(IllegalStateException("SOC недоступен"))

        try {
            val telemetry = JSONObject()
            telemetry.put("utc", System.currentTimeMillis() / 1000)
            telemetry.put("soc", soc)

            data.speed?.let { telemetry.put("speed", it) }
            data.power?.let { telemetry.put("power", it) }

            data.avgBatTemp?.let { telemetry.put("batt_temp", it) }
            data.exteriorTemp?.let { telemetry.put("ext_temp", it) }
            telemetry.put("capacity", nominalCapacityKwh)
            data.mileage?.let { telemetry.put("odometer", it) }
            data.insideTemp?.let { telemetry.put("cabin_temp", it) }
            data.tirePressFL?.let { telemetry.put("tire_pressure_fl", it) }
            data.tirePressFR?.let { telemetry.put("tire_pressure_fr", it) }
            data.tirePressRL?.let { telemetry.put("tire_pressure_rl", it) }
            data.tirePressRR?.let { telemetry.put("tire_pressure_rr", it) }

            telemetry.put("is_charging", if (isCharging(data, charging)) 1 else 0)
            data.gear?.let { telemetry.put("is_parked", if (it == 1) 1 else 0) }

            // Autoservice-only enrichment — Leopard 3 etc. SoH lets ABRP derate
            // nominal capacity by battery aging; is_dcfc separates fast-charge
            // sessions from AC; kwh_charged shows session progress.
            charging?.let { c ->
                c.gunConnectState?.let { gun ->
                    telemetry.put("is_dcfc", if (gun in DCFC_GUN_STATES) 1 else 0)
                }
                // -1.0f is the autoservice "no value" sentinel — drop it.
                // .toDouble() is required: Android's JSONObject only exposes
                // put(String, double) — there is no put(String, float). On JVM
                // the desktop org.json has the float overload, so this lands as
                // NoSuchMethodError only at runtime on the device.
                c.chargingCapacityKwh?.takeIf { it >= 0f }?.let {
                    telemetry.put("kwh_charged", it.toDouble())
                }
            }
            battery?.sohPercent?.takeIf { it in 0f..100f }?.let {
                telemetry.put("soh", it.toDouble())
            }

            carModel?.trim()?.takeIf { it.isNotEmpty() }?.let { telemetry.put("car_model", it) }

            val form = FormBody.Builder()
                .add("token", token)
                .add("tlm", telemetry.toString())

            val url = SEND_URL.toHttpUrl().newBuilder().apply {
                val key = apiKey.trim().ifEmpty { DEFAULT_API_KEY }
                addQueryParameter("api_key", key)
            }.build()

            val request = Request.Builder()
                .url(url)
                .post(form.build())
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    // Don't log raw body — Iternio may echo credentials back.
                    Log.w(TAG, "HTTP ${response.code}")
                    return@withContext Result.failure(
                        IllegalStateException("HTTP ${response.code}")
                    )
                }
                try {
                    val json = JSONObject(body)
                    val status = json.optString("status")
                    if (status.isNotBlank() && !status.equals("ok", ignoreCase = true)) {
                        // Iternio response may echo back token/api_key in error
                        // body — never propagate the raw body. Only surface the
                        // sanitized status code; full body stays in private logs.
                        val reason = json.optString("reason").ifBlank { status }
                        Log.w(TAG, "API status=$status reason=$reason")
                        return@withContext Result.failure(IllegalStateException("Iternio status=$status"))
                    }
                } catch (_: Exception) { /* пустой или не-JSON ответ считаем успехом при HTTP 2xx */ }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Log.w(TAG, "отправка не удалась: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Charging detection: only physical-gun signals. Whitelist autoservice
     * `gunConnectState` to {2=AC, 3=DC, 4=AC_DC, 5=VTOL} — anything else
     * (incl. 0 sentinel from cold-start window and 1=NONE) means not plugged
     * in. DiPars `chargeGunState == 2` is the same physical signal on
     * non-Leopard 3 builds.
     *
     * We deliberately do NOT use `power < 0` (regenerative braking gives
     * negative motor power but is not charging) or `chargingStatus > 0`
     * (firmware-specific values that fire spuriously on idle).
     */
    private fun isCharging(data: DiParsData, charging: ChargingReading?): Boolean {
        charging?.gunConnectState?.let { gun ->
            if (gun in DCFC_GUN_STATES || gun == 2) return true
        }
        if (data.chargeGunState == 2) return true
        return false
    }
}
