package com.bydmate.app.data.remote

data class InsightData(
    val title: String,
    val summary: String,
    val facts: String,   // 2-3 bullet points with key metrics
    val insights: String, // 2-3 paragraphs with recommendations
    val details: String,  // legacy fallback (facts + insights combined)
    val tone: String // "good", "warning", "critical"
)

data class OpenRouterModel(
    val id: String,
    val name: String,
    val pricingPrompt: Double // $/1M tokens, 0.0 = free
)
