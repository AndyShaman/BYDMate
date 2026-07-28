package com.bydmate.app.media

import android.app.PendingIntent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.bydmate.app.MainActivity

/**
 * Foreground service that streams internet radio with ExoPlayer behind a [MediaSession].
 *
 * The UI never touches the player directly — it goes through [RadioController], which drives this
 * service over a `MediaController`. Playback keeps running when the app is backgrounded, which is
 * the point on a head unit where the driver switches to the navigation app while listening.
 *
 * Two things come from the session for free and are the reason this is not a plain Service:
 * the media notification with working prev / play-pause / next, and the head unit's own transport
 * controls (steering wheel, dashboard media buttons), which route through the platform media
 * session rather than through the app's own UI.
 *
 * ExoPlayer also replaces the framework `MediaPlayer` that shipped in 3.8.1 for a plain codec
 * reason: most Russian stations serve HE-AAC (`.aacp`) mounts, which `MediaPlayer` fails to decode
 * on DiLink — Радио Record and Русское Радио simply never started.
 */
@OptIn(UnstableApi::class)
class RadioPlayerService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    private val handler = Handler(Looper.getMainLooper())
    private var retries = 0
    private var retryScheduled = false

    private val retryListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            // A dropped stream is the normal case in a moving car, not a reason to give up:
            // re-prepare with a backoff before surfacing the error to the driver.
            if (retries >= MAX_RETRIES) {
                Log.w(TAG, "giving up after $retries retries", error)
                return
            }
            if (retryScheduled) return
            retryScheduled = true
            val delay = RETRY_DELAY_MS * (1L shl retries)
            retries++
            Log.w(TAG, "stream error, retry $retries in ${delay}ms", error)
            handler.postDelayed({
                retryScheduled = false
                player?.let { p ->
                    p.prepare()
                    p.play()
                }
            }, delay)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Audio is flowing again — the next drop gets the full retry budget.
            if (isPlaying) retries = 0
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            retries = 0
        }
    }

    override fun onCreate() {
        super.onCreate()

        val exo = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true
            )
            // WAKE_MODE_NETWORK keeps both the CPU and the wifi radio up with the screen off.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setHandleAudioBecomingNoisy(true)
            .build()
        // Prev on the first station wraps to the last one, so the driver can cycle in either
        // direction without looking away from the road.
        exo.repeatMode = Player.REPEAT_MODE_ALL
        exo.addListener(retryListener)
        player = exo

        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        session = MediaSession.Builder(this, exo)
            .setSessionActivity(openApp)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    // The user swiped the app away from recents — do not keep audio running for an app they
    // just dismissed (unchanged from the 3.8.1 behaviour).
    override fun onTaskRemoved(rootIntent: Intent?) {
        player?.let { p ->
            p.stop()
            p.clearMediaItems()
        }
        stopSelf()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        session?.run {
            player.release()
            release()
        }
        session = null
        player = null
        super.onDestroy()
    }

    companion object {
        private const val TAG = "RadioPlayerService"
        private const val MAX_RETRIES = 4
        private const val RETRY_DELAY_MS = 2_000L
    }
}
