package com.bydmate.app.data.nativestack

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.remote.DiParsData
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/** Persists the set of absent axle names ("front", "rear") across process restarts (#403). */
interface MotorPresenceStore {
    fun load(): Set<String>
    fun save(absent: Set<String>)
}

/** SharedPreferences-backed [MotorPresenceStore] using one string-set key. */
class SharedPrefsMotorPresenceStore(
    private val prefs: SharedPreferences,
) : MotorPresenceStore {
    override fun load(): Set<String> =
        prefs.getStringSet(KEY_ABSENT_AXLES, null)?.toSet() ?: emptySet()

    override fun save(absent: Set<String>) {
        prefs.edit().putStringSet(KEY_ABSENT_AXLES, absent.toSet()).apply()
    }

    companion object {
        const val PREFS_NAME = "motor_presence"
        private const val KEY_ABSENT_AXLES = "absent_axles"
    }
}

/**
 * #426 / #403: a car can report temperatures for a motor it does not have (a Qin L DM-i shows
 * -36 and 65 degrees for a rear motor that is not there). The number alone cannot tell that from
 * a real cold motor, so the verdict comes from the axle's behaviour: it answers a readable rpm 0
 * and current 0 while the other axle spins at [OTHER_AXLE_MIN_RPM] or more, in an unbroken run of
 * poll snapshots (each at most [MAX_GAP_MS] after the last) of at least [MIN_TICKS] ticks over
 * [MIN_DEAD_MS]. A sentinel or out-of-range axle decodes to null and never qualifies. Any sign of
 * life (rpm outside -1..1, or [ALIVE_CURRENT_A] or more) clears the verdict and persists the
 * cleared state. Once reached, the verdict is persisted in [store] and restored on start so a
 * parked car does not show bogus temperatures again.
 */
@Singleton
class MotorPresence @Inject constructor(
    private val store: MotorPresenceStore,
) {

    private class Axle(val name: String) {
        var seenAlive = false
        var ticks = 0
        var since = 0L
        var lastAt = 0L
        @Volatile var absent = false
    }

    private val front = Axle("front")
    private val rear = Axle("rear")

    init {
        val restored = store.load()
        front.absent = front.name in restored
        rear.absent = rear.name in restored
    }

    /** The front axle has no motor: its temperatures are not real. */
    val frontAbsent: Boolean get() = front.absent

    /** The rear axle has no motor: its temperatures are not real. */
    val rearAbsent: Boolean get() = rear.absent

    /** Feeds one poll snapshot taken at [now] (elapsed ms). */
    @Synchronized
    fun onSnapshot(data: DiParsData, now: Long) {
        val before = currentAbsent()
        judge(front, data.motorRpmFront, data.motorCurrentFront, data.motorRpmRear, now)
        judge(rear, data.motorRpmRear, data.motorCurrentRear, data.motorRpmFront, now)
        val after = currentAbsent()
        if (after != before) {
            store.save(after)
        }
    }

    private fun currentAbsent(): Set<String> = buildSet {
        if (front.absent) add(front.name)
        if (rear.absent) add(rear.name)
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
