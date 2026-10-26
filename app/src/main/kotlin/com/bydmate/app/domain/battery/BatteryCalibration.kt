package com.bydmate.app.domain.battery

import com.bydmate.app.data.local.entity.ChargeEntity

/**
 * Last full charge and last calibration from the app's own charge log (#359), to the day.
 *
 * BYD manuals ask for a charge to 100 % at least weekly (the pack balances its cells at the
 * end) and a calibration, below 10 % up to 100 % in one session, every 3-6 months.
 */
object BatteryCalibration {

    data class Marks(val lastFullChargeTs: Long?, val lastCalibrationTs: Long?)

    enum class Level { GOOD, WARN, BAD }

    private const val DAY_MS = 86_400_000L
    private const val FULL_SOC = 100
    private const val CALIBRATION_MAX_START_SOC = 9

    fun marks(charges: List<ChargeEntity>): Marks {
        val full = charges.filter { it.status == "COMPLETED" && (it.socEnd ?: 0) >= FULL_SOC }
        val calibrations = full.filter { (it.socStart ?: Int.MAX_VALUE) <= CALIBRATION_MAX_START_SOC }
        return Marks(
            lastFullChargeTs = full.maxOfOrNull { it.endTs ?: it.startTs },
            lastCalibrationTs = calibrations.maxOfOrNull { it.endTs ?: it.startTs },
        )
    }

    /** Whole days since [ts]; a timestamp ahead of [now] counts as today. */
    fun daysSince(ts: Long, now: Long): Long = ((now - ts) / DAY_MS).coerceAtLeast(0)

    /** Weekly full charge: yellow from day 7, red from day 14. */
    fun sinceFullChargeLevel(lastFullChargeTs: Long?, now: Long): Level? {
        val days = daysSince(lastFullChargeTs ?: return null, now)
        return when {
            days < 7 -> Level.GOOD
            days < 14 -> Level.WARN
            else -> Level.BAD
        }
    }

    /** Calibration every 3-6 months: yellow from day 90, red after day 180. */
    fun calibrationLevel(lastCalibrationTs: Long?, now: Long): Level? {
        val days = daysSince(lastCalibrationTs ?: return null, now)
        return when {
            days < 90 -> Level.GOOD
            days <= 180 -> Level.WARN
            else -> Level.BAD
        }
    }
}
