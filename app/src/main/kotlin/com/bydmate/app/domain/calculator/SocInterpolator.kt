package com.bydmate.app.domain.calculator

import javax.inject.Singleton

data class SocInterpolatorState(
    val lastSoc: Int,
    val totalElecAtChange: Double,
)

interface SocInterpolatorPrefs {
    fun load(): SocInterpolatorState?
    fun save(state: SocInterpolatorState)
    fun clear()
}

/**
 * Smooths 4-km steps between integer SOC ticks.
 *
 * On every DiPars sample, remember `totalElec` at the moment SOC last changed.
 * `carryOver = totalElec - totalElec_at_last_soc_change` = energy used since
 * that step. Subtract it from `SOC × cap / 100` to get a fractional
 * remaining_kwh that changes monotonically tick-to-tick instead of jumping
 * at each integer SOC click.
 */
@Singleton
class SocInterpolator(
    private val persistence: SocInterpolatorPrefs,
) {
    @Volatile private var state: SocInterpolatorState? = persistence.load()
    @Volatile private var sessionId: Long? = null

    @Synchronized
    fun onSample(soc: Int?, totalElecKwh: Double?, sessionId: Long?) {
        if (soc == null || totalElecKwh == null) return
        if (sessionId != this.sessionId) {
            // New ignition cycle — re-anchor (charging may have changed everything).
            this.sessionId = sessionId
            commit(soc, totalElecKwh)
            return
        }
        val cur = state
        if (cur == null || cur.lastSoc != soc) {
            commit(soc, totalElecKwh)
        }
    }

    /**
     * kWh consumed since the last SOC change. Always >= 0 within the same
     * SOC step. Returns 0 when no anchor yet.
     */
    @Synchronized
    fun carryOver(totalElecKwh: Double?, soc: Int?): Double {
        val st = state ?: return 0.0
        if (totalElecKwh == null || soc == null) return 0.0
        if (soc != st.lastSoc) return 0.0  // SOC moved but onSample hasn't run yet
        val raw = totalElecKwh - st.totalElecAtChange
        if (raw < 0.0) return 0.0  // glitch — totalElec rolled back
        return raw
    }

    private fun commit(soc: Int, totalElec: Double) {
        val newState = SocInterpolatorState(lastSoc = soc, totalElecAtChange = totalElec)
        state = newState
        persistence.save(newState)
    }
}
