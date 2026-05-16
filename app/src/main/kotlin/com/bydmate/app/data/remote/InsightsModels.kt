package com.bydmate.app.data.remote

data class DynamicMetric(
    val label: String,         // localized: "Расход" / "Consumption"
    val current: String,       // localized: "26.7 кВтч/100" / "26.7 kWh/100"
    val previous: String?,     // "25.4" or null if no prev data
    val changePct: Double?,    // 5.1 or null
    val sentiment: String,     // "good", "bad", "neutral"
    val section: String? = null, // section header shown above this row
    val kind: String = ""      // stable internal id: "consumption", "trips", etc. (locale-agnostic)
)

data class InsightData(
    val title: String,
    val summary: String,
    val dynamics: List<DynamicMetric>,
    val insights: List<String>,
    val tone: String // "good", "warning", "critical"
)

data class OpenRouterModel(
    val id: String,
    val name: String,
    val pricingPrompt: Double // $/1M tokens, 0.0 = free
)
