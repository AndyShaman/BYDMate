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
 */
class ClusterMusicSync(private val port: Port) {

    /** The helper's raw writes: status 1 real, 0 no-op, <0 error, null daemon unreachable. */
    interface Port {
        suspend fun writeInt(dev: Int, fid: Int, value: Int): Int?
        suspend fun writeBuffer(dev: Int, fid: Int, bytes: ByteArray): Int?
    }

    /** What a [step] did, for the bridge's log and trace. */
    enum class Outcome { NONE, SHOWN, REASSERTED, TICKED, WRITE_FAILED, ABORTED, CLEARED, CLEAR_FAILED, CLEAR_GAVE_UP, HANDED_OFF }

    /** Last card the cluster confirmed; null when nothing of ours is known to be there. */
    var shown: Card? = null
        private set

    /** True while the cluster may hold our text and a clear is owed. */
    var dirty = false
        private set

    private var lastWriteAt = 0L
    private var progressSent: Int? = null
    private var playedSent: Triple<Int, Int, Int>? = null
    private var clearAttempts = 0

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
        if (!wanted) return if (dirty) clear(fids) else Outcome.NONE
        return when (target) {
            is Target.OtherPlaying -> handOff()
            Target.Idle -> if (dirty) clear(fids) else Outcome.NONE
            is Target.Show -> show(fids, target.card, nowMs, stillWanted)
        }
    }

    /**
     * Last clear before the bridge stops: one attempt, whatever the retry budget says, so a stop
     * never leaves our text behind when the helper can still take it.
     */
    suspend fun release(fids: ClusterMusicFids?): Outcome {
        if (fids == null || !dirty) return Outcome.NONE
        clearAttempts = 0
        return clear(fids)
    }

    /** Another app plays: the stock controller rewrites the card on the focus change. Ours is gone. */
    private fun handOff(): Outcome {
        if (!dirty && shown == null) return Outcome.NONE
        forget()
        return Outcome.HANDED_OFF
    }

    private suspend fun show(fids: ClusterMusicFids, card: Card, nowMs: Long, stillWanted: () -> Boolean): Outcome {
        val current = shown
        val newTrack = current?.steady() != card.steady()
        if (newTrack || nowMs - lastWriteAt >= REASSERT_MS) {
            val outcome = writeCard(fids, card, newTrack, stillWanted)
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

    private suspend fun writeCard(fids: ClusterMusicFids, card: Card, newTrack: Boolean, stillWanted: () -> Boolean): Outcome {
        dirty = true
        val writes = buildList<suspend () -> Boolean> {
            add { ok(port.writeInt(fids.instrumentDev, fids.source, ClusterMusicCard.SOURCE_THIRD_PARTY)) }
            add { ok(port.writeInt(fids.instrumentDev, fids.state, card.musicState)) }
            add { ok(port.writeBuffer(fids.instrumentDev, fids.info, ClusterMusicCard.encode(card.title))) }
        }
        for (write in writes) {
            if (!stillWanted()) { shown = null; return Outcome.ABORTED }
            if (!write()) { shown = null; return Outcome.WRITE_FAILED }
        }
        // Optional fids: best effort, a failure doesn't hold the card back.
        if (stillWanted()) writeSinger(fids, card.artist)
        if (newTrack && stillWanted()) resetTrackExtras(fids, card)
        if (!stillWanted()) { shown = null; return Outcome.ABORTED }
        shown = card
        clearAttempts = 0
        return Outcome.SHOWN
    }

    /**
     * A new track starts from a clean bar and clock: unknown progress or duration are written as
     * zero, so the previous track's bar and time never linger.
     */
    private suspend fun resetTrackExtras(fids: ClusterMusicFids, card: Card) {
        progressSent = null
        playedSent = null
        fids.progress?.let { if (ok(port.writeInt(fids.instrumentDev, it, card.progress ?: 0))) progressSent = card.progress ?: 0 }
        val audio = fids.audioDev ?: return
        fids.totalTime?.let { writeHms(audio, it, ClusterMusicCard.hms(card.durationSec ?: 0)) }
        fids.playTime?.let { if (writeHms(audio, it, ClusterMusicCard.hms(card.positionSec ?: 0))) playedSent = ClusterMusicCard.hms(card.positionSec ?: 0) }
    }

    /** Progress and played time as they move; a failed write is simply retried next tick. */
    private suspend fun tick(fids: ClusterMusicFids, card: Card): Boolean {
        var wrote = false
        val progress = card.progress
        if (progress != null && progress != progressSent && fids.progress != null) {
            if (ok(port.writeInt(fids.instrumentDev, fids.progress, progress))) progressSent = progress
            wrote = true
        }
        val position = card.positionSec
        val audio = fids.audioDev
        if (position != null && audio != null && fids.playTime != null) {
            val played = ClusterMusicCard.hms(position)
            val last = playedSent
            if (played != last) {
                var allOk = true
                if (played.first != last?.first) allOk = ok(port.writeInt(audio, fids.playTime[0], played.first)) && allOk
                if (played.second != last?.second) allOk = ok(port.writeInt(audio, fids.playTime[1], played.second)) && allOk
                if (played.third != last?.third) allOk = ok(port.writeInt(audio, fids.playTime[2], played.third)) && allOk
                playedSent = if (allOk) played else null
                wrote = true
            }
        }
        return wrote
    }

    /** What the stock sender sends when a source goes away: stopped, blank name and singer, zeroed extras. */
    private suspend fun clear(fids: ClusterMusicFids): Outcome {
        if (clearAttempts >= MAX_CLEAR_ATTEMPTS) return Outcome.NONE
        clearAttempts++
        val required = ok(port.writeInt(fids.instrumentDev, fids.state, ClusterMusicCard.MUSIC_STOPPED)) and
            ok(port.writeBuffer(fids.instrumentDev, fids.info, ClusterMusicCard.encode("")))
        writeSinger(fids, "")
        fids.progress?.let { port.writeInt(fids.instrumentDev, it, 0) }
        fids.audioDev?.let { audio ->
            fids.playTime?.let { writeHms(audio, it, Triple(0, 0, 0)) }
            fids.totalTime?.let { writeHms(audio, it, Triple(0, 0, 0)) }
        }
        if (required) {
            forget()
            return Outcome.CLEARED
        }
        return if (clearAttempts >= MAX_CLEAR_ATTEMPTS) Outcome.CLEAR_GAVE_UP else Outcome.CLEAR_FAILED
    }

    private suspend fun writeSinger(fids: ClusterMusicFids, artist: String) {
        val audio = fids.audioDev ?: return
        val singer = fids.singer ?: return
        port.writeBuffer(audio, singer, ClusterMusicCard.encode(artist))
    }

    private suspend fun writeHms(dev: Int, fids: List<Int>, hms: Triple<Int, Int, Int>): Boolean =
        ok(port.writeInt(dev, fids[0], hms.first)) and
            ok(port.writeInt(dev, fids[1], hms.second)) and
            ok(port.writeInt(dev, fids[2], hms.third))

    private fun forget() {
        shown = null
        dirty = false
        progressSent = null
        playedSent = null
        clearAttempts = 0
    }

    private fun ok(status: Int?): Boolean = status != null && status >= 0

    companion object {
        const val REASSERT_MS = 10_000L
        /** ~30 s of polls; after that the clear is dropped and logged instead of hammering a dead helper. */
        const val MAX_CLEAR_ATTEMPTS = 20
    }
}

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
