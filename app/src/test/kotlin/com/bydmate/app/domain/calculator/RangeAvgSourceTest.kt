package com.bydmate.app.domain.calculator

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class RangeAvgSourceTest {

    private fun newSource(
        historical: Double,
        live: Double?,
        sessionKm: Double,
    ): RangeAvgSource = RangeAvgSource(
        historicalProvider = { historical },
        liveAvgProvider = { live },
        sessionKmProvider = { sessionKm },
    )

    @Test fun `no live data returns historical`() = runBlocking {
        val s = newSource(historical = 20.0, live = null, sessionKm = 0.0)
        assertEquals(20.0, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `session under 3 km gives zero live weight`() = runBlocking {
        // even though live = 50, session < 3 km → weight 0 → historical wins
        val s = newSource(historical = 20.0, live = 50.0, sessionKm = 2.0)
        assertEquals(20.0, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `session at 3 km still zero weight`() = runBlocking {
        // Boundary: at exactly 3 km, weight = 0 (clamp).
        val s = newSource(historical = 20.0, live = 50.0, sessionKm = 3.0)
        assertEquals(20.0, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `session at 14 km gives 25 percent live weight`() = runBlocking {
        // (14-3)/22 × 0.5 = 0.25
        val s = newSource(historical = 20.0, live = 30.0, sessionKm = 14.0)
        // 0.25*30 + 0.75*20 = 22.5
        assertEquals(22.5, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `session at 25 km hits 50 percent live weight`() = runBlocking {
        val s = newSource(historical = 20.0, live = 30.0, sessionKm = 25.0)
        assertEquals(25.0, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `session over 25 km capped at 50 percent`() = runBlocking {
        val s = newSource(historical = 20.0, live = 30.0, sessionKm = 100.0)
        assertEquals(25.0, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `historical only when live is null even at high session km`() = runBlocking {
        // sessionKm computed elsewhere but live avg unavailable (e.g., dKm collapsed).
        val s = newSource(historical = 20.0, live = null, sessionKm = 50.0)
        assertEquals(20.0, s.recentAvgConsumption(), 0.01)
    }

    @Test fun `historical fallback flows through when no trips`() = runBlocking {
        // historicalProvider returns 18 (the default fallback inside repo).
        val s = newSource(historical = 18.0, live = null, sessionKm = 0.0)
        assertEquals(18.0, s.recentAvgConsumption(), 0.01)
    }
}
