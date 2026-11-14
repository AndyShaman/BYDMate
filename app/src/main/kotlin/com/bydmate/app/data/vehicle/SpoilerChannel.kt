package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw value of a spoiler fid (or of the pilot state), sentinels kept. Null means the read did not complete. */
fun interface SpoilerReader {
    suspend fun read(fid: Int): Int?
}

/** The verdict of a spoiler the anti-pinch stopped; ActionDispatcher words it apart from a refusal. */
internal const val SPOILER_ANTI_PINCH = "anti-pinch"

/** How the verdict of a command held by the standstill guard starts; ActionDispatcher words it apart too. */
internal const val SPOILER_STANDING = "blocked standing"

/**
 * Electric spoiler (#383) through the BYD voice assistant's commands: up/down (1 = up, 2 = down)
 * and automatic mode on (1). Not validated on a car, so nothing is written unless the config
 * says a spoiler is fitted, and the car's own feedback decides:
 *  - config read failed or a read error → UNREACHABLE, nothing written
 *  - config not 2                       → NOT_EQUIPPED, nothing written (65535 on a car without one)
 *  - repair mode, a spoiler warning, motor protection or the navigation pilot → BLOCKED, nothing
 *    written: the values the voice assistant refuses on (GetAllError). A check that does not
 *    read, or reads anything else, does not hold the command, as in BYD's code.
 *  - down or automatic mode with the spoiler raised and the car standing → BLOCKED, nothing
 *    written: the assistant does not lower it there by voice, it asks the driver to look around
 *    and tap (GetAutomaticTailCloseStatus). An unknown speed does not prove the car is moving.
 *    The speed is checked twice: before the write, and again in the writer's beforeSend guard,
 *    inside the helper's transport lock right before the transact, because the car can stop
 *    while the write waits for its audit row and for that lock.
 *  - up: status 2 or 5, down: 3 or 6    → OK (moving or already there)
 *  - status 4                           → ANTI_PINCH, at once and without a retry
 *  - status still as before the write   → NO_EFFECT
 *  - a failed read or any other value   → UNCONFIRMED
 * Automatic mode on is read back on the mode status (2 = automatic) the same way; a mode that is
 * automatic already is OK without a write. For up/down the mode is read before and after for the
 * log only: the command is expected to switch the spoiler to manual, and the first owner's log
 * has to show whether it does.
 * One write per attempt; a TRANSIENT write (daemon down, car off) ends it without reading.
 * Commands run one at a time under [mutex], so the next one reads the real status.
 */
