package com.bydmate.app.domain.calculator

import android.content.Context
import javax.inject.Singleton

@Singleton
class SocInterpolatorPrefsImpl(context: Context) : SocInterpolatorPrefs {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(): SocInterpolatorState? {
        if (!prefs.contains(KEY_LAST_SOC) || !prefs.contains(KEY_TOTAL_ELEC_BITS)) return null
        return SocInterpolatorState(
            lastSoc = prefs.getInt(KEY_LAST_SOC, 0),
            totalElecAtChange = Double.fromBits(prefs.getLong(KEY_TOTAL_ELEC_BITS, 0L)),
        )
    }

    override fun save(state: SocInterpolatorState) {
        prefs.edit()
            .putInt(KEY_LAST_SOC, state.lastSoc)
            .putLong(KEY_TOTAL_ELEC_BITS, state.totalElecAtChange.toRawBits())
            .apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_LAST_SOC).remove(KEY_TOTAL_ELEC_BITS).apply()
    }

    private companion object {
        const val PREFS_NAME = "bydmate_range_prefs"
        const val KEY_LAST_SOC = "soc_interp_last_soc"
        const val KEY_TOTAL_ELEC_BITS = "soc_interp_total_elec_bits"
    }
}
