package com.bydmate.app.media

/**
 * Pure half of [ClusterMusicBridge]: which session feeds the instrument's music card, what the
 * card should say, and how the text goes on the wire. JVM-tested; nothing here touches Android.
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
    )

    data class Card(val title: String, val artist: String, val musicState: Int)

    /**
     * The card for [sessions] (system priority order), or null when no source session has a
     * track to show. A playing source session wins over a paused one; stopped or untitled
     * sessions never produce a card.
     */
    fun pick(sessions: List<SessionSnapshot>, sources: List<String> = SOURCE_PACKAGES): Card? {
        val candidates = sessions.filter { it.packageName in sources && !it.title.isNullOrBlank() }
        val playing = candidates.firstOrNull { it.playbackState == PB_PLAYING || it.playbackState == PB_BUFFERING }
        val chosen = playing ?: candidates.firstOrNull { it.playbackState == PB_PAUSED } ?: return null
        val state = if (chosen === playing) MUSIC_PLAYING else MUSIC_PAUSED
        return Card(chosen.title!!.trim(), chosen.artist?.trim().orEmpty(), state)
    }

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
