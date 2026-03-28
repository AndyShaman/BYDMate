package com.bydmate.app.data.remote

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
        private const val BASE_URL = "http://localhost:8988/api/getDiPars"
        private const val TEMPLATE =
            "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}" +
            "|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}" +
            "|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}" +
            "|ChargingStatus:{充电状态}"
    }

    suspend fun fetch(): DiParsData? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL?text=$TEMPLATE"
            val request = Request.Builder().url(url).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) return@withContext null

            val valStr = json.optString("val", "")
            parse(valStr)
        } catch (e: Exception) {
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
