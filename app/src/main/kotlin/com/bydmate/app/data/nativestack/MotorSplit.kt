package com.bydmate.app.data.nativestack

import kotlin.math.abs
import kotlin.math.roundToInt

/** How the drive current is shared between the two motors, when the car is pulling at all. */
internal sealed interface MotorSplit {
    /** Neither motor is drawing current — the row shows dashes rather than a made-up 50/50. */
    data object Idle : MotorSplit
    data class Share(val frontPercent: Int, val rearPercent: Int) : MotorSplit
}

/**
 * Front/rear share of the motor power. Both motors hang off the same traction bus, so equal
 * voltage makes the currents alone the split; magnitudes are used because the sign under
 * regeneration is unverified. Null means the car does not report the pair (single-motor
 * firmware or no data yet) and the row is not drawn at all.
 */
internal fun motorSplitPercent(front: Float?, rear: Float?): MotorSplit? {
    if (front == null || rear == null) return null
    val f = abs(front)
    val r = abs(rear)
    if (f + r < IDLE_CURRENT_THRESHOLD_A) return MotorSplit.Idle
    val frontPercent = (f / (f + r) * 100f).roundToInt()
    return MotorSplit.Share(frontPercent, 100 - frontPercent)
}

/** Below this the car is standing still and the ratio is noise, not a split. */
private const val IDLE_CURRENT_THRESHOLD_A = 0.5f
