package com.bydmate.app.media

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bydmate.app.R
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's single handle on [RadioPlayerService].
 *
 * Screens call [play] / [step] / [stop] and observe [state]; everything underneath is a
 * `MediaController` talking to the service's `MediaSession`. Going through the session rather
 * than through service intents is what keeps the app UI, the media notification and the head
 * unit's own transport buttons showing and doing the same thing.
 *
 * Main thread only — `MediaController` requires it.
 */
object RadioController {

    private const val TAG = "RadioController"
    private const val EXTRA_ICON_REF = "bydmate.icon_ref"

    private val _state = MutableStateFlow(RadioPlayback())
    val state: StateFlow<RadioPlayback> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private var appContext: Context? = null

    /** Commands issued before the connection is up, replayed in order once it is. */
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = publish()
        override fun onPlayerError(error: PlaybackException) {
            Log.w(TAG, "playback error", error)
            publish(error)
        }
    }

    /** Starts [tracks] at [index]; a station already playing is replaced. */
    fun play(context: Context, tracks: List<RadioTrack>, index: Int) {
        if (tracks.isEmpty()) return
        val target = index.coerceIn(0, tracks.lastIndex)
        withController(context) { c ->
            c.setMediaItems(tracks.map(::toMediaItem), target, C.TIME_UNSET)
            c.prepare()
            c.play()
        }
    }

    /**
     * Moves [delta] stations from the one playing, wrapping at both ends.
     *
     * With the same list already loaded this is a session seek, so the notification and the
     * steering wheel stay in step; otherwise (nothing playing yet, or the list changed under us)
     * the playlist is reloaded at the station the driver asked for.
     */
    fun step(context: Context, tracks: List<RadioTrack>, delta: Int) {
        if (tracks.isEmpty()) return
        val c = controller
        if (c != null && c.mediaItemCount > 0 && loadedIds(c) == tracks.map { it.id }) {
            if (delta >= 0) c.seekToNextMediaItem() else c.seekToPreviousMediaItem()
            // Only after an error has left the player idle — re-preparing a live player mid-seek
            // would restart the source for nothing.
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
            return
        }
        val from = tracks.indexOfFirst { it.id == _state.value.stationId }
        // floorMod keeps the wrap correct for the -1 of "prev from nothing playing".
        play(context, tracks, Math.floorMod(from + delta, tracks.size))
    }

    /**
     * Re-applies [tracks] to the station already playing — used when the stream URL behind it
     * changes under the player, as the data-saver setting does. A no-op with nothing playing:
     * the next start picks the new list up anyway.
     */
    fun reload(context: Context, tracks: List<RadioTrack>) {
        val c = controller ?: return
        if (!c.isConnected || c.mediaItemCount == 0) return
        val index = tracks.indexOfFirst { it.id == _state.value.stationId }
        if (index < 0) return
        play(context, tracks, index)
    }

    fun stop(context: Context) {
        withController(context) { c ->
            c.stop()
            c.clearMediaItems()
        }
    }

    private fun loadedIds(c: MediaController): List<Long> =
        (0 until c.mediaItemCount).map { c.getMediaItemAt(it).mediaId.toLongOrNull() ?: 0L }

    private fun toMediaItem(track: RadioTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(track.url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.name)
                    .setArtworkUri(track.artworkUri?.let(Uri::parse))
                    // Extras survive the controller/session boundary, which is how the floating
                    // widget gets the same logo reference the Radio tab resolves from the DB.
                    .setExtras(Bundle().apply { putString(EXTRA_ICON_REF, track.iconRef) })
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .build()
            )
            .build()

    private fun withController(context: Context, command: (MediaController) -> Unit) {
        appContext = context.applicationContext
        val ready = controller
        if (ready != null && ready.isConnected) {
            command(ready)
            return
        }
        pending.addLast(command)
        connect(context.applicationContext)
    }

    private fun connect(context: Context) {
        if (connecting != null) return
        val token = SessionToken(context, ComponentName(context, RadioPlayerService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        connecting = future
        future.addListener({
            connecting = null
            val c = runCatching { future.get() }
                .onFailure { Log.w(TAG, "controller connect failed", it) }
                .getOrNull()
            if (c == null) {
                pending.clear()
                return@addListener
            }
            controller = c
            c.addListener(listener)
            while (pending.isNotEmpty()) pending.removeFirst().invoke(c)
            publish()
        }, ContextCompat.getMainExecutor(context))
    }

    private fun publish(error: PlaybackException? = null) {
        val c = controller
        if (c == null || !c.isConnected) {
            _state.value = RadioPlayback()
            return
        }
        val item = c.currentMediaItem
        val id = item?.mediaId?.toLongOrNull() ?: 0L
        val name = item?.mediaMetadata?.title?.toString().orEmpty()
        val failure = error ?: c.playerError
        val status = when {
            failure != null -> RadioStatus.ERROR
            item == null -> RadioStatus.IDLE
            c.playbackState == Player.STATE_BUFFERING -> RadioStatus.BUFFERING
            c.playbackState == Player.STATE_READY && c.isPlaying -> RadioStatus.PLAYING
            c.playbackState == Player.STATE_READY -> RadioStatus.PAUSED
            else -> RadioStatus.IDLE
        }
        _state.value = RadioPlayback(
            stationId = if (status == RadioStatus.IDLE) 0L else id,
            stationName = if (status == RadioStatus.IDLE) "" else name,
            stationIcon = item?.mediaMetadata?.extras?.getString(EXTRA_ICON_REF),
            status = status,
            errorMessage = if (failure != null) {
                appContext?.getString(R.string.radio_error_stream)
            } else {
                null
            }
        )
    }
}
