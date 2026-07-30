package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A write request after generation routing. */
data class RoutedWrite(val actionName: String, val value: Int)

/**
 * Routes the percent window actions to the CTRL channel on head units whose firmware
 * has no percent window family at all (DiLink 3.0, ~6400 constants — issue #79).
 *
 * Generation is probed lazily on the first window write, from READ fids only. A CTRL
 * candidate needs the full signature: ALL FOUR percent window fids answer
 * DEVICE_THE_FEATURE_LINK_ERROR (65535) AND the alternative-generation fid answers a
 * valid percent. The fids are read one at a time and the probe stops at the first
 * conclusive answer, so a healthy unit costs one read. Every outcome other than a valid
 * percent or the full signature (daemon down, car asleep, any other sentinel, a failed
 * read) leaves the channel UNKNOWN and the write goes out on the existing percent path.
 *
 * Two rails keep transient link errors on a percent-capable unit from cementing CTRL: the
 * full four-fid signature, and a persisted candidate timestamp — CTRL is only written to
 * the store by a second candidate probe at least [CTRL_CONFIRM_MIN_GAP_MS] after the
 * first, which no single cold start can span. Until then the percent path stays in place,
 * and any healthy probe clears the pending candidate.
 *
 * The probe is single-flight (a [Mutex] guards re-check → probe → persist) and its
 * verdict, UNKNOWN included, is memoized in memory for [MEMO_TTL_MS] — except while a
 * candidate is pending, where re-probing is what lets a healthy read clear it. A composite
 * command that fans out to four doors therefore probes once and routes all four the
 * same way.
 */
