package com.bydmate.app.data.local

/**
 * Plausibility bound for BYD's own `electricity` column in energydata.
 *
 * The head unit occasionally writes a value that cannot come from this battery (VadimV,
 * 2026-09-12: 54681,2 kWh on a 38,2 km trip whose SOC only moved 92 → 84), and that single
 * record then poisons every week/month/year statistic built on top of it. Such a value is
 * replaced with the SOC-delta estimate, or dropped when there is no usable SOC pair.
 *
 * The pack capacity is deliberately NOT a bound: on a DM-i hybrid the engine recharges the
 * battery mid-trip, so 30 kWh over 200 km out of a 20 kWh pack is an ordinary 15 per 100. Only
 * an absolute ceiling no drive can reach ([MAX_KWH_PER_TRIP]) and the per-100 figure are used.
 *
 * The per-100 ceiling only applies from [MIN_KM_FOR_PER100_RULE] up: a few hundred metres with
 * the heater on is honestly worth hundreds of kWh per 100 km and must be left alone.
 *
 * Plausible values are returned untouched — the healthy fleet sees no change.
 */
object TripEnergySanity {

    /** Ceiling for a single trip; a real one stays around 10-25 kWh/100 km. */
    const val MAX_KWH_PER_100KM = 150.0

    /**
     * Below this distance the per-100 figure says nothing: VadimV's own list has honest
     * 0,1 km / 0,1 kWh records (99,6 per 100), and a couple of hundred metres with the heater
     * on beats any ceiling. Short trips are left to the negative and absolute rules.
     */
    const val MIN_KM_FOR_PER100_RULE = 5.0

    /**
     * No BYD pack, and no hybrid drive topping it up on the way, gets anywhere near this in one
     * trip; VadimV's 54681,2 kWh does. Deliberately far above any real value, because the only
     * job here is to catch garbage, not to second-guess the BMS.
     */
    const val MAX_KWH_PER_TRIP = 500.0

    fun isPlausible(electricityKwh: Double, tripKm: Double?): Boolean {
        if (electricityKwh < 0.0) return false
        if (electricityKwh > MAX_KWH_PER_TRIP) return false
        val km = tripKm ?: 0.0
        if (km >= MIN_KM_FOR_PER100_RULE && electricityKwh / km * 100.0 > MAX_KWH_PER_100KM) return false
        return true
    }

    /**
     * The kWh value to store: the raw one when it is plausible, the SOC-delta estimate when it
     * is not, and null when even that is unavailable (unknown consumption — never the raw value).
     * [capacityKwh] is used for that fallback only; 0 means "unset" and leaves nothing to fall
     * back to.
     */
    fun kwhFor(
        electricityKwh: Double,
        tripKm: Double?,
        socStart: Int?,
        socEnd: Int?,
        capacityKwh: Double,
    ): Double? {
        if (isPlausible(electricityKwh, tripKm)) return electricityKwh
        if (capacityKwh > 0.0 && socStart != null && socEnd != null && socStart > socEnd) {
            return (socStart - socEnd) / 100.0 * capacityKwh
        }
        return null
    }

    /** Consumption per 100 km for a (possibly dropped) kWh value. */
    fun per100For(kwh: Double?, tripKm: Double?): Double? {
        if (kwh == null) return null
        val km = tripKm ?: return null
        return if (km > 0.0) kwh / km * 100.0 else null
    }
}
