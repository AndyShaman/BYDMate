package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class DiParsData(
    val soc: Int?,           // Battery SOC %
    val speed: Int?,         // km/h
    val mileage: Double?,    // km (raw value / 10)
    val power: Double?,      // kW (negative = charging)
    val chargeGunState: Int?, // 2 = connected
    val maxBatTemp: Int?,    // C
    val avgBatTemp: Int?,    // C
    val minBatTemp: Int?,    // C
    val chargingStatus: Int?
)

@Singleton
class DiParsClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DiParsClient"
        // BydConnect uses 127.0.0.1, not localhost — localhost may not resolve on DiLink
        private const val BASE_URL = "http://127.0.0.1:8988/api/getDiPars"
        private const val TEMPLATE =
            "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}" +
            "|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}" +
            "|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}" +
            "|ChargingStatus:{充电状态}"
    }

    @Volatile
    private var firstCall = true

    suspend fun fetch(): DiParsData? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL?text=$TEMPLATE"
            if (firstCall) {
                Log.d(TAG, "First DiPars request URL: $url")
                firstCall = false
            }
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            Log.d(TAG, "Response code=${response.code}, bodyLength=${body?.length ?: 0}")

            if (body == null) {
                Log.w(TAG, "Response body is null")
                return@withContext null
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                Log.w(TAG, "DiPars response success=false, body=$body")
                return@withContext null
            }

            val valStr = json.optString("val", "")
            val data = parse(valStr)
            Log.d(TAG, "Parsed: soc=${data.soc} speed=${data.speed} mileage=${data.mileage} " +
                "power=${data.power} chargeGun=${data.chargeGunState} " +
                "batTemp=${data.minBatTemp}/${data.avgBatTemp}/${data.maxBatTemp} " +
                "chargingStatus=${data.chargingStatus}")
            data
        } catch (e: Exception) {
            Log.e(TAG, "DiPars fetch failed: ${e.message}", e)
            null
        }
    }

    private fun parse(raw: String): DiParsData {
        val map = mutableMapOf<String, String>()
        raw.split("|").forEach { part ->
            val colonIdx = part.indexOf(':')
            if (colonIdx > 0) {
                val key = part.substring(0, colonIdx)
                val value = part.substring(colonIdx + 1)
                map[key] = value
            }
        }
        return DiParsData(
            soc = map["SOC"]?.toIntOrNull(),
            speed = map["Speed"]?.toIntOrNull(),
            mileage = map["Mileage"]?.toDoubleOrNull()?.let { it / 10.0 },
            power = map["Power"]?.toDoubleOrNull(),
            chargeGunState = map["ChargeGun"]?.toIntOrNull(),
            maxBatTemp = map["MaxBatTemp"]?.toIntOrNull(),
            avgBatTemp = map["AvgBatTemp"]?.toIntOrNull(),
            minBatTemp = map["MinBatTemp"]?.toIntOrNull(),
            chargingStatus = map["ChargingStatus"]?.toIntOrNull()
        )
    }
}
