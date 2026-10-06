package com.bydmate.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidCatalogManager
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.media.ClusterMusicCard.Target
import com.bydmate.app.media.ClusterMusicSync.Outcome
import com.bydmate.app.split.Split37Engine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the track of any player the stock controller leaves out (Yandex Navigator's Alice music,
 * Yandex Music, Spotify, internet radio...) onto the instrument cluster's music card, which it
 * leaves blank for them. It writes the fids the stock MediaInfoSender
 * writes for whitelisted apps, resolved from this firmware's catalog ([ClusterMusicFids]), through
 * the shell-uid helper: the app uid is refused these writes, as for
 * [com.bydmate.app.hud.HudCanChannel]'s road name.
 *
 * Polls rather than registering MediaController callbacks: the stock controller blanks the card on
 * every audio-focus change, and the periodic re-assert in [ClusterMusicSync] covers that without
 * tracking its timing. Every poll runs under [mutex], and [stop] waits for the running one before
 * its own clear, so no write of a cancelled poll lands after the clear.
 *
 * Checked on a Sealion 07 (DiLink 5.0): source 26 renders the text, the "armrest screen" singer fid
 * is the card's second line, long titles scroll, the bar follows the progress fid, and the stock
 * controller leaves the card alone on a track change while focus stays with the same app. Played /
 * total time are not sent: that card doesn't show them. Cover art
 * is out of reach: the stock sender hands it to `content://com.byd.mediacenter.provider/info`, whose
 * read and write permissions are signature-level, and the shell uid is refused.
 *
 * Platformized (UI7) firmware, checked on a Leopard 3 (build 20260514): the cluster leaves the
 * card blank for source 26 and renders it for 11, and the stock firmware puts 26 back every
 * ~10 s. There the card is written with 11 and a second loop ([WATCH_MS]) reads the source back
 * while our card is shown, rewriting the card as soon as it changed ([ClusterMusicSync.watch]).
 * Other firmware runs no watch and writes exactly what it did before.
 */
