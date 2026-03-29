package com.bydmate.app.data.remote

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class DiParsData(
    val soc: Int?,
    val speed: Int?,
    val mileage: Double?,
    val power: Double?,
    val chargeGunState: Int?,
    val maxBatTemp: Int?,
    val avgBatTemp: Int?,
    val minBatTemp: Int?,
    val chargingStatus: Int?,
    val batteryCapacityKwh: Double?,
    val totalElecConsumption: Double?,
    val voltage12v: Double?,
    val maxCellVoltage: Double?,
    val minCellVoltage: Double?,
    val exteriorTemp: Int?
)

@Singleton
class DiParsClient @Inject constructor(
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "DiParsClient"
        private const val BASE_URL = "http://127.0.0.1:8988/api/getDiPars"
        private const val TEMPLATE =
            "SOC:{电量百分比}|Speed:{车速}|Mileage:{里程}|Power:{发动机功率}" +
            "|ChargeGun:{充电枪插枪状态}|MaxBatTemp:{最高电池温度}" +
            "|AvgBatTemp:{平均电池温度}|MinBatTemp:{最低电池温度}" +
            "|ChargingStatus:{充电状态}" +
            "|BatCapacity:{电池容量}|TotalElecCon:{总电耗}" +
            "|Voltage12V:{蓄电池电压}|MaxCellV:{最高电池电压}" +
            "|MinCellV:{最低电池电压}|ExtTemp:{车外温度}"
    }

    suspend fun fetch(): DiParsData? = withContext(Dispatchers.IO) {
        try {
            val httpUrl = BASE_URL.toHttpUrl().newBuilder()
                .addQueryParameter("text", TEMPLATE)
                .build()
            val request = Request.Builder().url(httpUrl).build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()

            if (body == null) {
                Log.w(TAG, "Response body is null")
                return@withContext null
            }

            val json = JSONObject(body)
            if (!json.optBoolean("success", false)) {
                Log.w(TAG, "success=false: $body")
                return@withContext null
            }

            parse(json.optString("val", ""))
        } catch (e: Exception) {
            Log.e(TAG, "fetch failed: ${e.message}")
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

        // Cell voltages: DiPlus already divides raw millivolts by 1000 → value is in volts
        // Treat <= 0 as unavailable (DiPlus returns 0.0 when BMS hasn't reported)
        val maxCellRaw = map["MaxCellV"]?.toDoubleOrNull()
        val minCellRaw = map["MinCellV"]?.toDoubleOrNull()
        val maxCell = maxCellRaw?.takeIf { it > 0.5 }
        val minCell = minCellRaw?.takeIf { it > 0.5 }

        // 12V: may come as millivolts (>100) or volts (<100); 0 = unavailable
        val v12Raw = map["Voltage12V"]?.toDoubleOrNull()
        val v12 = when {
            v12Raw == null || v12Raw <= 0.0 -> null
            v12Raw > 100.0 -> v12Raw / 1000.0  // millivolts → volts
            else -> v12Raw                       // already in volts
        }

        Log.d(TAG, "Raw DiPlus: MaxCellV=${map["MaxCellV"]}, MinCellV=${map["MinCellV"]}, " +
            "Voltage12V=${map["Voltage12V"]}, ExtTemp=${map["ExtTemp"]}, " +
            "BatCapacity=${map["BatCapacity"]}, AvgBatTemp=${map["AvgBatTemp"]}")
        Log.d(TAG, "Parsed: maxCell=$maxCell, minCell=$minCell, v12=$v12")

        return DiParsData(
            soc = map["SOC"]?.toIntOrNull(),
            speed = map["Speed"]?.toIntOrNull(),
            mileage = map["Mileage"]?.toDoubleOrNull()?.let { it / 10.0 },
            power = map["Power"]?.toDoubleOrNull(),
            chargeGunState = map["ChargeGun"]?.toIntOrNull(),
            maxBatTemp = map["MaxBatTemp"]?.toIntOrNull(),
            avgBatTemp = map["AvgBatTemp"]?.toIntOrNull(),
            minBatTemp = map["MinBatTemp"]?.toIntOrNull(),
            chargingStatus = map["ChargingStatus"]?.toIntOrNull(),
            batteryCapacityKwh = map["BatCapacity"]?.toDoubleOrNull(),
            totalElecConsumption = map["TotalElecCon"]?.toDoubleOrNull(),
            voltage12v = v12,
            maxCellVoltage = maxCell,
            minCellVoltage = minCell,
            exteriorTemp = map["ExtTemp"]?.toIntOrNull()
        )
    }
}
