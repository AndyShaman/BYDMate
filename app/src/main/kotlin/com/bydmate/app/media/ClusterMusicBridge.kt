package com.bydmate.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.data.nativestack.FidCatalogManager
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.media.ClusterMusicCard.Target
import com.bydmate.app.media.ClusterMusicSync.Outcome
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
 */
@Singleton
class ClusterMusicBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
    private val catalogManager: FidCatalogManager,
) {
    private val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    private val sync = ClusterMusicSync(object : ClusterMusicSync.Port {
        override suspend fun writeInt(dev: Int, fid: Int, value: Int): Int? = helper.writeStatus(dev, fid, value)
        override suspend fun writeBuffer(dev: Int, fid: Int, bytes: ByteArray): Int? =
            helper.writeBufferStatus(dev, fid, bytes)
    })
    private val mutex = Mutex()

    /** The final clear must outlive the service scope, which TrackingService cancels right after stop(). */
    private val ownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var job: Job? = null
    @Volatile private var ensureAccess: (suspend (String) -> Unit)? = null
    private val access = ClusterMusicAccess(ACCESS_RETRY_MS)
    private var wasEnabled = false
    private var lastFids: ClusterMusicFids? = null
    private var fidsLogged = false
    private var lastTargetKind: String? = null

    /** The package the last poll gave the card to; it keeps the card while nobody plays. */
    private var lastOwner: String? = null

    /**
     * [ensureAccess] re-arms notification-listener access (TrackingService's GrantSelfHeal); it runs
     * when the switch is turned on and while getActiveSessions is refused.
     */
    fun start(scope: CoroutineScope, ensureAccess: suspend (String) -> Unit) {
        this.ensureAccess = ensureAccess
        job?.cancel()
        job = scope.launch {
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
        val target = readTarget() ?: return
        val kind = when (target) {
            is Target.Show -> "show:${target.packageName}"
            is Target.OtherPlaying -> "other:${target.packageName}"
            Target.Idle -> "idle"
        }
        if (kind != lastTargetKind) {
            Log.i(TAG, "target $kind")
            lastTargetKind = kind
        }
        val outcome = sync.step(true, fids, target, SystemClock.elapsedRealtime()) { enabled() && job?.isActive == true }
        report(outcome, target)
    }

    /** The catalog arrives some time after start; until it has every required symbol nothing is written. */
    private fun resolveFids(): ClusterMusicFids? {
        val catalog = catalogManager.catalog
        val fids = ClusterMusicFids.resolve(catalog)
        if (fids != null) lastFids = fids
        if (!fidsLogged && (fids != null || catalog != null)) {
            fidsLogged = true
            if (fids != null) {
                Log.i(TAG, "fids: $fids")
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

    /**
     * Null when the sessions can't be read: without listener access the poll decides nothing.
     * [rearm] false (the feature is off or stopping) skips re-arming the access.
     */
    private suspend fun readTarget(rearm: Boolean = true): Target? {
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
        val sessions = controllers.map {
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
        val target = ClusterMusicCard.decide(sessions, lastOwner)
        when (target) {
            is Target.Show -> lastOwner = target.packageName
            is Target.OtherPlaying -> lastOwner = target.packageName
            Target.Idle -> Unit
        }
        return target
    }

    private fun report(outcome: Outcome, target: Target?, reason: String? = null) {
        when (outcome) {
            Outcome.NONE, Outcome.TICKED, Outcome.REASSERTED, Outcome.RETRYING -> return
            Outcome.SHOWN -> {
                // Lengths only: users post these logs in public issues.
                val card = (target as? Target.Show)?.card
                Log.i(TAG, "card <- title(${card?.title?.length}) artist(${card?.artist?.length}) " +
                    "state=${card?.musicState} progress=${card?.progress ?: "no duration"}")
            }
            Outcome.REFUSED -> Log.w(TAG, "card off until restart: the car refused it ${ClusterMusicSync.MAX_WRITE_REFUSALS} times")
            else -> Log.i(TAG, "card ${outcome.name.lowercase()}${reason?.let { " ($it)" } ?: ""}")
        }
        Trace.event(TraceArea.CAR, "cluster_music", "outcome" to outcome.name.lowercase(), "reason" to reason)
    }

    companion object {
        private const val TAG = "ClusterMusicBridge"
        const val POLL_MS = 1_500L
        /** How long stop() keeps retrying its clear before giving up. */
        const val STOP_CLEAR_TIMEOUT_MS = 5_000L
        /** Spacing of the access re-arm while getActiveSessions keeps refusing. */
        const val ACCESS_RETRY_MS = 60_000L
    }
}
