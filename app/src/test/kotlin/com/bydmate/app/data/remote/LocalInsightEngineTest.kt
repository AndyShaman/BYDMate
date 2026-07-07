package com.bydmate.app.data.remote

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class LocalInsightEngineTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun res(lang: String) = context
        .createConfigurationContext(
            android.content.res.Configuration(context.resources.configuration).apply {
                setLocale(java.util.Locale(lang))
            },
        )
        .resources

    @Test
    fun `stable consumption produces stable title`() {
        val stats = baseStats(consumptionChangePct = 2.0)
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.title.contains("стабилен", ignoreCase = true))
        assertEquals(18.0, extractAvgFromSummary(result.summary), 0.1)
    }

    @Test
    fun `consumption rise with short trips`() {
        val stats = baseStats(
            consumptionChangePct = 12.0,
            shortTripCount = 6,
            recentTripCount = 10,
        )
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.title.contains("▲"))
        assertTrue(result.summary.contains("коротк", ignoreCase = true))
        assertTrue(result.insights.any { it.contains("короче 5 км") })
    }

    @Test
    fun `consumption drop title`() {
        val stats = baseStats(consumptionChangePct = -8.0)
        val result = LocalInsightEngine.generate(stats, res("en"))
        assertTrue(result.title.contains("down", ignoreCase = true))
    }

    @Test
    fun `12v critical overrides consumption title`() {
        val stats = baseStats(consumptionChangePct = 20.0, voltage12v = 11.5)
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.title.contains("12V", ignoreCase = true))
    }

    @Test
    fun `idle drain insight when significant`() {
        val stats = baseStats(
            recentKwh = 20.0,
            drainKwh = 5.0,
            drainHours = 4.0,
        )
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.insights.any { it.contains("стоянке") || it.contains("кВт") })
    }

    @Test
    fun `not enough data template`() {
        val result = LocalInsightEngine.notEnoughData(res("ru"))
        assertTrue(result.insights.isEmpty())
        assertTrue(result.title.isNotBlank())
    }

    @Test
    fun `night idle insight when significant`() {
        val stats = baseStats().copy(
            drainKwh = 6.0,
            nightDrainKwh = 4.5,
            nightDrainSharePct = 75.0,
        )
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(
            result.title.contains("ночн", ignoreCase = true) ||
                result.insights.any { it.contains("Ночью") || it.contains("22:00") },
        )
    }

    @Test
    fun `dc more expensive than ac insight`() {
        val stats = baseStats().copy(
            acKwhWeek = 15.0,
            dcKwhWeek = 25.0,
            acSessionCount = 2,
            dcSessionCount = 3,
            acCostPerKwh = 5.0,
            dcCostPerKwh = 12.0,
            currencyCode = "RUB",
        )
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.insights.any { it.contains("DC", ignoreCase = true) })
    }

    @Test
    fun `dc heavy usage insight`() {
        val stats = baseStats().copy(
            acKwhWeek = 5.0,
            dcKwhWeek = 40.0,
            dcSessionCount = 4,
        )
        val result = LocalInsightEngine.generate(stats, res("en"))
        assertTrue(result.insights.any { it.contains("DC", ignoreCase = true) })
    }

    @Test
    fun `consumption drop adds improvement bullet`() {
        val stats = baseStats(consumptionChangePct = -10.0)
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.insights.any { it.contains("снизился", ignoreCase = true) })
    }

    @Test
    fun `returns up to five insights`() {
        val stats = baseStats(
            consumptionChangePct = 12.0,
            shortTripCount = 6,
            recentTripCount = 10,
            recentKwh = 25.0,
            drainKwh = 5.0,
            drainHours = 3.0,
        ).copy(
            recentKm = 320.0,
            prevKm = 200.0,
            recentAvgSpeed = 80.0,
            recentCost = 500.0,
            nightDrainKwh = 4.5,
            nightDrainSharePct = 75.0,
            acKwhWeek = 20.0,
            dcKwhWeek = 30.0,
            acSessionCount = 2,
            dcSessionCount = 3,
            acCostPerKwh = 5.0,
            dcCostPerKwh = 12.0,
            avgExteriorTemp = -2,
        )
        val result = LocalInsightEngine.generate(stats, res("ru"))
        assertTrue(result.insights.size >= 4)
        assertTrue(result.insights.size <= 5)
    }

    private fun baseStats(
        consumptionChangePct: Double? = null,
        shortTripCount: Int = 2,
        recentTripCount: Int = 10,
        recentKwh: Double = 18.0,
        drainKwh: Double = 0.0,
        drainHours: Double = 0.0,
        voltage12v: Double? = 12.6,
    ) = InsightStats(
        recentTripCount = recentTripCount,
        recentKm = 100.0,
        recentKwh = recentKwh,
        recentAvgCons = 18.0,
        recentAvgSpeed = 45.0,
        shortTripCount = shortTripCount,
        prevTripCount = 8,
        prevKm = 90.0,
        prevAvgCons = if (consumptionChangePct != null) {
            18.0 / (1.0 + consumptionChangePct / 100.0)
        } else 17.0,
        consumptionChangePct = consumptionChangePct,
        bestTripCons = 14.0,
        bestTripKm = 30.0,
        worstTripCons = 24.0,
        worstTripKm = 5.0,
        recentCost = 0.0,
        currencyCode = "RUB",
        drainKwh = drainKwh,
        drainHours = drainHours,
        voltage12v = voltage12v,
        v12TrendDelta = null,
        v12Min = null,
        cellDeltaMv = null,
        avgExteriorTemp = null,
    )

    private fun extractAvgFromSummary(summary: String): Double {
        val match = Regex("""(\d+[.,]\d+)""").find(summary)
        return match?.groupValues?.get(1)?.replace(',', '.')?.toDouble() ?: 0.0
    }
}
