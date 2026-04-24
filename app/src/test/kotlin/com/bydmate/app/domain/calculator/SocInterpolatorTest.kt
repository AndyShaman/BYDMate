package com.bydmate.app.domain.calculator

import org.junit.Assert.assertEquals
import org.junit.Test

class SocInterpolatorTest {

    private fun newInterpolator(): SocInterpolator {
        return SocInterpolator(persistence = InMemorySocInterpolatorPrefs())
    }

    @Test fun `cold start — first sample initialises anchor, carry 0`() {
        val interp = newInterpolator()
        interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        assertEquals(0.0, interp.carryOver(totalElecKwh = 1000.0, soc = 80), 0.001)
    }

    @Test fun `carry monotonic between SOC steps`() {
        val interp = newInterpolator()
        interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        interp.onSample(soc = 80, totalElecKwh = 1000.2, sessionId = 1L)
        assertEquals(0.2, interp.carryOver(totalElecKwh = 1000.2, soc = 80), 0.001)
        interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
        assertEquals(0.5, interp.carryOver(totalElecKwh = 1000.5, soc = 80), 0.001)
    }

    @Test fun `SOC click down resets carry to zero`() {
        val interp = newInterpolator()
        interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
        interp.onSample(soc = 79, totalElecKwh = 1000.7, sessionId = 1L)
        assertEquals(0.0, interp.carryOver(totalElecKwh = 1000.7, soc = 79), 0.001)
        interp.onSample(soc = 79, totalElecKwh = 1000.9, sessionId = 1L)
        assertEquals(0.2, interp.carryOver(totalElecKwh = 1000.9, soc = 79), 0.001)
    }

    @Test fun `new session re-anchors carry`() {
        val interp = newInterpolator()
        interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
        interp.onSample(soc = 78, totalElecKwh = 1001.2, sessionId = 2L)
        assertEquals(0.0, interp.carryOver(totalElecKwh = 1001.2, soc = 78), 0.001)
    }

    @Test fun `negative carry clamped to zero`() {
        val interp = newInterpolator()
        interp.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        interp.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)
        assertEquals(0.0, interp.carryOver(totalElecKwh = 999.8, soc = 80), 0.001)
    }

    @Test fun `state restored from persistence across instances`() {
        val prefs = InMemorySocInterpolatorPrefs()
        val a = SocInterpolator(persistence = prefs)
        a.onSample(soc = 80, totalElecKwh = 1000.0, sessionId = 1L)
        a.onSample(soc = 80, totalElecKwh = 1000.5, sessionId = 1L)

        // New SocInterpolator instance (simulates sys-kill + restart) but same prefs
        val b = SocInterpolator(persistence = prefs)
        // Before any onSample on b, carryOver uses state loaded from prefs
        assertEquals(0.5, b.carryOver(totalElecKwh = 1000.5, soc = 80), 0.001)
    }
}

class InMemorySocInterpolatorPrefs : SocInterpolatorPrefs {
    private var lastSoc: Int? = null
    private var totalElecAtChange: Double? = null
    override fun load(): SocInterpolatorState? {
        val s = lastSoc; val t = totalElecAtChange
        return if (s != null && t != null) SocInterpolatorState(s, t) else null
    }
    override fun save(state: SocInterpolatorState) {
        lastSoc = state.lastSoc; totalElecAtChange = state.totalElecAtChange
    }
    override fun clear() { lastSoc = null; totalElecAtChange = null }
}
