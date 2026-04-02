package com.bydmate.app.data.remote

import android.content.Context
import android.util.Log
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class InsightsManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val openRouterClient: OpenRouterClient,
    private val tripDao: TripDao,
    private val idleDrainDao: IdleDrainDao,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "InsightsManager"
        private const val PREFS_NAME = "insights_cache"
        private const val KEY_INSIGHT_JSON = "insight_json"
        private const val KEY_INSIGHT_DATE = "insight_date"
        private const val KEY_MODELS_JSON = "models_json"
        private const val KEY_MODELS_DATE = "models_date"

        private const val SYSTEM_PROMPT = """You are an EV driving analyst for BYD Leopard 3 (Fangchengbao Bao 3), a plug-in hybrid SUV with a 72.9 kWh LFP Blade Battery.

Analyze the provided driving statistics and return actionable insights in Russian.

Focus on:
- Consumption patterns and trends (improving or degrading vs previous period)
- Anomalies (unusually high consumption trips, excessive idle drain)
- Cost optimization opportunities
- Battery health indicators (cell voltage delta, 12V battery voltage, temperature)
- Driving habit impact (short trips vs long, speed impact on consumption)
- Seasonal/temperature effects on range and consumption

Be specific — reference actual numbers from the data. Give practical advice.
Do NOT mention SoH percentage or battery capacity estimation — we cannot measure it accurately.

Return ONLY valid JSON (no markdown, no code fences):
{"title":"3-4 word headline in Russian","summary":"One sentence max 60 chars in Russian","details":"2-4 paragraphs with specific recommendations in Russian","tone":"good|warning|critical"}

tone guidelines:
- "good": everything looks normal or improving
- "warning": notable degradation, high drain, or concerning pattern
- "critical": anomaly that needs immediate attention"""
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCachedInsight(): InsightData? {
        val json = prefs.getString(KEY_INSIGHT_JSON, null) ?: return null
        return parseInsight(json)
    }

    fun getCachedDate(): String? = prefs.getString(KEY_INSIGHT_DATE, null)

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun needsRefresh(): Boolean {
        val lastDate = prefs.getString(KEY_INSIGHT_DATE, null)
        return lastDate != todayString()
    }

    suspend fun refreshIfNeeded(): InsightData? {
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_API_KEY, "")
        if (apiKey.isBlank()) return getCachedInsight()
        if (!needsRefresh()) return getCachedInsight()
        return refresh()
    }

    suspend fun refresh(): InsightData? {
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_API_KEY, "")
        if (apiKey.isBlank()) return null

        val modelId = settingsRepository.getString(SettingsRepository.KEY_OPENROUTER_MODEL, "")
        if (modelId.isBlank()) return null

        return try {
            val dataPrompt = buildDataPrompt()
            if (dataPrompt == null) {
                Log.d(TAG, "Not enough data for insights")
                return null
            }

            val response = openRouterClient.chat(apiKey, modelId, SYSTEM_PROMPT, dataPrompt)
            if (response == null) {
                Log.w(TAG, "No response from OpenRouter")
                return getCachedInsight()
            }

            // Strip markdown code fences if model wraps JSON in them
            val cleaned = response
                .trim()
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()

            val insight = parseInsight(cleaned)
            if (insight != null) {
                prefs.edit()
                    .putString(KEY_INSIGHT_JSON, cleaned)
                    .putString(KEY_INSIGHT_DATE, todayString())
                    .apply()
                Log.i(TAG, "Insight refreshed: ${insight.title}")
            } else {
                Log.w(TAG, "Failed to parse insight: $cleaned")
            }
            insight ?: getCachedInsight()
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed: ${e.message}")
            getCachedInsight()
        }
    }

    private suspend fun buildDataPrompt(): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        // Last 7 days
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val weekAgo = cal.timeInMillis

        // Previous 7 days (7-14 days ago)
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -14)
        val twoWeeksAgo = cal.timeInMillis

        val recentTrips = tripDao.getAllSnapshot().filter { it.startTs >= weekAgo }
        val prevTrips = tripDao.getAllSnapshot().filter { it.startTs in twoWeeksAgo until weekAgo }

        if (recentTrips.isEmpty() && prevTrips.isEmpty()) return@withContext null

        val sb = StringBuilder()

        // Recent period stats
        val recentKm = recentTrips.sumOf { it.distanceKm ?: 0.0 }
        val recentKwh = recentTrips.sumOf { it.kwhConsumed ?: 0.0 }
        val recentAvgCons = if (recentKm > 0) recentKwh / recentKm * 100 else 0.0
        val recentAvgSpeed = recentTrips.mapNotNull { it.avgSpeedKmh }.let {
            if (it.isNotEmpty()) it.average() else 0.0
        }
        val shortTrips = recentTrips.count { (it.distanceKm ?: 0.0) < 5.0 }

        sb.appendLine("=== Last 7 days ===")
        sb.appendLine("Trips: ${recentTrips.size}, Total: %.1f km, %.1f kWh".format(recentKm, recentKwh))
        sb.appendLine("Avg consumption: %.1f kWh/100km, Avg speed: %.0f km/h".format(recentAvgCons, recentAvgSpeed))
        sb.appendLine("Short trips (<5km): $shortTrips of ${recentTrips.size}")

        // Best/worst trip
        val tripsWithCons = recentTrips.filter { (it.kwhPer100km ?: 0.0) > 0 && (it.distanceKm ?: 0.0) > 1.0 }
        tripsWithCons.minByOrNull { it.kwhPer100km!! }?.let {
            sb.appendLine("Best trip: %.1f/100 (%.1f km, %.0f km/h)".format(it.kwhPer100km, it.distanceKm, it.avgSpeedKmh ?: 0.0))
        }
        tripsWithCons.maxByOrNull { it.kwhPer100km!! }?.let {
            sb.appendLine("Worst trip: %.1f/100 (%.1f km, %.0f km/h)".format(it.kwhPer100km, it.distanceKm, it.avgSpeedKmh ?: 0.0))
        }

        // Cost
        val recentCost = recentTrips.sumOf { it.cost ?: 0.0 }
        val currency = settingsRepository.getCurrency()
        if (recentCost > 0) {
            sb.appendLine("Cost: %.2f %s (%.2f %s/km)".format(recentCost, currency.code,
                if (recentKm > 0) recentCost / recentKm else 0.0, currency.code))
        }

        // Previous period comparison
        if (prevTrips.isNotEmpty()) {
            val prevKm = prevTrips.sumOf { it.distanceKm ?: 0.0 }
            val prevKwh = prevTrips.sumOf { it.kwhConsumed ?: 0.0 }
            val prevAvgCons = if (prevKm > 0) prevKwh / prevKm * 100 else 0.0
            sb.appendLine("\n=== Previous 7 days (comparison) ===")
            sb.appendLine("Trips: ${prevTrips.size}, Total: %.1f km, %.1f kWh".format(prevKm, prevKwh))
            sb.appendLine("Avg consumption: %.1f kWh/100km".format(prevAvgCons))
        }

        // Idle drain
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val drainKwh = idleDrainDao.getKwhSince(weekAgo)
        val drainHours = idleDrainDao.getHoursSince(weekAgo)
        if (drainKwh > 0) {
            sb.appendLine("\n=== Idle drain (7 days) ===")
            sb.appendLine("Total: %.1f kWh in %.0f hours".format(drainKwh, drainHours))
            if (drainHours > 0) {
                sb.appendLine("Rate: %.2f kWh/hour, ~%.1f kWh/day".format(drainKwh / drainHours, drainKwh / 7.0))
            }
        }

        // Live vehicle data
        val liveData = TrackingService.lastData.value
        if (liveData != null) {
            sb.appendLine("\n=== Current vehicle state ===")
            liveData.soc?.let { sb.appendLine("SOC: $it%") }
            liveData.avgBatTemp?.let { sb.appendLine("Battery temp: ${it}°C") }
            liveData.exteriorTemp?.let { sb.appendLine("Exterior temp: ${it}°C") }
            liveData.voltage12v?.let { sb.appendLine("12V battery: ${"%.1f".format(it)}V") }
            if (liveData.maxCellVoltage != null && liveData.minCellVoltage != null) {
                val delta = liveData.maxCellVoltage - liveData.minCellVoltage
                sb.appendLine("Cell voltages: ${"%.3f".format(liveData.minCellVoltage)}–${"%.3f".format(liveData.maxCellVoltage)}V (delta: ${"%.0f".format(delta * 1000)}mV)")
            }
            liveData.mileage?.let { sb.appendLine("Odometer: ${"%.1f".format(it)} km") }
        }

        // Temperature from recent trips
        val temps = recentTrips.mapNotNull { it.exteriorTemp }
        if (temps.isNotEmpty()) {
            sb.appendLine("\n=== Temperature during trips ===")
            sb.appendLine("Avg: ${temps.average().toInt()}°C, Min: ${temps.min()}°C, Max: ${temps.max()}°C")
        }

        sb.toString()
    }

    private fun parseInsight(json: String): InsightData? {
        return try {
            val obj = JSONObject(json)
            InsightData(
                title = obj.optString("title", ""),
                summary = obj.optString("summary", ""),
                details = obj.optString("details", ""),
                tone = obj.optString("tone", "good")
            )
        } catch (e: Exception) {
            Log.w(TAG, "parseInsight failed: ${e.message}")
            null
        }
    }

    // Model list caching
    suspend fun getModels(apiKey: String): List<OpenRouterModel> {
        val cachedDate = prefs.getString(KEY_MODELS_DATE, null)
        val today = todayString()

        if (cachedDate == today) {
            val cached = prefs.getString(KEY_MODELS_JSON, null)
            if (cached != null) {
                return parseModelsCache(cached)
            }
        }

        val models = openRouterClient.fetchModels(apiKey)
        if (models.isNotEmpty()) {
            cacheModels(models)
        }
        return models
    }

    private fun cacheModels(models: List<OpenRouterModel>) {
        val arr = org.json.JSONArray()
        for (m in models) {
            arr.put(JSONObject().apply {
                put("id", m.id)
                put("name", m.name)
                put("pricing", m.pricingPrompt)
            })
        }
        prefs.edit()
            .putString(KEY_MODELS_JSON, arr.toString())
            .putString(KEY_MODELS_DATE, todayString())
            .apply()
    }

    private fun parseModelsCache(json: String): List<OpenRouterModel> {
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                OpenRouterModel(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    pricingPrompt = obj.optDouble("pricing", 0.0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