class SpoilerChannel(
    private val writer: GuardedWriter,
    /** Speed now, km/h rounded up; null = unknown. */
    private val speed: suspend () -> Int?,
    private val reader: SpoilerReader,
) {
    enum class Result { OK, NOT_EQUIPPED, BLOCKED, NO_EFFECT, ANTI_PINCH, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] ends the channel's log line (OK, no spoiler, config unreadable, blocked <check>=<v>,
     * blocked standing ..., unreachable, anti-pinch, no effect, unconfirmed read=<v>); [written]
     * says whether the command was written; [state] is the last status read: the spoiler status
     * for up/down, the mode status for automatic mode (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?)

    private val mutex = Mutex()

    suspend fun actuate(up: Boolean): Outcome = mutex.withLock { move(if (up) Move.UP else Move.DOWN) }

    suspend fun autoOn(): Outcome = mutex.withLock { automatic() }

    private suspend fun move(move: Move): Outcome {
        val entry = enter(move.want)
        entry.stop?.let { return it }
        val before = read(WriteAllowlist.SPOILER_STATUS_FID)
        val guard = Standstill(if (move == Move.DOWN) raised(before) else null)
        guard.holds(speed)?.let { return blocked(entry.head, it) }
        val autoBefore = read(WriteAllowlist.SPOILER_AUTO_MODE_FID)
        val status = writer.write(move.action, move.value, guard::lastCheck)
        guard.held?.let { return blocked(entry.head, it) }
        val after = mutableListOf<Int?>()
        // auto: the car's automatic mode before and after (2 = automatic, 1 = manual).
        suspend fun log(verdict: String) = Log.i(
            TAG,
            "${entry.head} before=${render(before)} status=$status after=[${after.joinToString(",") { render(it) }}] " +
                "auto=${render(autoBefore)}->${render(read(WriteAllowlist.SPOILER_AUTO_MODE_FID))} -> $verdict",
        )
        if (status == WriteOutcome.TRANSIENT) {
            log("unreachable")
            return Outcome(Result.UNREACHABLE, "unreachable", false, null)
        }
        val outcome = readBack(WriteAllowlist.SPOILER_STATUS_FID, after) { value ->
            when (value) {
                in move.done -> Outcome(Result.OK, "OK", true, value)
                STATE_ANTI_PINCH -> Outcome(Result.ANTI_PINCH, SPOILER_ANTI_PINCH, true, value)
                else -> null
            }
        } ?: unsettled(before, after.last())
        log(outcome.verdict)
        return outcome
    }

    private suspend fun automatic(): Outcome {
        val entry = enter("auto")
        entry.stop?.let { return it }
        val before = read(WriteAllowlist.SPOILER_AUTO_MODE_FID)
        if (before == MODE_AUTOMATIC) {
            Log.i(TAG, "${entry.head} mode=$before -> OK, already automatic, nothing written")
            return Outcome(Result.OK, "OK", false, before)
        }
        // Automatic mode lowers a raised spoiler when the car stands, so the same guard holds it.
        val guard = Standstill(raised(read(WriteAllowlist.SPOILER_STATUS_FID)))
        guard.holds(speed)?.let { return blocked(entry.head, it) }
        val status = writer.write("spoiler_auto_on", CMD_AUTO_ON, guard::lastCheck)
        guard.held?.let { return blocked(entry.head, it) }
        val after = mutableListOf<Int?>()
        fun log(verdict: String) = Log.i(
            TAG,
            "${entry.head} mode=${render(before)} status=$status after=[${after.joinToString(",") { render(it) }}] -> $verdict",
        )
        if (status == WriteOutcome.TRANSIENT) {
            log("unreachable")
            return Outcome(Result.UNREACHABLE, "unreachable", false, null)
        }
        val outcome = readBack(WriteAllowlist.SPOILER_AUTO_MODE_FID, after) { value ->
            if (value == MODE_AUTOMATIC) Outcome(Result.OK, "OK", true, value) else null
        } ?: unsettled(before, after.last())
        log(outcome.verdict)
        return outcome
    }

    /** Where an attempt stands after the checks that come before any write. */
    private class Entry(val stop: Outcome?, val head: String)

    /** The config and the car's own refusals: [Entry.stop] ends the attempt, else [Entry.head] starts its log line. */
    private suspend fun enter(want: String): Entry {
        val config = read(WriteAllowlist.SPOILER_CONFIG_FID)
        if (config != CONFIG_FITTED) return Entry(refused(want, config), "")
        val checks = CHECKS.map { it to read(it.fid) }
        val seen = checks.joinToString(",") { (check, value) -> "${check.label}=${render(value)}" }
        val head = "Spoiler: want=$want config=$config checks=[$seen]"
        val hit = checks.firstOrNull { (check, value) -> value in check.refuseOn }
        return Entry(hit?.let { (check, value) -> blocked(head, "blocked ${check.label}=$value") }, head)
    }

    private fun blocked(head: String, verdict: String): Outcome {
        Log.i(TAG, "$head -> $verdict, nothing written")
        return Outcome(Result.BLOCKED, verdict, false, null)
    }

    /**
     * The position the standstill guard works on, or null when it has nothing to hold: the spoiler
     * is closing already, is not raised (position up to 10), or its position does not read, which
     * does not hold the command in BYD's code either.
     */
    private suspend fun raised(status: Int?): Int? {
        if (status == STATE_CLOSING) return null
        return read(WriteAllowlist.SPOILER_POSITION_FID)?.let(SentinelDecoder::decodeInt)?.takeIf { it > RAISED_ABOVE }
    }

    /** The standstill guard of one attempt; [position] is null when there is nothing to hold. */
    private class Standstill(private val position: Int?) {
        /** Why the last check held the command; null when it let it through. */
        var held: String? = null
            private set

        /** The verdict when the spoiler is raised and the car stands or its speed is unknown, else null. */
        suspend fun holds(speed: suspend () -> Int?): String? {
            if (position == null) return null
            // Set before the read: one the transport cuts off for taking too long is an unknown speed.
            held = "$SPOILER_STANDING position=$position speed=?"
            val kmh = runCatching { speed() }.onFailure { if (it is CancellationException) throw it }.getOrNull()
            held = if (kmh != null && kmh > STANDING_MAX_KMH) null else "$SPOILER_STANDING position=$position speed=${kmh ?: "?"}"
            return held
        }

        /** The writer's beforeSend guard: the speed once more, through the transport's own reader. */
        suspend fun lastCheck(locked: LockedReader): Boolean = holds { lockedGuardSpeedKmh(locked) } == null
    }

    /** Up to [READBACK_ATTEMPTS] reads of [fid] into [after]; the first one [settle] gives a verdict on ends it. */
    private suspend fun readBack(fid: Int, after: MutableList<Int?>, settle: (Int?) -> Outcome?): Outcome? {
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = read(fid)
            after += value
            settle(value)?.let { return it }
        }
        return null
    }

    /** The exit without a write: the config read failed, or no electric spoiler is fitted. */
    private fun refused(want: String, config: Int?): Outcome {
        // 65535 (no CAN link) is how a car without a spoiler answers; the other sentinels are read errors.
        val unreadable = config == null ||
            (config != SentinelDecoder.FEATURE_LINK_ERROR && SentinelDecoder.decodeInt(config) == null)
        val outcome = if (unreadable) Outcome(Result.UNREACHABLE, "config unreadable", false, null)
            else Outcome(Result.NOT_EQUIPPED, "no spoiler", false, null)
        Log.i(TAG, "Spoiler: want=$want config=${render(config)} -> ${outcome.verdict}, nothing written")
        return outcome
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(fid: Int): Int? =
        readGuarded({ Log.w(TAG, "Spoiler: read fid=$fid failed: ${it.message}") }) { reader.read(fid) }

    /**
     * The command of one direction and the statuses that confirm it: opening or opened, closing or
     * closed (status 2 → 5 → 3 → 6 in the owner's log).
     */
    private enum class Move(val want: String, val action: String, val value: Int, val done: Set<Int>) {
        UP("up", "spoiler_up", 1, setOf(2, 5)),
        DOWN("down", "spoiler_down", 2, setOf(3, 6)),
    }

    /** One of the car's own states the voice assistant refuses on, and the values that refuse. */
    private class Check(val label: String, val fid: Int, val refuseOn: Set<Int>)

    private companion object {
        const val TAG = "SpoilerChannel"
        val CHECKS = listOf(
            Check("repair", WriteAllowlist.SPOILER_REPAIR_FID, setOf(1)),
            Check("warning", WriteAllowlist.SPOILER_WARNING_FID, setOf(1, 2, 3, 4)),
            Check("thermal", WriteAllowlist.SPOILER_THERMAL_FID, setOf(1)),
            Check("pilot", WriteAllowlist.SPOILER_PILOT_FID, setOf(4, 6, 8)),
        )
        const val CONFIG_FITTED = 2
        const val CMD_AUTO_ON = 1
        const val MODE_AUTOMATIC = 2
        const val STATE_CLOSING = 3
        const val STATE_ANTI_PINCH = 4
        /** The standstill guard, as in BYD's code: position above 10 is raised, up to 3 km/h is standing. */
        const val RAISED_ABOVE = 10
        const val STANDING_MAX_KMH = 3
        const val READBACK_ATTEMPTS = 6
        /** The spoiler takes seconds to move; 6 x 500 ms gives the status time to change. */
        const val READBACK_DELAY_MS = 500L
    }
}

/** The readback gave no verdict. A failed read or the same error code on both sides proves nothing. */
private fun unsettled(before: Int?, state: Int?): SpoilerChannel.Outcome =
    if (state != null && SentinelDecoder.decodeInt(state) != null && state == before) {
        SpoilerChannel.Outcome(SpoilerChannel.Result.NO_EFFECT, "no effect", true, state)
    } else {
        SpoilerChannel.Outcome(SpoilerChannel.Result.UNCONFIRMED, "unconfirmed read=${render(state)}", true, state)
    }