@Singleton
@Suppress("TooManyFunctions") // the poll, the UI7 watch, the session reads and the dump
class ClusterMusicBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
    private val catalogManager: FidCatalogManager,
) {
    private val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val ui7 = Split37Engine.isPlatformizedFirmware()
    private val sync = ClusterMusicSync(object : ClusterMusicSync.Port {
        override suspend fun writeInt(dev: Int, fid: Int, value: Int): Int? = helper.writeStatus(dev, fid, value)
        override suspend fun writeBuffer(dev: Int, fid: Int, bytes: ByteArray): Int? =
            helper.writeBufferStatus(dev, fid, bytes)
        override suspend fun readInt(dev: Int, fid: Int): Int? =
            runCatching { helper.read(dev, fid) }
                .onFailure { if (it is CancellationException) throw it }
                .getOrNull()
                ?.toInt()
                ?.let { SentinelDecoder.decodeInt(it) }
    }, ui7)
    private val mutex = Mutex()

    /** The final clear must outlive the service scope, which TrackingService cancels right after stop(). */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    @Volatile private var ensureAccess: (suspend (String) -> Unit)? = null
    private val access = ClusterMusicAccess(ACCESS_RETRY_MS)
    private var wasEnabled = false
    @Volatile private var lastFids: ClusterMusicFids? = null
    private var fidsLogged = false
    private val owner = ClusterMusicOwner()
    /** Last outcome other than NONE / TICKED and when, for the dump. */
    @Volatile private var lastOutcome: Outcome? = null
    @Volatile private var lastOutcomeAt = 0L
    /** Outcome and reason of the last cluster_music outcome line in the trace. */
    @Volatile private var lastTracedOutcome: String? = null

    /**
     * [ensureAccess] re-arms notification-listener access (TrackingService's GrantSelfHeal); it runs
     * when the switch is turned on and while getActiveSessions is refused.
     */
    fun start(scope: CoroutineScope, ensureAccess: suspend (String) -> Unit) {
        this.ensureAccess = ensureAccess
        job?.cancel()
        job = scope.launch {
            // A child of the poll: stop() cancels and joins both before its clear.
            if (ui7) launch {
                while (isActive) {
                    delay(WATCH_MS)
                    runCatching { mutex.withLock { if (isActive) watchTick() } }.onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "watch failed: ${it.message}")
                    }
                }
            }
            while (isActive) {
                runCatching { mutex.withLock { if (isActive) tick() } }.onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "tick failed: ${it.message}")
                }
                delay(POLL_MS)
            }
        }
    }

    /** Waits for the running poll, then clears whatever of ours the cluster may still hold. */
    fun stop() {
        val running = job ?: return
        job = null
        ownScope.launch {
            running.cancelAndJoin()
            val outcome = withTimeoutOrNull(STOP_CLEAR_TIMEOUT_MS) {
                mutex.withLock {
                    // The owner is read again before every attempt: another player may take
                    // the card while a failed clear waits for its retry.
                    var result = sync.release(lastFids, ownerBeforeClear())
                    while (result == Outcome.CLEAR_FAILED) {
                        delay(POLL_MS)
                        result = sync.release(lastFids, ownerBeforeClear())
                    }
                    wasEnabled = false
                    access.onSwitch(false)
                    result
                }
            }
            report(outcome ?: Outcome.CLEAR_GAVE_UP, null, reason = "stop")
        }
    }

    private fun enabled(): Boolean = prefs.getBoolean(ClusterProjectionManager.KEY_CLUSTER_MUSIC_CARD, false)

    private suspend fun tick() {
        val enabled = enabled()
        if (enabled != wasEnabled) {
            Log.i(TAG, "switch ${if (enabled) "on" else "off"}")
            Trace.event(TraceArea.CAR, "cluster_music", "switch" to if (enabled) "on" else "off")
            wasEnabled = enabled
        }
        if (access.onSwitch(enabled)) ensureAccess?.invoke("cluster-music on")
        val fids = resolveFids()
        if (!enabled) {
            report(sync.step(false, fids, ownerBeforeClear(), SystemClock.elapsedRealtime()), null)
            return
        }
        val sessions = readSessions() ?: return
        val target = observe(sessions, via = "poll")
        val outcome = sync.step(true, fids, target, SystemClock.elapsedRealtime()) { enabled() && job?.isActive == true }
        report(outcome, target)
    }

    /**
     * Decides the target from [sessions] and records it in [owner], logging a change of target.
     * The watch goes through here too, so a player it shows owns the card at the next poll.
     */
    private fun observe(sessions: List<ClusterMusicCard.SessionSnapshot>, via: String): Target {
        val previous = owner.lastOwner
        val target = ClusterMusicCard.decide(sessions, lastOwner = previous)
        val kind = owner.observe(target) ?: return target
        // Package and PlaybackState only: users post these logs in public issues.
        val states = sessions.take(MAX_LOGGED_SESSIONS).joinToString(" ") { "${it.packageName}:${it.playbackState}" }
        Log.i(TAG, "target $kind sessions=[$states] owner=$previous via=$via")
        Trace.event(TraceArea.CAR, "cluster_music", "target" to kind, "sessions" to states, "owner" to previous, "via" to via)
        return target
    }

    /**
     * UI7 only: one source read while our card is shown, a full rewrite when the firmware changed
     * it. The owner is decided again right before a rewrite, exactly as a poll decides it.
     */
    private suspend fun watchTick() {
        val outcome = sync.watch(
            lastFids,
            SystemClock.elapsedRealtime(),
            current = { readSessions(rearm = false)?.let { observe(it, via = "watch") } },
        ) { enabled() && job?.isActive == true }
        report(outcome, null, reason = "watch")
    }

    /** The catalog arrives some time after start; until it has every required symbol nothing is written. */
    private fun resolveFids(): ClusterMusicFids? {
        val catalog = catalogManager.catalog
        val fids = ClusterMusicFids.resolve(catalog)
        if (fids != null) lastFids = fids
        if (!fidsLogged && (fids != null || catalog != null)) {
            fidsLogged = true
            if (fids != null) {
                Log.i(TAG, "fids: $fids source=${sync.sourceCode} ui7=$ui7")
            } else {
                val missing = ClusterMusicFids.missing(catalog).joinToString()
                Log.w(TAG, "card off on this firmware, catalog lacks $missing")
                Trace.event(TraceArea.CAR, "cluster_music", "unsupported" to missing)
            }
        }
        return fids
    }

    /**
     * Who owns playback right before a clear of ours (switch off, stop): another player that took
     * the card since the last poll keeps it. Unreadable sessions fall back to [Target.Idle], a clear.
     */
    private suspend fun ownerBeforeClear(): Target {
        if (!sync.dirty) return Target.Idle
        return runCatching { readTarget(rearm = false) }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "owner read before clear failed: ${it.javaClass.simpleName}")
        }.getOrNull() ?: Target.Idle
    }

    private suspend fun readTarget(rearm: Boolean): Target? =
        readSessions(rearm)?.let { ClusterMusicCard.decide(it, lastOwner = owner.lastOwner) }

    /**
     * Null when the sessions can't be read: without listener access the poll decides nothing.
     * [rearm] false (the feature is off or stopping) skips re-arming the access.
     */
    private suspend fun readSessions(rearm: Boolean = true): List<ClusterMusicCard.SessionSnapshot>? {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val controllers = try {
            msm.getActiveSessions(ComponentName(context, MediaSessionListenerService::class.java))
        } catch (e: SecurityException) {
            if (rearm && access.onRefused(SystemClock.elapsedRealtime())) {
                Log.w(TAG, "no notification-listener access, re-arming: ${e.message}")
                Trace.event(TraceArea.CAR, "cluster_music", "access" to "missing")
                ensureAccess?.invoke("cluster-music no access")
            }
            return null
        }
        access.onGranted()
        val now = SystemClock.elapsedRealtime()
        return controllers.map {
            val md = it.metadata
            val pb = it.playbackState
            ClusterMusicCard.SessionSnapshot(
                packageName = it.packageName,
                playbackState = pb?.state,
                title = md?.getString(MediaMetadata.METADATA_KEY_TITLE),
                artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                    ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST),
                positionMs = pb?.let { st ->
                    // PlaybackState.position is as of lastPositionUpdateTime; advance it while playing.
                    val moving = st.state == PlaybackState.STATE_PLAYING
                    if (st.position < 0) null
                    else st.position + if (moving) ((now - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
                },
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
        }
    }

    private fun report(outcome: Outcome, target: Target?, reason: String? = null) {
        if (outcome != Outcome.NONE && outcome != Outcome.TICKED) {
            lastOutcome = outcome
            lastOutcomeAt = SystemClock.elapsedRealtime()
        }
        when (outcome) {
            Outcome.NONE, Outcome.TICKED, Outcome.REASSERTED, Outcome.RETRYING -> return
            Outcome.SOURCE_REASSERTED -> {
                // The firmware flips the source every ~10 s: the first rewrite and every Nth.
                val n = sync.sourceReasserts
                if (n != 1 && n % SOURCE_LOG_EVERY != 0) return
                Log.i(TAG, "card rewritten #$n: the firmware set source ${sync.lastSourceRead}, ours is ${sync.sourceCode}")
            }
            Outcome.READ_FAILED ->
                Log.w(TAG, "source read failed, next read in ${ClusterMusicSync.REASSERT_MS / 1_000} s")
            Outcome.SHOWN -> {
                // Lengths only: users post these logs in public issues.
                val show = target as? Target.Show
                val card = show?.card
                Log.i(TAG, "card <- ${show?.packageName} title(${card?.title?.length}) artist(${card?.artist?.length}) " +
                    "state=${card?.musicState} progress=${card?.progress ?: "no duration"}")
            }
            Outcome.REFUSED -> Log.w(TAG, "card off until restart: the car refused it ${ClusterMusicSync.MAX_WRITE_REFUSALS} times")
            else -> Log.i(TAG, "card ${outcome.name.lowercase()}${reason?.let { " ($it)" } ?: ""}")
        }
        traceOnChange(outcome, reason)
    }

    /** Only a change of outcome: every new track is another "shown", a hundred lines a drive. */
    private fun traceOnChange(outcome: Outcome, reason: String?) {
        val key = "${outcome.name}|$reason"
        if (key == lastTracedOutcome) return
        lastTracedOutcome = key
        Trace.event(TraceArea.CAR, "cluster_music", "outcome" to outcome.name.lowercase(), "reason" to reason)
    }

    /** The `--- cluster music ---` dump section; memory only, no helper call. */
    fun dumpLines(): List<String> {
        val now = SystemClock.elapsedRealtime()
        val outcome = lastOutcome
        val readAt = sync.lastSourceReadAt
        return listOf(
            "switch=${if (enabled()) "on" else "off"} running=${job?.isActive == true}",
            "ui7=${if (ui7) "yes" else "no"} source=${sync.sourceCode} watch=${if (ui7) "${WATCH_MS} ms" else "off"}",
            "fids=${lastFids ?: "(unresolved)"}",
            "owner=${owner.lastOwner ?: "(none)"} target=${owner.lastTargetKind ?: "(none)"} " +
                "shown=${sync.shown != null} dirty=${sync.dirty} refused=${sync.refused}",
            "last_outcome=" + (outcome?.let { "${it.name.lowercase()} age=${(now - lastOutcomeAt) / 1_000}s" } ?: "(none)"),
            "source_reasserts=${sync.sourceReasserts} last_source_read=" +
                (sync.lastSourceRead?.let { "$it age=${(now - readAt) / 1_000}s" } ?: "(none)"),
        )
    }

    companion object {
        private const val TAG = "ClusterMusicBridge"
        const val POLL_MS = 1_500L
        /** UI7 source watch: the firmware flips the source every ~10 s, a read a second catches it. */
        const val WATCH_MS = 1_000L
        /** Source rewrites between two log lines (~5 min at the firmware's pace). */
        private const val SOURCE_LOG_EVERY = 30
        /** How long stop() keeps retrying its clear before giving up. */
        const val STOP_CLEAR_TIMEOUT_MS = 5_000L
        /** Spacing of the access re-arm while getActiveSessions keeps refusing. */
        const val ACCESS_RETRY_MS = 60_000L
        /** Sessions listed in the target-change log line. */
        private const val MAX_LOGGED_SESSIONS = 6
    }
}
