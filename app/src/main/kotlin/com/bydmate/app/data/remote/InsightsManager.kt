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
        private const val KEY_V12_HISTORY = "v12_history"   // JSON array of {date:"yyyy-MM-dd", volts:Double}, max 7 entries

        private const val SYSTEM_PROMPT = """You are an EV driving analyst for BYD Leopard 3 (Fangchengbao Bao 3), a plug-in hybrid SUV with a 72.9 kWh LFP Blade Battery.

Analyze the provided driving statistics and return actionable insights in Russian.

Focus on:
- Consumption patterns and trends (week-over-week AND month-over-month)
- Cost optimization opportunities
- Battery health indicators (cell voltage delta, 12V battery voltage, temperature)
- Driving habit impact (short trips vs long, speed impact on consumption)
- Seasonal/temperature effects on range and consumption
- Correlations the user wouldn't notice (time of day vs efficiency, temperature vs consumption)

IMPORTANT about "Stationary consumption":
This is energy used while the vehicle is RUNNING but NOT MOVING — engine warmup, waiting with A/C on, configuring the car, etc. This is NORMAL behavior, NOT a parasitic drain or anomaly. Do NOT recommend checking remote access, alarm systems, or parking mode settings. Only mention it if the proportion is notably high compared to driving consumption, and frame advice as "reduce idle time" not "diagnose a problem".

Be specific — reference actual numbers from the data. Give practical advice.
Do NOT mention SoH percentage or battery capacity estimation — we cannot measure it accurately.

Key metrics and dynamics (consumption, mileage, trends) are shown separately in the UI — do NOT repeat raw numbers. Focus ONLY on non-obvious correlations and actionable advice.

Return ONLY valid JSON (no markdown, no code fences):
{"title":"key change in 3-5 words","summary":"cause or advice, max 60 chars","insights":["insight 1","insight 2","insight 3"],"tone":"good|warning|critical"}

title: the most important change or finding — e.g. "Расход ▲8% за неделю", "Расход стабилен", "Батарея в норме". NEVER write generic titles like "Анализ эффективности", "Обзор статистики", "Итоги недели" — always the KEY FINDING with a number.
summary: the cause or actionable advice — e.g. "Много коротких поездок — прогрев забирает энергию". Max 60 chars.
Each insight: 1-2 sentences in Russian. Start with the key finding. Reference specific numbers. Max 3 insights.

tone guidelines:
- "good": everything looks normal or improving
- "warning": notable degradation or concerning pattern
- "critical": anomaly that needs immediate attention

Anti-hallucination rules (CRITICAL):
- If the data block shows fewer than 5 trips, fewer than 2 days of 12V history, or a section is entirely missing — do NOT invent trends, percentages, or correlations for that aspect. Either omit that topic or say data is insufficient.
- Never quote numbers the data block does not contain. If you are uncertain about a figure, do not mention it.
- If the data is too thin overall, return {"title":"Данных мало для анализа","summary":"Нужно больше поездок на неделе","insights":[],"tone":"good"} and nothing else."""
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

    /** Record today's 12V reading (if available). Keeps the last 7 unique dates. */
    private fun append12VSample() {
        val volts = TrackingService.lastData.value?.voltage12v ?: return
        val today = todayString()
        val raw = prefs.getString(KEY_V12_HISTORY, null)
        val arr = try { if (raw != null) org.json.JSONArray(raw) else org.json.JSONArray() } catch (_: Exception) { org.json.JSONArray() }

        // Skip if today already recorded
        for (i in 0 until arr.length()) {
            if (arr.getJSONObject(i).optString("date") == today) return
        }
        arr.put(org.json.JSONObject().apply {
            put("date", today)
            put("volts", volts)
        })
        // Trim to last 7 entries
        val trimmed = org.json.JSONArray()
        val start = (arr.length() - 7).coerceAtLeast(0)
        for (i in start until arr.length()) trimmed.put(arr.get(i))
        prefs.edit().putString(KEY_V12_HISTORY, trimmed.toString()).apply()
    }

    suspend fun refresh(): InsightData? {
        append12VSample()
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

            // Build deterministic dynamics from actual data (not LLM)
            val dynamics = buildDynamics()

            // Serialize dynamics into JSON array for caching
            val dynamicsArr = org.json.JSONArray()
            for (m in dynamics) {
                dynamicsArr.put(JSONObject().apply {
                    put("label", m.label)
                    put("current", m.current)
                    if (m.previous != null) put("previous", m.previous)
                    if (m.changePct != null) put("changePct", m.changePct)
                    put("sentiment", m.sentiment)
                    if (m.section != null) put("section", m.section)
                })
            }

            // Determine tone from data (not LLM)
            val deterministicTone = determineTone(dynamics)

            // Merge dynamics + override tone into LLM response before caching
            val mergedJson = try {
                val obj = JSONObject(cleaned)
                obj.put("dynamics", dynamicsArr)
                obj.put("tone", deterministicTone)
                obj.toString()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to merge dynamics into JSON: ${e.message}")
                cleaned
            }

            val insight = parseInsight(mergedJson)
            if (insight != null) {
                prefs.edit()
                    .putString(KEY_INSIGHT_JSON, mergedJson)
                    .putString(KEY_INSIGHT_DATE, todayString())
                    .apply()
                Log.i(TAG, "Insight refreshed: ${insight.title}")
            } else {
                Log.w(TAG, "Failed to parse insight: $mergedJson")
            }
            insight ?: getCachedInsight()
        } catch (e: Exception) {
            Log.e(TAG, "refresh failed: ${e.message}")
            getCachedInsight()
        }
    }

    private suspend fun buildDynamics(): List<DynamicMetric> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -7)
        val weekAgo = cal.timeInMillis

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -14)
        val twoWeeksAgo = cal.timeInMillis

        val allTrips = tripDao.getAllSnapshot()
        val recentTrips = allTrips.filter { it.startTs >= weekAgo }
        val prevTrips = allTrips.filter { it.startTs in twoWeeksAgo until weekAgo }

        val metrics = mutableListOf<DynamicMetric>()

        // --- Consumption ---
        val recentKm = recentTrips.sumOf { it.distanceKm ?: 0.0 }
        val recentKwh = recentTrips.sumOf { it.kwhConsumed ?: 0.0 }
        val recentCons = if (recentKm > 0) recentKwh / recentKm * 100 else 0.0

        val prevKm = prevTrips.sumOf { it.distanceKm ?: 0.0 }
        val prevKwh = prevTrips.sumOf { it.kwhConsumed ?: 0.0 }
        val prevCons = if (prevKm > 0) prevKwh / prevKm * 100 else 0.0

        if (recentCons > 0) {
            val pct = if (prevCons > 0) (recentCons - prevCons) / prevCons * 100 else null
            metrics.add(DynamicMetric(
                label = "Расход",
                current = "%.1f кВтч/100".format(recentCons),
                previous = if (prevCons > 0) "%.1f".format(prevCons) else null,
                changePct = pct,
                sentiment = consumptionSentiment(pct),
                section = "Неделя к неделе"
            ))
        }

        // --- Trips ---
        if (recentTrips.isNotEmpty()) {
            val pct = if (prevTrips.isNotEmpty())
                (recentTrips.size - prevTrips.size).toDouble() / prevTrips.size * 100 else null
            metrics.add(DynamicMetric(
                label = "Поездки",
                current = "${recentTrips.size} · ${"%.0f".format(recentKm)} км",
                previous = if (prevTrips.isNotEmpty()) "${prevTrips.size} · ${"%.0f".format(prevKm)} км" else null,
                changePct = pct,
                sentiment = "neutral"
            ))
        }

        // --- Short trips % ---
        if (recentTrips.isNotEmpty()) {
            val shortNow = recentTrips.count { (it.distanceKm ?: 0.0) < 5.0 }
            val pctNow = shortNow * 100 / recentTrips.size
            val shortPrev = if (prevTrips.isNotEmpty()) prevTrips.count { (it.distanceKm ?: 0.0) < 5.0 } else 0
            val pctPrev = if (prevTrips.isNotEmpty()) shortPrev * 100 / prevTrips.size else null

            val changePct = if (pctPrev != null && pctPrev > 0)
                (pctNow - pctPrev).toDouble() / pctPrev * 100 else null

            if (pctNow > 0) {
                metrics.add(DynamicMetric(
                    label = "Короткие < 5 км",
                    current = "$pctNow% ($shortNow/${recentTrips.size})",
                    previous = if (pctPrev != null) "$pctPrev%" else null,
                    changePct = changePct,
                    sentiment = consumptionSentiment(changePct)
                ))
            }
        }

        // --- Average distance ---
        if (recentTrips.isNotEmpty()) {
            val avgDistNow = recentKm / recentTrips.size
            val avgDistPrev = if (prevTrips.isNotEmpty()) prevKm / prevTrips.size else null

            val pct = if (avgDistPrev != null && avgDistPrev > 0)
                (avgDistNow - avgDistPrev) / avgDistPrev * 100 else null

            metrics.add(DynamicMetric(
                label = "Ср. дистанция",
                current = "%.1f км".format(avgDistNow),
                previous = if (avgDistPrev != null) "%.1f".format(avgDistPrev) else null,
                changePct = pct,
                sentiment = efficiencySentiment(pct)
            ))
        }

        // --- Stationary consumption ---
        val drainKwh = idleDrainDao.getKwhSince(weekAgo)
        val drainHours = idleDrainDao.getHoursSince(weekAgo)
        val prevDrainKwh = idleDrainDao.getKwhBetween(twoWeeksAgo, weekAgo)

        if (drainKwh > 0.1) {
            val rate = if (drainHours > 0) drainKwh / drainHours else 0.0
            val pct = if (prevDrainKwh > 0.1)
                (drainKwh - prevDrainKwh) / prevDrainKwh * 100 else null

            val drainTimeStr = if (drainHours < 1.0)
                "${"%.0f".format(drainHours * 60)} мин" else "${"%.1f".format(drainHours)} ч"
            metrics.add(DynamicMetric(
                label = "Стоянка",
                current = "${"%.1f".format(drainKwh)} кВтч · $drainTimeStr",
                previous = if (prevDrainKwh > 0.1) "${"%.1f".format(prevDrainKwh)} кВтч" else null,
                changePct = pct,
                sentiment = consumptionSentiment(pct)
            ))
        }

        metrics
    }

    // Deterministic tone based on consumption only (12V/cell-delta evaluated at display time)
    private fun determineTone(dynamics: List<DynamicMetric>): String {
        val consumption = dynamics.firstOrNull { it.label == "Расход" }
        return com.bydmate.app.data.automation.InsightToneLogic.consumptionTone(consumption?.changePct)
    }

    // up = bad (consumption, short trips, stationary)
    private fun consumptionSentiment(changePct: Double?): String = when {
        changePct == null -> "neutral"
        changePct > 0.5 -> "bad"
        changePct < -0.5 -> "good"
        else -> "neutral"
    }

    // up = good (avg distance)
    private fun efficiencySentiment(changePct: Double?): String = when {
        changePct == null -> "neutral"
        changePct > 0.5 -> "good"
        changePct < -0.5 -> "bad"
        else -> "neutral"
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

        // Last 30 days
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -30)
        val monthAgo = cal.timeInMillis

        val allTrips = tripDao.getAllSnapshot()
        val recentTrips = allTrips.filter { it.startTs >= weekAgo }
        if (recentTrips.size < 5) {
            Log.d(TAG, "Less than 5 trips in last 7 days — skip LLM call")
            return@withContext null
        }
        val prevTrips = allTrips.filter { it.startTs in twoWeeksAgo until weekAgo }
        val monthTrips = allTrips.filter { it.startTs >= monthAgo }

        if (recentTrips.isEmpty() && monthTrips.isEmpty()) return@withContext null

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

        // Monthly summary (30 days)
        if (monthTrips.size > recentTrips.size) {
            val monthKm = monthTrips.sumOf { it.distanceKm ?: 0.0 }
            val monthKwh = monthTrips.sumOf { it.kwhConsumed ?: 0.0 }
            val monthAvgCons = if (monthKm > 0) monthKwh / monthKm * 100 else 0.0
            val monthCost = monthTrips.sumOf { it.cost ?: 0.0 }
            val monthShort = monthTrips.count { (it.distanceKm ?: 0.0) < 5.0 }
            sb.appendLine("\n=== Last 30 days (monthly) ===")
            sb.appendLine("Trips: ${monthTrips.size}, Total: %.1f km, %.1f kWh".format(monthKm, monthKwh))
            sb.appendLine("Avg consumption: %.1f kWh/100km".format(monthAvgCons))
            sb.appendLine("Short trips (<5km): $monthShort of ${monthTrips.size}")
            if (monthCost > 0) {
                sb.appendLine("Total cost: %.2f %s".format(monthCost, currency.code))
            }
        }

        // Stationary consumption (vehicle running, not moving: warmup, waiting, A/C)
        val drainKwh = idleDrainDao.getKwhSince(weekAgo)
        val drainHours = idleDrainDao.getHoursSince(weekAgo)
        val drainKwhMonth = idleDrainDao.getKwhSince(monthAgo)
        val drainHoursMonth = idleDrainDao.getHoursSince(monthAgo)
        if (drainKwh > 0 || drainKwhMonth > 0) {
            sb.appendLine("\n=== Stationary consumption (engine running, not moving) ===")
            if (drainKwh > 0) {
                sb.appendLine("7 days: %.1f kWh in %.0f hours".format(drainKwh, drainHours))
                if (drainHours > 0) {
                    sb.appendLine("Rate: %.2f kWh/hour".format(drainKwh / drainHours))
                }
            }
            if (drainKwhMonth > drainKwh) {
                sb.appendLine("30 days: %.1f kWh in %.0f hours".format(drainKwhMonth, drainHoursMonth))
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

        // --- 12V 7-day history ---
        val v12Raw = prefs.getString(KEY_V12_HISTORY, null)
        if (v12Raw != null) {
            try {
                val arr = org.json.JSONArray(v12Raw)
                val values = (0 until arr.length()).map { arr.getJSONObject(it).getDouble("volts") }
                if (values.size >= 2) {
                    val avg = values.average()
                    val minV = values.min()
                    val maxV = values.max()
                    // Simple trend: last half vs first half
                    val half = values.size / 2
                    val firstAvg = values.take(half).average()
                    val lastAvg = values.takeLast(half).average()
                    val delta = lastAvg - firstAvg
                    val trend = when {
                        delta > 0.1 -> "rising"
                        delta < -0.1 -> "falling"
                        else -> "stable"
                    }
                    sb.appendLine("\n=== 12V battery history (last ${values.size} days) ===")
                    sb.appendLine("Avg: %.2fV, Min: %.2fV, Max: %.2fV, Trend: %s (Δ%+.2fV)".format(avg, minV, maxV, trend, delta))
                }
            } catch (_: Exception) { /* ignore malformed cache */ }
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

            // Parse dynamics (structured metrics with trends)
            val dynamicsList = mutableListOf<DynamicMetric>()
            val dynArr = obj.optJSONArray("dynamics")
            if (dynArr != null) {
                for (i in 0 until dynArr.length()) {
                    val d = dynArr.getJSONObject(i)
                    dynamicsList.add(DynamicMetric(
                        label = d.getString("label"),
                        current = d.getString("current"),
                        previous = d.optString("previous", null),
                        changePct = if (d.has("changePct")) d.getDouble("changePct") else null,
                        sentiment = d.optString("sentiment", "neutral"),
                        section = d.optString("section", null)
                    ))
                }
            }

            // Parse insights — array of strings (new) or single string (legacy)
            val insightsList = mutableListOf<String>()
            val insightsVal = obj.opt("insights")
            when (insightsVal) {
                is org.json.JSONArray -> {
                    for (i in 0 until insightsVal.length()) {
                        insightsList.add(insightsVal.getString(i))
                    }
                }
                is String -> {
                    if (insightsVal.isNotBlank()) {
                        insightsVal.split("\n\n").filter { it.isNotBlank() }.forEach {
                            insightsList.add(it.trim())
                        }
                    }
                }
            }

            // Legacy fallback: "details" field from old cache
            if (insightsList.isEmpty()) {
                val details = obj.optString("details", "")
                if (details.isNotBlank()) {
                    details.split("\n\n").filter { it.isNotBlank() }.forEach {
                        insightsList.add(it.trim())
                    }
                }
            }

            InsightData(
                title = obj.optString("title", ""),
                summary = obj.optString("summary", ""),
                dynamics = dynamicsList,
                insights = insightsList,
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
