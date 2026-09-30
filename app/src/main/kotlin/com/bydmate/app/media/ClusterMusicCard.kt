package com.bydmate.app.media

/**
 * Pure half of [ClusterMusicBridge]: who owns playback right now, what the card should say, and
 * how the text goes on the wire. JVM-tested; nothing here touches Android.
 *
 * Background: the stock `com.byd.mediacontroller` fills the card (INSTRUMENT_MUSIC_INFO_SET and
 * friends) only for a hard-coded whitelist of Chinese players, BYD's own player and Bluetooth.
 * Any other audio-focus owner gets `sendBlackThirdPartyAppMediaInfo()`: source 26, state 2 and a
 * blank name. Yandex Navigator's in-app music (Alice) and Yandex Music are not whitelisted, so the
 * card stays empty while they play.
 */
object ClusterMusicCard {

    /** Packages whose MediaSession is mirrored onto the card. */
    val SOURCE_PACKAGES = listOf("ru.yandex.yandexnavi", "ru.yandex.music")

    // PlaybackState.STATE_* as literals (android.jar members are stubs on the JVM).
    private const val PB_PAUSED = 2
    private const val PB_PLAYING = 3
    private const val PB_BUFFERING = 6

    /** INSTRUMENT_MUSIC_STATE_SET values, as the stock OtherClient sends them. */
    const val MUSIC_PLAYING = 1
    const val MUSIC_PAUSED = 2
    const val MUSIC_STOPPED = 3

    /** The instrument's music-source code for an unlisted third-party app. */
    const val SOURCE_THIRD_PARTY = 26

    /** The stock sender caps the buffer at 255 bytes; one less keeps UTF-16 units whole. */
    const val MAX_TEXT_BYTES = 254

    data class SessionSnapshot(
        val packageName: String,
        /** PlaybackState.STATE_* value; null = the session reports no state. */
        val playbackState: Int?,
        val title: String?,
        val artist: String?,
        /** Current position, already advanced to "now" by the caller; null = unknown. */
        val positionMs: Long? = null,
        /** METADATA_KEY_DURATION; null or <= 0 = unknown. */
        val durationMs: Long? = null,
    )

    /**
     * [progress] is the 0..100 bar value; [positionSec] / [durationSec] feed the played / total
     * time fids. Each is null when the session doesn't give it.
     */
    data class Card(
        val title: String,
        val artist: String,
        val musicState: Int,
        val progress: Int? = null,
        val positionSec: Int? = null,
        val durationSec: Int? = null,
    ) {
        /** The part that only changes with the track or play state; progress and position tick. */
        fun steady(): Card = copy(progress = null, positionSec = null)
    }

    /** What the bridge should do with the card this tick. */
    sealed interface Target {
        /** A source package owns playback: the card should say this. */
        data class Show(val card: Card) : Target

        /** Another app is playing: the stock controller owns the card, hands off. */
        data class OtherPlaying(val packageName: String) : Target

        /** Nobody is playing and no source session has a track: nothing of ours belongs there. */
        data object Idle : Target
    }

    /**
     * Picks the playback owner across *all* sessions ([sessions] in the system's priority order):
     * the first one that is playing or buffering, else the first paused one. A source package
     * becomes [Target.Show]; any other playing app is [Target.OtherPlaying], so a paused Yandex
     * session never outranks the stock player or Bluetooth that is actually playing. A paused
     * non-source owner, a stopped source or an untitled one is [Target.Idle].
     */
    fun decide(sessions: List<SessionSnapshot>, sources: List<String> = SOURCE_PACKAGES): Target {
        val playing = sessions.firstOrNull { it.playbackState == PB_PLAYING || it.playbackState == PB_BUFFERING }
        if (playing != null && playing.packageName !in sources) return Target.OtherPlaying(playing.packageName)
        val owner = playing ?: sessions.firstOrNull { it.playbackState == PB_PAUSED }
        if (owner == null || owner.packageName !in sources || owner.title.isNullOrBlank()) return Target.Idle
        val duration = owner.durationMs?.takeIf { it > 0 }
        return Target.Show(
            Card(
                title = owner.title.trim(),
                artist = owner.artist?.trim().orEmpty(),
                musicState = if (owner === playing) MUSIC_PLAYING else MUSIC_PAUSED,
                progress = progressPercent(owner.positionMs, duration),
                positionSec = owner.positionMs?.let { (it.coerceIn(0, duration ?: it) / 1000).toInt() },
                durationSec = duration?.let { (it / 1000).toInt() },
            )
        )
    }

    /** The bar value as the stock OtherClient rounds it: position / duration * 100, clamped. */
    fun progressPercent(positionMs: Long?, durationMs: Long?): Int? {
        if (positionMs == null || durationMs == null || durationMs <= 0) return null
        return ((positionMs.coerceIn(0, durationMs) * 100.0 / durationMs) + 0.5).toInt()
    }

    /** Hours, minutes, seconds, as the stock sendAudioTime splits them. */
    fun hms(totalSec: Int): Triple<Int, Int, Int> = Triple(totalSec / 3600, totalSec / 60 % 60, totalSec % 60)

    /**
     * UTF-16LE without a BOM, cut to [MAX_TEXT_BYTES] without splitting a surrogate pair.
     * The stock sender turns an empty string into a single space; so does this.
     */
    fun encode(text: String): ByteArray {
        val src = text.ifEmpty { " " }
        var chars = minOf(src.length, MAX_TEXT_BYTES / 2)
        if (chars < src.length && chars > 0 && Character.isHighSurrogate(src[chars - 1])) chars--
        return src.substring(0, chars).toByteArray(Charsets.UTF_16LE)
    }
}
