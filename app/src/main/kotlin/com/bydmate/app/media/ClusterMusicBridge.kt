package com.bydmate.app.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.os.SystemClock
import android.util.Log
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.media.ClusterMusicCard.Card
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors a non-whitelisted player's track (Yandex Navigator's Alice music, Yandex Music) onto
 * the instrument cluster's music card, writing the same fids the stock MediaInfoSender writes
 * for whitelisted apps: source, state and name on the instrument (dev 1007), singer on audio
 * (dev 1002). The app uid is refused these writes, so they go through the shell-uid helper, the
 * same setInt / setBuffer path [com.bydmate.app.hud.HudCanChannel] uses for the road name.
 *
 * Polls rather than registering MediaController callbacks: the stock controller blanks the card
 * ~500 ms after every audio-focus change, and a periodic re-assert covers that without tracking
 * its timing. Sessions come through the notification-listener foothold
 * ([MediaSessionListenerService]) that the knob play/pause already relies on.
 *
 * Checked on a Sealion 07 (DiLink 5.0): source 26 renders the text, the "armrest screen" singer
 * fid is the card's second line, long titles scroll, and the stock controller leaves the card
 * alone on a track change while focus stays with the same app, so every change is ours to push.
 * The progress bar follows INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET; played / total time go out like
 * the stock sender's, though this car's card layout doesn't display them. Cover art is out of reach: the
 * stock sender hands it to `content://com.byd.mediacenter.provider/info`, whose read and write
 * permissions are signature-level, and the shell uid is refused.
 */
