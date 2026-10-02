package com.bydmate.app.media

import com.bydmate.app.media.ClusterMusicCard.Card
import com.bydmate.app.media.ClusterMusicCard.Target

/**
 * The card's state machine, free of Android so every transition is JVM-tested. [ClusterMusicBridge]
 * feeds it one [step] per poll, serialized.
 *
 * Wanted and confirmed state are kept apart: [shown] only moves when every required write of a
 * card came back with a non-negative status, and [dirty] says the cluster may hold our text
 * (anything was attempted since the last confirmed clear). A failed clear therefore stays owed
 * and is retried on the next step, up to [MAX_CLEAR_ATTEMPTS].
 *
 * A car that refuses a required fid (negative status) [MAX_WRITE_REFUSALS] times in a row gets no
 * new card until the process restarts ([refused]), so it can't flood the diagnostics log; a clear
 * still owed for our card and a hand-off are handled as before. An unreachable helper (null) is
 * not a refusal: the daemon may still be starting, so the card is retried, but no sooner than
 * [REASSERT_MS] after the failed attempt, and only the first failure of a streak is reported.
 */
class ClusterMusicSync(private val port: Port) {

    /** The helper's raw writes: status 1 real, 0 no-op, <0 error, null daemon unreachable. */
    interface Port {
        suspend fun writeInt(dev: Int, fid: Int, value: Int): Int?
        suspend fun writeBuffer(dev: Int, fid: Int, bytes: ByteArray): Int?
    }

    /** What a [step] did, for the bridge's log and trace. [RETRYING] and [NONE] are not reported. */
    enum class Outcome {
        NONE, SHOWN, REASSERTED, TICKED, WRITE_FAILED, RETRYING, REFUSED, ABORTED,
        CLEARED, CLEAR_FAILED, CLEAR_GAVE_UP, HANDED_OFF,
    }

    /** Last card the cluster confirmed; null when nothing of ours is known to be there. */
    var shown: Card? = null
        private set

    /** True while the cluster may hold our text and a clear is owed. */
    var dirty = false
        private set

    /** True once the car refused the card [MAX_WRITE_REFUSALS] times: no new show until the process restarts. */
    var refused = false
        private set

    /** Set while the helper is unreachable: no new card attempt before this time. */
    private var retryAt: Long? = null

    /** Set while a clear found the helper unreachable: no new clear attempt from a poll before this time. */
    private var clearRetryAt: Long? = null
    private var lastWriteAt = 0L
    private var progressSent: Int? = null
    private var clearAttempts = 0
    private var refusals = 0
    private var failing = false

    /**
     * One poll. [wanted] is the switch; [fids] null means this firmware can't take the card, so
     * nothing is written. [stillWanted] is re-checked between writes, so turning the switch off
     * mid-step stops the rest of the card and leaves a clear owed instead.
     */
    suspend fun step(
        wanted: Boolean,
        fids: ClusterMusicFids?,
        target: Target,
        nowMs: Long,
        stillWanted: () -> Boolean = { true },
    ): Outcome {
        if (fids == null) return Outcome.NONE
        // Another app plays: the stock controller rewrites the card on the focus change. Ours is
        // gone, with the switch on or off.
        if (target is Target.OtherPlaying) return handOff()
        if (!wanted || target !is Target.Show) return if (dirty) clear(fids, nowMs) else Outcome.NONE
        return if (refused) Outcome.NONE else show(fids, target.card, nowMs, stillWanted)
    }

    /**
     * Last clear before the bridge stops: one attempt, whatever the retry budget says, so a stop
     * never leaves our text behind when the helper can still take it. [target] is the owner read
     * right before the stop: a card another player took is forgotten, not cleared.
     */
    suspend fun release(fids: ClusterMusicFids?, target: Target = Target.Idle): Outcome {
        if (fids == null) return Outcome.NONE
        if (target is Target.OtherPlaying) return handOff()
        if (!dirty) return Outcome.NONE
        clearAttempts = 0
        return clear(fids, nowMs = null)
    }

    private fun handOff(): Outcome =
        if (dirty || shown != null) Outcome.HANDED_OFF.also { forget() } else Outcome.NONE

    private suspend fun show(fids: ClusterMusicFids, card: Card, nowMs: Long, stillWanted: () -> Boolean): Outcome {
        // Every helper call is logged by HelperClient: a dead daemon is retried at the re-assert pace.
        retryAt?.let { if (nowMs < it) return Outcome.NONE }
        val newTrack = shown?.steady() != card.steady()
        if (newTrack || nowMs - lastWriteAt >= REASSERT_MS) {
            val outcome = writeCard(fids, card, newTrack, nowMs, stillWanted)
            if (outcome != Outcome.SHOWN) return outcome
            lastWriteAt = nowMs
            if (!newTrack) return Outcome.REASSERTED
        }
        val ticked = tick(fids, card)
        return when {
            newTrack -> Outcome.SHOWN
            ticked -> Outcome.TICKED
            else -> Outcome.NONE
        }
    }