class WindowChannelRouter(
    private val helper: HelperClient,
    private val store: WindowChannelStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private data class Memo(val channel: WindowChannel, val atMs: Long)

    /** A probe verdict plus whether it may be memoized (a pending candidate may not). */
    private data class Probe(val channel: WindowChannel, val memoize: Boolean = true)

    private val probeMutex = Mutex()
    @Volatile private var memo: Memo? = null

    /**
     * Translates a percent window write for this generation. Returns [actionName]/[value]
     * unchanged for every non-window action and for percent-capable / undecided units.
     */
    suspend fun route(actionName: String, value: Int): RoutedWrite {
        val ctrlAction = CTRL_ACTIONS[actionName.lowercase()]
            ?: return RoutedWrite(actionName, value)
        // Out-of-range percent keeps the percent action so the allowlist range gate
        // rejects it, instead of being folded into a valid CTRL code.
        if (value !in 0..100) return RoutedWrite(actionName, value)
        if (channel() != WindowChannel.CTRL) return RoutedWrite(actionName, value)
        return RoutedWrite(ctrlAction, ctrlValue(value))
    }

    private suspend fun channel(): WindowChannel {
        decided()?.let { return it }
        return probeMutex.withLock {
            // Re-check inside the lock: a concurrent write may have probed already.
            decided() ?: probe().let { verdict ->
                if (verdict.memoize) memo = Memo(verdict.channel, clock())
                verdict.channel
            }
        }
    }

    /** Persisted winner, else a fresh enough probe verdict (UNKNOWN included). */
    private fun decided(): WindowChannel? {
        store.winner().takeIf { it != WindowChannel.UNKNOWN }?.let { return it }
        return memo?.takeIf { clock() - it.atMs < MEMO_TTL_MS }?.channel
    }

    private suspend fun probe(): Probe {
        // Read one fid at a time and leave as soon as the answer is conclusive: a healthy
        // unit answers on the first fid and pays for a single binder read.
        for (fid in PERCENT_FIDS) {
            val percent = read(fid)
            if (percent != null && percent in PERCENT_RANGE) {
                // Latching PERCENT also drops any pending candidate (see setWinner): one
                // healthy observation ends the candidate history.
                store.setWinner(WindowChannel.PERCENT)
                return Probe(WindowChannel.PERCENT)
            }
            // Anything short of "this fid reports a link error" is not evidence that the
            // family is absent: a null is a failed read, another sentinel is another fault.
            if (percent != FEATURE_LINK_ERROR) return Probe(undecided())
        }
        val alt = read(RR_PERCENT_FID_GEN3)
        if (alt == null || alt !in PERCENT_RANGE) return Probe(undecided())
        return confirmCandidate()
    }

    /**
     * The full "no percent family" signature was seen. Fixing CTRL needs a second such probe
     * at least [CTRL_CONFIRM_MIN_GAP_MS] later: a cold-start link error storm on a
     * percent-capable unit is over long before that, so it can only ever produce a first
     * candidate. An older-than-[CTRL_CONFIRM_MAX_GAP_MS] candidate is stale evidence about a
     * car that has been driven since, and starts the pair over.
     */
    private fun confirmCandidate(): Probe {
        val first = store.ctrlCandidateAtMs()
        val gap = clock() - first
        if (first > 0L && gap >= CTRL_CONFIRM_MIN_GAP_MS && gap <= CTRL_CONFIRM_MAX_GAP_MS) {
            Log.i(TAG, "window channel = CTRL (percent family absent on this firmware)")
            store.setWinner(WindowChannel.CTRL)
            return Probe(WindowChannel.CTRL)
        }
        store.setCtrlCandidateAtMs(clock())
        Log.i(TAG, "window channel: CTRL candidate recorded (previous gap ${gap}ms), staying on percent")
        // NOT memoized: while a candidate is pending, every command must re-probe, because it
        // is a healthy probe that clears the stamp. Memoizing this verdict would hide the next
        // healthy read for MEMO_TTL_MS and let a much later second anomaly pair up with a stamp
        // a percent-capable unit had already disproved (codex, wave C).
        return Probe(WindowChannel.UNKNOWN, memoize = false)
    }

    /** Any non-candidate outcome discards the pending candidate, so the pair must be clean. */
    private fun undecided(): WindowChannel {
        if (store.ctrlCandidateAtMs() != 0L) store.setCtrlCandidateAtMs(0L)
        return WindowChannel.UNKNOWN
    }

    /** A probe failure must never fail the write it precedes — treat it as undecided. */
    private suspend fun read(fid: Int): Long? = try {
        helper.read(WINDOW_DEV, fid)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Log.w(TAG, "window channel probe read fid=$fid failed: ${e.message}")
        null
    }

    companion object {
        private const val TAG = "WindowChannelRouter"

        private const val WINDOW_DEV = 1001
        // READ fids, not write fids: the four DiLink 5.0 window percents and the
        // DiLink 3.0 twin of the rear-right one.
        private val PERCENT_FIDS = listOf(947912728, 1267728400, 947912736, 947912752)
        private const val RR_PERCENT_FID_GEN3 = 1267728408
        private val FEATURE_LINK_ERROR = SentinelDecoder.FEATURE_LINK_ERROR.toLong()
        private val PERCENT_RANGE = 0L..100L

        /** Minimum distance between the two candidate probes that fix CTRL. A cold start,
         *  where a percent-capable unit can answer link errors for a while, is over well
         *  inside this window, so it cannot supply both halves of the pair. */
        internal const val CTRL_CONFIRM_MIN_GAP_MS = 10 * 60 * 1000L

        /** A pending candidate older than this is stale evidence — the pair starts over. */
        internal const val CTRL_CONFIRM_MAX_GAP_MS = 7 * 24 * 60 * 60 * 1000L

        /** Lifetime of an in-memory probe verdict: long enough to cover one composite
         *  command's fan-out (4 doors, 150 ms apart), short enough that a woken car is
         *  re-probed on the next command. */
        internal const val MEMO_TTL_MS = 30_000L

        private val CTRL_ACTIONS: Map<String, String> = mapOf(
            "window_driver_pos" to "window_driver_ctrl",
            "window_passenger_pos" to "window_passenger_ctrl",
            "window_rear_left_pos" to "window_rear_left_ctrl",
            "window_rear_right_pos" to "window_rear_right_ctrl",
        )

        private const val CTRL_OPEN = 1
        private const val CTRL_CLOSE = 2
        private const val CTRL_HALF = 4
        private const val CTRL_VENT = 5

        /** Percent target → nearest CTRL detent (the channel has no continuous position). */
        internal fun ctrlValue(percent: Int): Int = when {
            percent == 0 -> CTRL_CLOSE
            percent <= 25 -> CTRL_VENT
            percent <= 75 -> CTRL_HALF
            else -> CTRL_OPEN
        }
    }
}