@Singleton
class ClusterMusicBridge @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helper: HelperClient,
) {
    private val prefs = context.getSharedPreferences(ClusterProjectionManager.PREFS_NAME, Context.MODE_PRIVATE)
    private var job: Job? = null
    private var lastCard: Card? = null
    private var lastWriteAt = 0L
    private var lastProgress: Int? = null
    private var lastPlayed: Triple<Int, Int, Int>? = null

    fun start(scope: CoroutineScope) {
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure { Log.w(TAG, "tick failed: ${it.message}") }
                delay(POLL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        lastCard = null
    }

    private suspend fun tick() {
        if (!prefs.getBoolean(ClusterProjectionManager.KEY_CLUSTER_MUSIC_CARD, false)) {
            if (lastCard != null) clear()
            return
        }
        val card = ClusterMusicCard.pick(readSessions())
        val now = SystemClock.elapsedRealtime()
        when {
            card == null -> if (lastCard != null) clear()
            card.steady() != lastCard?.steady() || now - lastWriteAt >= REASSERT_MS -> write(card, now)
        }
        if (card == null) return
        if (card.progress != null && card.progress != lastProgress) {
            helper.writeStatus(DEV_INSTRUMENT, FID_MUSIC_PROGRESS, card.progress)
            lastProgress = card.progress
        }
        card.positionSec?.let { writePlayed(ClusterMusicCard.hms(it)) }
    }

    private fun readSessions(): List<ClusterMusicCard.SessionSnapshot> = runCatching {
        val msm = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val now = SystemClock.elapsedRealtime()
        msm.getActiveSessions(ComponentName(context, MediaSessionListenerService::class.java)).map {
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
                    val moving = st.state == android.media.session.PlaybackState.STATE_PLAYING
                    if (st.position < 0) null
                    else st.position + if (moving) ((now - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L
                },
                durationMs = md?.getLong(MediaMetadata.METADATA_KEY_DURATION),
            )
        }
    }.getOrElse { emptyList() }

    /** Played time, only the parts that moved: normally just the seconds. */
    private suspend fun writePlayed(played: Triple<Int, Int, Int>) {
        val last = lastPlayed
        if (played.first != last?.first) helper.writeStatus(DEV_AUDIO, FID_PLAY_HOUR, played.first)
        if (played.second != last?.second) helper.writeStatus(DEV_AUDIO, FID_PLAY_MINUTE, played.second)
        if (played.third != last?.third) helper.writeStatus(DEV_AUDIO, FID_PLAY_SECOND, played.third)
        lastPlayed = played
    }

    private suspend fun writeTotal(totalSec: Int) {
        val (h, m, sec) = ClusterMusicCard.hms(totalSec)
        helper.writeStatus(DEV_AUDIO, FID_TOTAL_HOUR, h)
        helper.writeStatus(DEV_AUDIO, FID_TOTAL_MINUTE, m)
        helper.writeStatus(DEV_AUDIO, FID_TOTAL_SECOND, sec)
    }

    private suspend fun write(card: Card, now: Long) {
        val changed = card.steady() != lastCard?.steady()
        val src = helper.writeStatus(DEV_INSTRUMENT, FID_MUSIC_SOURCE, ClusterMusicCard.SOURCE_THIRD_PARTY)
        val state = helper.writeStatus(DEV_INSTRUMENT, FID_MUSIC_STATE, card.musicState)
        val name = helper.writeBufferStatus(DEV_INSTRUMENT, FID_MUSIC_INFO, ClusterMusicCard.encode(card.title))
        val singer = helper.writeBufferStatus(DEV_AUDIO, FID_SINGER_NAME, ClusterMusicCard.encode(card.artist))
        if (changed) {
            Log.i(TAG, "card <- \"${card.title}\" / \"${card.artist}\" state=${card.musicState} " +
                "progress=${card.progress ?: "no duration"} rc src=$src state=$state name=$name singer=$singer")
            if (card.progress == null) lastProgress = null
            // New track: total time once, and played time from scratch.
            writeTotal(card.durationSec ?: 0)
            lastPlayed = null
        }
        lastCard = card
        lastWriteAt = now
    }

    /** What the stock sender sends when a source goes away: stopped, blank name and singer. */
    private suspend fun clear() {
        helper.writeStatus(DEV_INSTRUMENT, FID_MUSIC_STATE, ClusterMusicCard.MUSIC_STOPPED)
        helper.writeStatus(DEV_INSTRUMENT, FID_MUSIC_PROGRESS, 0)
        lastProgress = null
        writePlayed(Triple(0, 0, 0))
        writeTotal(0)
        lastPlayed = null
        helper.writeBufferStatus(DEV_INSTRUMENT, FID_MUSIC_INFO, ClusterMusicCard.encode(""))
        helper.writeBufferStatus(DEV_AUDIO, FID_SINGER_NAME, ClusterMusicCard.encode(""))
        Log.i(TAG, "card cleared")
        lastCard = null
    }

    companion object {
        private const val TAG = "ClusterMusicBridge"
        const val POLL_MS = 1_500L
        const val REASSERT_MS = 10_000L

        const val DEV_INSTRUMENT = 1007
        const val DEV_AUDIO = 1002
        const val FID_MUSIC_INFO = 1140527112     // INSTRUMENT_MUSIC_INFO_SET (buffer)
        const val FID_MUSIC_STATE = 1138753546    // INSTRUMENT_MUSIC_STATE_SET
        const val FID_MUSIC_SOURCE = 871366704    // INSTRUMENT_MUSIC_SOURCE_SET, CAN-FD variant
        const val FID_MUSIC_PROGRESS = 1138753552 // INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET (0..100)
        // Played / total time on the audio device, split the way the stock sendAudioTime does.
        const val FID_PLAY_HOUR = 1309671432      // AUDIO_PLAY_TIME_HOUR_SET
        const val FID_PLAY_MINUTE = 1309671440    // AUDIO_PLAY_TIME_MINUTE_SET
        const val FID_PLAY_SECOND = 1309671448    // AUDIO_PLAY_TIME_SECOND_SET
        const val FID_TOTAL_HOUR = 1309671456     // AUDIO_TOTAL_TIME_HOUR_SET
        const val FID_TOTAL_MINUTE = 1309671464   // AUDIO_TOTAL_TIME_MINUTE_SET
        const val FID_TOTAL_SECOND = 1309671472   // AUDIO_TOTAL_TIME_SECOND_SET
        const val FID_SINGER_NAME = 1140396040    // AUDIO_ARMREST_SCREEN_SINGER_NAME_SET (buffer)
    }
}
