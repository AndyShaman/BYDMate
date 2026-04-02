package com.bydmate.app.data.remote

data class InsightData(
    val title: String,
    val summary: String,
    val details: String,
    val tone: String // "good", "warning", "critical"
)

data class OpenRouterModel(
    val id: String,
    val name: String,
    val pricingPrompt: Double // $/1M tokens, 0.0 = free
)
