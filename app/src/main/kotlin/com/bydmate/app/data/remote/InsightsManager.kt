package com.bydmate.app.data.remote

import android.content.Context
import android.util.Log
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.ChargeDao
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
    private val chargeDao: ChargeDao,
    private val settingsRepository: SettingsRepository,
    private val localePreferences: LocalePreferences = LocalePreferences(context)
) {
    companion object {
        private const val TAG = "InsightsManager"
        private const val PREFS_NAME = "insights_cache"
        private const val KEY_INSIGHT_JSON = "insight_json"
        private const val KEY_INSIGHT_DATE = "insight_date"
        private const val KEY_MODELS_JSON = "models_json"
        private const val KEY_MODELS_DATE = "models_date"
        private const val KEY_V12_HISTORY = "v12_history"   // JSON array of {date:"yyyy-MM-dd", volts:Double}, max 7 entries
        private val LANGUAGE_CACHE_KEY = Regex("^insight_\\d{4}-\\d{2}-\\d{2}_[a-z]{2}_\\d+$")

        @JvmStatic
        fun cacheKey(date: String, lang: String, periodDays: Int = 7): String = "insight_${date}_${lang}_${periodDays}"
    }

    private val prefs by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getCachedInsight(periodDays: Int = 7): InsightData? {
        val json = prefs.getString(cacheKey(todayString(), currentLanguage(), periodDays), null) ?: return null
        return parseInsight(json)
    }

    /** Cached insight with local-only dynamics (e.g. night idle row) applied at read time. */
    suspend fun getDisplayInsight(periodDays: Int = 7): InsightData? {
        val cached = getCachedInsight(periodDays) ?: return null
        return enrichLocalInsight(cached, periodDays)
    }

    fun getCachedDate(periodDays: Int = 7): String? {
        val today = todayString()
        return if (prefs.contains(cacheKey(today, currentLanguage(), periodDays))) today else null
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // Return the actually selected language so every locale (pt, zh, ...) gets its
    // own localized insight resources AND its own cache key. Collapsing everything
    // but "en" to "ru" made pt/zh reuse the Russian resources and cache entry.
    private fun currentLanguage(): String =
        localePreferences.getLanguage() ?: "ru"

    fun needsRefresh(periodDays: Int = 7): Boolean {
        return !prefs.contains(cacheKey(todayString(), currentLanguage(), periodDays))
    }

    fun migrateLegacyCache() {
        val keysToRemove = prefs.all.keys.filter { key ->
            key.startsWith("insight_") && !LANGUAGE_CACHE_KEY.matches(key)
        }
        if (keysToRemove.isEmpty()) return

        val editor = prefs.edit()
        keysToRemove.forEach { editor.remove(it) }
        editor.apply()
    }

    suspend fun refreshIfNeeded(periodDays: Int = 7): InsightData? {
        if (!needsRefresh(periodDays)) return getCachedInsight(periodDays)
        return refresh(periodDays)
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

    /** Generate (or serve from today's cache) the insight for a 7- or 30-day window. */
    suspend fun refresh(periodDays: Int = 7): InsightData? {
        append12VSample()
        return refreshLocal(periodDays)
    }

    private suspend fun refreshLocal(periodDays: Int): InsightData? {
        return try {
            val lang = currentLanguage()
            val stats = collectStats(periodDays)
            if (stats == null) {
                Log.d(TAG, "Not enough data for local insights")
                return null
            }
            val dynamics = buildDynamics(lang, periodDays).toMutableList()
            insertNightDrainMetricIfLocal(dynamics, lang, periodDays)
            val tone = determineTone(dynamics)
            val text = LocalInsightEngine.generate(stats, localizedResources(lang), periodDays)
            val insight = InsightData(
                title = text.title,
                summary = text.summary,
                dynamics = dynamics,
                insights = text.insights,
                tone = tone,
            )
            cacheInsight(insight, lang, periodDays)
            Log.i(TAG, "Local insight refreshed: ${insight.title}")
            insight
        } catch (e: Exception) {
            Log.e(TAG, "refreshLocal failed: ${e.message}")
            getCachedInsight(periodDays)?.let { enrichLocalInsight(it, periodDays) }
        }
    }

    private fun cacheInsight(insight: InsightData, lang: String, periodDays: Int) {
        val dynamicsArr = org.json.JSONArray()
        for (m in insight.dynamics) {
            dynamicsArr.put(JSONObject().apply {
                put("label", m.label)
                put("current", m.current)
                if (m.previous != null) put("previous", m.previous)
                if (m.changePct != null) put("changePct", m.changePct)
                put("sentiment", m.sentiment)
                if (m.section != null) put("section", m.section)
                if (m.kind.isNotEmpty()) put("kind", m.kind)
            })
        }
        val obj = JSONObject().apply {
            put("title", insight.title)
            put("summary", insight.summary)
            put("tone", insight.tone)
            put("dynamics", dynamicsArr)
            put("insights", org.json.JSONArray(insight.insights))
        }
        prefs.edit()
            .putString(cacheKey(todayString(), lang, periodDays), obj.toString())
            .apply()
    }

    private fun localizedResources(lang: String): android.content.res.Resources {
        val config = android.content.res.Configuration(context.resources.configuration)
        config.setLocale(Locale(lang))
        return context.createConfigurationContext(config).resources
    }

    /** Deterministic dynamics for an arbitrary window (7 = week, 30 = month). Used by the
     *  voice agent's get_stats_summary tool, and internally by refreshLocal() for the
     *  dashboard's own period toggle. */
    suspend fun dynamicsFor(periodDays: Int): List<DynamicMetric> =
        buildDynamics(currentLanguage(), periodDays)

    private suspend fun buildDynamics(lang: String, periodDays: Int = 7): List<DynamicMetric> = withContext(Dispatchers.IO) {
        val res = localizedResources(lang)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -periodDays)
        val periodStart = cal.timeInMillis

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -2 * periodDays)
        val prevStart = cal.timeInMillis

        val allTrips = tripDao.getAllSnapshot()
        val recentTrips = allTrips.filter { it.startTs >= periodStart }
        val prevTrips = allTrips.filter { it.startTs in prevStart until periodStart }

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
                label = res.getString(com.bydmate.app.R.string.insight_metric_consumption),
                current = res.getString(com.bydmate.app.R.string.insight_unit_kwh_per_100, recentCons),
                previous = if (prevCons > 0) res.getString(com.bydmate.app.R.string.insight_unit_kwh_per_100_prev, prevCons) else null,
                changePct = pct,
                sentiment = consumptionSentiment(pct),
                section = res.getString(
                    if (periodDays >= 28) com.bydmate.app.R.string.insight_section_month_to_month
                    else com.bydmate.app.R.string.insight_section_week_to_week),
                kind = "consumption"
            ))
        }

        // --- Trips ---
        if (recentTrips.isNotEmpty()) {
            val pct = if (prevTrips.isNotEmpty())
                (recentTrips.size - prevTrips.size).toDouble() / prevTrips.size * 100 else null
            metrics.add(DynamicMetric(
                label = res.getString(com.bydmate.app.R.string.insight_metric_trips),
                current = res.getString(com.bydmate.app.R.string.insight_trips_value, recentTrips.size, "%.0f".format(recentKm)),
                previous = if (prevTrips.isNotEmpty()) res.getString(com.bydmate.app.R.string.insight_trips_prev, prevTrips.size, "%.0f".format(prevKm)) else null,
                changePct = pct,
                sentiment = "neutral",
                kind = "trips"
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
                    label = res.getString(com.bydmate.app.R.string.insight_metric_short_trips),
                    current = res.getString(com.bydmate.app.R.string.insight_short_value, pctNow, shortNow, recentTrips.size),
                    previous = if (pctPrev != null) res.getString(com.bydmate.app.R.string.insight_short_prev, pctPrev) else null,
                    changePct = changePct,
                    sentiment = consumptionSentiment(changePct),
                    kind = "short_trips"
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
                label = res.getString(com.bydmate.app.R.string.insight_metric_avg_distance),
                current = res.getString(com.bydmate.app.R.string.insight_avg_distance_value, "%.1f".format(avgDistNow)),
                previous = if (avgDistPrev != null) "%.1f".format(avgDistPrev) else null,
                changePct = pct,
                sentiment = efficiencySentiment(pct),
                kind = "avg_distance"
            ))
        }

        // --- Stationary consumption ---
        val drainKwh = idleDrainDao.getKwhSince(periodStart)
        val drainHours = idleDrainDao.getHoursSince(periodStart)
        val prevDrainKwh = idleDrainDao.getKwhBetween(prevStart, periodStart)

        if (drainKwh > 0.1) {
            val rate = if (drainHours > 0) drainKwh / drainHours else 0.0
            val pct = if (prevDrainKwh > 0.1)
                (drainKwh - prevDrainKwh) / prevDrainKwh * 100 else null

            val drainTimeStr = if (drainHours < 1.0)
                res.getString(com.bydmate.app.R.string.insight_idle_minutes, "%.0f".format(drainHours * 60))
            else res.getString(com.bydmate.app.R.string.insight_idle_hours, "%.1f".format(drainHours))
            metrics.add(DynamicMetric(
                label = res.getString(com.bydmate.app.R.string.insight_metric_idle),
                current = res.getString(com.bydmate.app.R.string.insight_idle_value, "%.1f".format(drainKwh), drainTimeStr),
                previous = if (prevDrainKwh > 0.1) res.getString(com.bydmate.app.R.string.insight_idle_prev, "%.1f".format(prevDrainKwh)) else null,
                changePct = pct,
                sentiment = consumptionSentiment(pct),
                kind = "idle"
            ))
        }

        metrics
    }

    private suspend fun enrichLocalInsight(insight: InsightData, periodDays: Int): InsightData {
        if (insight.dynamics.any { it.kind == "night_idle" }) {
            return insight
        }
        val metrics = insight.dynamics.toMutableList()
        insertNightDrainMetricIfLocal(metrics, lang = currentLanguage(), periodDays)
        return insight.copy(dynamics = metrics)
    }

    private suspend fun insertNightDrainMetricIfLocal(metrics: MutableList<DynamicMetric>, lang: String, periodDays: Int) {
        if (metrics.any { it.kind == "night_idle" }) return
        insertNightDrainMetric(metrics, lang, periodDays)
    }

    private suspend fun insertNightDrainMetric(metrics: MutableList<DynamicMetric>, lang: String, periodDays: Int) {
        val metric = buildNightDrainMetric(lang, periodDays)
        val idleIdx = metrics.indexOfFirst { it.kind == "idle" }
        if (idleIdx >= 0) {
            metrics.add(idleIdx + 1, metric)
        } else {
            metrics.add(metric)
        }
    }

    /** Same current/previous window convention as buildDynamics()/collectStats() (periodDays,
     *  2 * periodDays) -- was hard-coded to 7/14 days regardless of the caller's period, so a
     *  30-day insight could carry a weekly night-idle row. */
    private suspend fun buildNightDrainMetric(lang: String, periodDays: Int): DynamicMetric {
        val res = localizedResources(lang)
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -periodDays)
        val weekAgo = cal.timeInMillis

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -2 * periodDays)
        val twoWeeksAgo = cal.timeInMillis

        val weekDrains = idleDrainDao.getSince(weekAgo)
        val nightKwh = InsightStatsAggregator.nightDrainKwh(weekDrains)
        val drainKwh = idleDrainDao.getKwhSince(weekAgo)
        val share = if (drainKwh > 0.1) (nightKwh / drainKwh * 100).toInt() else 0

        val prevDrains = idleDrainDao.getSince(twoWeeksAgo).filter { it.startTs < weekAgo }
        val prevNightKwh = InsightStatsAggregator.nightDrainKwh(prevDrains)
        val changePct = if (prevNightKwh > 0.1) {
            (nightKwh - prevNightKwh) / prevNightKwh * 100
        } else null

        return DynamicMetric(
            label = res.getString(com.bydmate.app.R.string.insight_metric_night_idle),
            current = res.getString(
                com.bydmate.app.R.string.insight_night_idle_value,
                nightKwh,
                share,
            ),
            previous = if (prevNightKwh > 0.1) {
                res.getString(com.bydmate.app.R.string.insight_night_idle_prev, prevNightKwh)
            } else null,
            changePct = changePct,
            sentiment = consumptionSentiment(changePct),
            kind = "night_idle",
        )
    }

    // Deterministic tone based on consumption only (12V/cell-delta evaluated at display time)
    private fun determineTone(dynamics: List<DynamicMetric>): String {
        val consumption = dynamics.firstOrNull { it.kind == "consumption" }
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

    suspend fun collectStats(periodDays: Int = 7): InsightStats? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -periodDays)
        val weekAgo = cal.timeInMillis

        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -2 * periodDays)
        val twoWeeksAgo = cal.timeInMillis

        val allTrips = tripDao.getAllSnapshot()
        val recentTrips = allTrips.filter { it.startTs >= weekAgo }
        if (recentTrips.size < 5) return@withContext null

        val prevTrips = allTrips.filter { it.startTs in twoWeeksAgo until weekAgo }

        val recentKm = recentTrips.sumOf { it.distanceKm ?: 0.0 }
        val recentKwh = recentTrips.sumOf { it.kwhConsumed ?: 0.0 }
        val recentAvgCons = if (recentKm > 0) recentKwh / recentKm * 100 else 0.0
        val recentAvgSpeed = recentTrips.mapNotNull { it.avgSpeedKmh }.let {
            if (it.isNotEmpty()) it.average() else 0.0
        }
        val shortTripCount = recentTrips.count { (it.distanceKm ?: 0.0) < 5.0 }

        val prevKm = prevTrips.sumOf { it.distanceKm ?: 0.0 }
        val prevKwh = prevTrips.sumOf { it.kwhConsumed ?: 0.0 }
        val prevAvgCons = if (prevKm > 0) prevKwh / prevKm * 100 else 0.0
        val consumptionChangePct = if (prevAvgCons > 0) {
            (recentAvgCons - prevAvgCons) / prevAvgCons * 100
        } else null

        val tripsWithCons = recentTrips.filter {
            (it.kwhPer100km ?: 0.0) > 0 && (it.distanceKm ?: 0.0) > 1.0
        }
        val best = tripsWithCons.minByOrNull { it.kwhPer100km!! }
        val worst = tripsWithCons.maxByOrNull { it.kwhPer100km!! }

        val recentCost = recentTrips.sumOf { it.cost ?: 0.0 }
        val currency = settingsRepository.getCurrency()

        val drainKwh = idleDrainDao.getKwhSince(weekAgo)
        val drainHours = idleDrainDao.getHoursSince(weekAgo)

        val liveData = TrackingService.lastData.value
        val cellDeltaMv = if (liveData?.maxCellVoltage != null && liveData.minCellVoltage != null) {
            (liveData.maxCellVoltage - liveData.minCellVoltage) * 1000.0
        } else null

        var v12TrendDelta: Double? = null
        var v12Min: Double? = null
        val v12Raw = prefs.getString(KEY_V12_HISTORY, null)
        if (v12Raw != null) {
            try {
                val arr = org.json.JSONArray(v12Raw)
                val values = (0 until arr.length()).map { arr.getJSONObject(it).getDouble("volts") }
                if (values.size >= 2) {
                    v12Min = values.min()
                    val half = values.size / 2
                    v12TrendDelta = values.takeLast(half).average() - values.take(half).average()
                }
            } catch (_: Exception) { /* ignore */ }
        }

        val temps = recentTrips.mapNotNull { it.exteriorTemp }
        val avgExteriorTemp = if (temps.isNotEmpty()) temps.average().toInt() else null

        val weekCharges = chargeDao.getCompletedSince(weekAgo)
        val charging = InsightStatsAggregator.chargingWeek(weekCharges)
        val weekDrains = idleDrainDao.getSince(weekAgo)
        val nightDrainKwh = InsightStatsAggregator.nightDrainKwh(weekDrains)
        val nightDrainSharePct = if (drainKwh > 0.1) nightDrainKwh / drainKwh * 100.0 else null

        InsightStats(
            recentTripCount = recentTrips.size,
            recentKm = recentKm,
            recentKwh = recentKwh,
            recentAvgCons = recentAvgCons,
            recentAvgSpeed = recentAvgSpeed,
            shortTripCount = shortTripCount,
            prevTripCount = prevTrips.size,
            prevKm = prevKm,
            prevAvgCons = prevAvgCons,
            consumptionChangePct = consumptionChangePct,
            bestTripCons = best?.kwhPer100km,
            bestTripKm = best?.distanceKm,
            worstTripCons = worst?.kwhPer100km,
            worstTripKm = worst?.distanceKm,
            recentCost = recentCost,
            currencyCode = currency.code,
            drainKwh = drainKwh,
            drainHours = drainHours,
            voltage12v = liveData?.voltage12v,
            v12TrendDelta = v12TrendDelta,
            v12Min = v12Min,
            cellDeltaMv = cellDeltaMv,
            avgExteriorTemp = avgExteriorTemp,
            acKwhWeek = charging.acKwh,
            dcKwhWeek = charging.dcKwh,
            acSessionCount = charging.acSessions,
            dcSessionCount = charging.dcSessions,
            acCostPerKwh = charging.acCostPerKwh,
            dcCostPerKwh = charging.dcCostPerKwh,
            chargeCostWeek = charging.totalCost,
            nightDrainKwh = nightDrainKwh,
            nightDrainSharePct = nightDrainSharePct,
        )
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
                        section = d.optString("section", null),
                        kind = d.optString("kind", "")
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