    private suspend fun writeCard(
        fids: ClusterMusicFids,
        card: Card,
        newTrack: Boolean,
        nowMs: Long,
        stillWanted: () -> Boolean,
    ): Outcome {
        dirty = true
        val writes = buildList<suspend () -> Int?> {
            add { port.writeInt(fids.instrumentDev, fids.source, ClusterMusicCard.SOURCE_THIRD_PARTY) }
            add { port.writeInt(fids.instrumentDev, fids.state, card.musicState) }
            add { port.writeBuffer(fids.instrumentDev, fids.info, ClusterMusicCard.encode(card.title)) }
        }
        for (write in writes) {
            if (!stillWanted()) { shown = null; return Outcome.ABORTED }
            val status = write()
            if (!ok(status)) return failed(status, nowMs)
        }
        refusals = 0
        failing = false
        retryAt = null
        // Optional fids: best effort, a failure doesn't hold the card back.
        if (stillWanted()) writeSinger(fids, card.artist)
        if (newTrack && stillWanted()) {
            // A new track starts from a clean bar: unknown progress is written as zero, so the
            // previous track's bar never lingers.
            progressSent = null
            fids.progress?.let { if (ok(port.writeInt(fids.instrumentDev, it, card.progress ?: 0))) progressSent = card.progress ?: 0 }
        }
        if (!stillWanted()) { shown = null; return Outcome.ABORTED }
        shown = card
        clearAttempts = 0
        clearRetryAt = null
        return Outcome.SHOWN
    }

    /** A required write failed: count refusals toward [refused], report only a streak's first failure. */
    private fun failed(status: Int?, nowMs: Long): Outcome {
        shown = null
        retryAt = if (status == null) nowMs + REASSERT_MS else null
        if (status != null) {
            refusals++
            if (refusals >= MAX_WRITE_REFUSALS) {
                refused = true
                return Outcome.REFUSED
            }
        }
        if (failing) return Outcome.RETRYING
        failing = true
        return Outcome.WRITE_FAILED
    }

    /** The bar as it moves; a failed write is simply retried next tick. */
    private suspend fun tick(fids: ClusterMusicFids, card: Card): Boolean {
        val progress = card.progress ?: return false
        val fid = fids.progress ?: return false
        if (progress == progressSent) return false
        if (ok(port.writeInt(fids.instrumentDev, fid, progress))) progressSent = progress
        return true
    }

    /**
     * What the stock sender sends when a source goes away: stopped, blank name and singer, empty bar.
     * A poll's clear ([nowMs] set) that found the helper unreachable waits [REASSERT_MS] before the
     * next attempt, so the budget outlasts a daemon restart; [release] passes null and always tries.
     */
    private suspend fun clear(fids: ClusterMusicFids, nowMs: Long?): Outcome {
        if (clearAttempts >= MAX_CLEAR_ATTEMPTS) return Outcome.NONE
        val retryAt = clearRetryAt
        if (nowMs != null && retryAt != null && nowMs < retryAt) return Outcome.NONE
        clearAttempts++
        val state = port.writeInt(fids.instrumentDev, fids.state, ClusterMusicCard.MUSIC_STOPPED)
        val info = port.writeBuffer(fids.instrumentDev, fids.info, ClusterMusicCard.encode(""))
        writeSinger(fids, "")
        fids.progress?.let { port.writeInt(fids.instrumentDev, it, 0) }
        if (ok(state) && ok(info)) {
            forget()
            return Outcome.CLEARED
        }
        if (nowMs != null && (state == null || info == null)) clearRetryAt = nowMs + REASSERT_MS
        return if (clearAttempts >= MAX_CLEAR_ATTEMPTS) Outcome.CLEAR_GAVE_UP else Outcome.CLEAR_FAILED
    }

    private suspend fun writeSinger(fids: ClusterMusicFids, artist: String) {
        val audio = fids.audioDev ?: return
        val singer = fids.singer ?: return
        port.writeBuffer(audio, singer, ClusterMusicCard.encode(artist))
    }

    private fun forget() {
        shown = null
        dirty = false
        progressSent = null
        clearAttempts = 0
        clearRetryAt = null
    }

    companion object {
        const val REASSERT_MS = 10_000L
        /**
         * ~30 s of polls for a refusing car, ~200 s for an unreachable helper (one attempt per
         * [REASSERT_MS]); after that the clear is dropped and logged instead of hammering a dead helper.
         */
        const val MAX_CLEAR_ATTEMPTS = 20
        /** Refused required writes in a row before the card is off until restart. */
        const val MAX_WRITE_REFUSALS = 3
    }
}

/** A helper write landed: status 1 real, 0 no-op; negative is an error, null the daemon unreachable. */
private fun ok(status: Int?): Boolean = status != null && status >= 0

/**
 * When the bridge re-arms notification-listener access: once when the switch is turned on, and
 * while getActiveSessions keeps refusing, at most every [retryMs]. Pure, so the timing is tested.
 */
class ClusterMusicAccess(private val retryMs: Long) {
    private var wasEnabled = false
    private var refusedSince: Long? = null

    /** True on an off -> on edge of the switch: check the grant before relying on it. */
    fun onSwitch(enabled: Boolean): Boolean {
        val rising = enabled && !wasEnabled
        wasEnabled = enabled
        return rising
    }

    /** True when a refused getActiveSessions should re-arm now. */
    fun onRefused(nowMs: Long): Boolean {
        val since = refusedSince
        if (since == null || nowMs - since >= retryMs) {
            refusedSince = nowMs
            return true
        }
        return false
    }

    /** Sessions read fine again: the next refusal re-arms at once. */
    fun onGranted() {
        refusedSince = null
    }
}
