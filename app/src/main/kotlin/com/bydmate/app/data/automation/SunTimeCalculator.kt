package com.bydmate.app.data.automation

import android.location.Location
import org.shredzone.commons.suncalc.SunTimes
import java.time.ZonedDateTime

enum class TimeOfDay { DAY, NIGHT, DAWN, DUSK }

object SunTimeCalculator {

    // Within ±45 minutes of sunrise → dawn; within ±45 minutes of sunset → dusk.
    private const val TRANSITION_MINUTES = 45L

    fun currentPhase(location: Location, now: ZonedDateTime = ZonedDateTime.now()): TimeOfDay {
        val sunTimes = SunTimes.compute()
            .at(location.latitude, location.longitude)
            .on(now)
            .execute()

        val nowMs = now.toInstant().toEpochMilli()
        val rise = sunTimes.rise?.toInstant()?.toEpochMilli()
        val set = sunTimes.set?.toInstant()?.toEpochMilli()
        val transitionMs = TRANSITION_MINUTES * 60_000L

        if (rise != null && kotlin.math.abs(nowMs - rise) < transitionMs) return TimeOfDay.DAWN
        if (set != null && kotlin.math.abs(nowMs - set) < transitionMs) return TimeOfDay.DUSK

        if (sunTimes.isAlwaysUp) return TimeOfDay.DAY
        if (sunTimes.isAlwaysDown) return TimeOfDay.NIGHT

        val sunAbove = rise != null && set != null && (
            (rise < set && nowMs in rise..set) ||
            (rise > set && (nowMs >= rise || nowMs <= set))
        )
        return if (sunAbove) TimeOfDay.DAY else TimeOfDay.NIGHT
    }
}
