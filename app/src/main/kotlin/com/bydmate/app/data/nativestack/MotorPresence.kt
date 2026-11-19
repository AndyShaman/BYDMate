package com.bydmate.app.data.nativestack

import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * #426: a car can report temperatures for a motor it does not have (a Qin L DM-i shows -36° and
 * 65° for a rear motor that is not there). The number alone cannot tell that from a real cold
 * motor, so the verdict comes from the axle's behaviour: it answers a readable rpm 0 and current 0
 * while the other axle spins at [OTHER_AXLE_MIN_RPM] or more, in an unbroken run of poll
 * snapshots (each at most [MAX_GAP_MS] after the last) of at least [MIN_TICKS] ticks over
 * [MIN_DEAD_MS]. A sentinel or out-of-range axle decodes to null and never qualifies. Any sign of
 * life (rpm outside -1..1, or [ALIVE_CURRENT_A] or more) clears the verdict for the process and
 * keeps it cleared. Once reached, the verdict holds until such a sign: a stop at a light does not
 * bring the temperatures back. Process-only: nothing is persisted.
 */
@Singleton
class MotorPresence @Inject constructor() {

    private class Axle(val name: String) {
        var seenAlive = false
        var ticks = 0
        var since = 0L
        var lastAt = 0L
        @Volatile var absent = false
    }

    private val front = Axle("front")
    private val rear = Axle("rear")

    /** The front axle has no motor: its temperatures are not real. */
    val frontAbsent: Boolean get() = front.absent

    /** The rear axle has no motor: its temperatures are not real. */
    val rearAbsent: Boolean get() = rear.absent

    /** Feeds one poll snapshot taken at [now] (elapsed ms). */
    @Synchronized
    fun onSnapshot(data: DiParsData, now: Long) {
        judge(front, data.motorRpmFront, data.motorCurrentFront, data.motorRpmRear, now)
        judge(rear, data.motorRpmRear, data.motorCurrentRear, data.motorRpmFront, now)
    }

    private fun judge(axle: Axle, rpm: Int?, current: Float?, otherRpm: Int?, now: Long) {
        if (axle.seenAlive) return
        if (alive(rpm, current)) {
            axle.seenAlive = true
            if (axle.absent) {
                axle.absent = false
                Log.i(TAG, "motor presence: ${axle.name} axle alive (rpm=$rpm current=$current), verdict cleared")
            }
            return
        }
        if (axle.absent) return
        // Not alive here, so a readable current is under ALIVE_CURRENT_A.
        val idle = rpm != null && current != null && rpm in -1..0
        val otherSpins = otherRpm != null && abs(otherRpm) >= OTHER_AXLE_MIN_RPM
        if (!idle || !otherSpins) {
            axle.ticks = 0
            return
        }
        countDeadTick(axle, now)
        if (axle.absent) {
            Log.i(
                TAG,
                "motor presence: ${axle.name} axle absent (rpm=$rpm current=$current for ${now - axle.since}ms, " +
                    "${axle.ticks} ticks, other axle rpm=$otherRpm), its temperatures show a dash"
            )
        }
    }

    private fun alive(rpm: Int?, current: Float?): Boolean =
        (rpm != null && rpm !in -1..1) || (current != null && abs(current) >= ALIVE_CURRENT_A)

    /** One more qualifying tick; a gap over [MAX_GAP_MS] starts the run again. */
    private fun countDeadTick(axle: Axle, now: Long) {
        if (axle.ticks == 0 || now - axle.lastAt > MAX_GAP_MS) {
            axle.ticks = 0
            axle.since = now
        }
        axle.ticks++
        axle.lastAt = now
        axle.absent = axle.ticks >= MIN_TICKS && now - axle.since >= MIN_DEAD_MS
    }

    private companion object {
        const val TAG = "MotorPresence"
        const val OTHER_AXLE_MIN_RPM = 300
        const val ALIVE_CURRENT_A = 0.5f
        const val MAX_GAP_MS = 5_000L
        const val MIN_TICKS = 3
        const val MIN_DEAD_MS = 20_000L
    }
}
