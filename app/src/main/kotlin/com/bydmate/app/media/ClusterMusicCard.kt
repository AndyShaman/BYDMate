package com.bydmate.app.media

/**
 * Pure half of [ClusterMusicBridge]: who owns playback right now, what the card should say, and
 * how the text goes on the wire. JVM-tested; nothing here touches Android.
 *
 * Background: the stock `com.byd.mediacontroller` fills the card (INSTRUMENT_MUSIC_INFO_SET and
 * friends) only for a hard-coded whitelist of Chinese players, BYD's own player and Bluetooth.
 * Any other audio-focus owner gets `sendBlackThirdPartyAppMediaInfo()`: source 26, state 2 and a
 * blank name. Yandex Navigator's in-app music (Alice), Yandex Music, Spotify, internet radio and
 * every other app outside that list leave the card empty while they play, so any of them is a source.
 */
object ClusterMusicCard {

    /**
     * Packages the stock controller fills the card for itself (`MediaTaskManager.mWhiteListPackageNames`
     * in DiLink 5.0's MediaController) and the ones it ignores as focus noise (calls, Bluetooth, the
     * system). These are never mirrored: the stock card is already right for them.
     */
    val STOCK_PACKAGES = setOf(
        "com.byd.mediacenter", "com.byd.videoplay", "com.byd.videoplay.fse", "com.byd.videoplay.hd",
        "com.byd.videoplay.youku", "com.byd.videoplay.youku.fse", "com.byd.synclink",
        "com.tencent.qqmusiccar", "com.tencent.qqmusic", "com.tencent.qqmusictv",
        "com.netease.cloudmusic.iot", "com.netease.cloudmusic.tv",
        "com.ximalaya.ting.android", "com.ximalaya.ting.android.car.byd", "bubei.tingshu.hd",
        "com.kugou.android", "com.kugou.android.auto", "cn.kuwo.kwmusiccar", "cn.kuwo.player",
        "cn.wenyu.bodian", "app.podcast.cosmos", "cmgyunting.vehicleplayer.cnr", "com.huawei.dmsdpdevice",
        "android", "com.android.bluetooth", "com.android.server.telecom",
    )

    /** An app whose MediaSession is mirrored onto the card: anything the stock controller leaves blank. */
    fun isSource(packageName: String): Boolean =
        packageName !in STOCK_PACKAGES && !packageName.startsWith("com.byd.")

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

    /** [progress] is the 0..100 bar value, null when the session doesn't give a duration. */
    data class Card(
        val title: String,
        val artist: String,
        val musicState: Int,
        val progress: Int? = null,
    ) {
        /** The part that only changes with the track or play state; the bar ticks. */
        fun steady(): Card = copy(progress = null)
    }

    /** What the bridge should do with the card this tick. */
    sealed interface Target {
        /** A source package owns playback: the card should say this. */
        data class Show(val card: Card, val packageName: String = "") : Target

        /** Another app is playing: the stock controller owns the card, hands off. */
        data class OtherPlaying(val packageName: String) : Target

        /** Nobody is playing and no source session has a track: nothing of ours belongs there. */
        data object Idle : Target
    }

    /**
     * Picks the playback owner across *all* sessions ([sessions] in the system's priority order):
     * the first one that is playing or buffering. A source package becomes [Target.Show]; any other
     * playing app is [Target.OtherPlaying], so a paused source session never outranks the stock
     * player or Bluetooth that is actually playing.
     *
     * With nobody playing, [lastOwner] (the package the previous poll picked) keeps the card while
     * its session is still there, whatever state it reports: internet radio drops out of "playing"
     * for a moment while it rebuffers or a voice prompt ducks it, and handing the card to an older
     * paused session then would flip it back and forth. Without such an owner the first paused
     * session decides. A non-source owner, a stopped source or an untitled one is [Target.Idle].
     */
    fun decide(
        sessions: List<SessionSnapshot>,
        lastOwner: String? = null,
        isSource: (String) -> Boolean = ::isSource,
    ): Target {
        val playing = sessions.firstOrNull { it.playbackState == PB_PLAYING || it.playbackState == PB_BUFFERING }
        if (playing != null && !isSource(playing.packageName)) return Target.OtherPlaying(playing.packageName)
        val sticky = if (playing == null && lastOwner != null) {
            sessions.firstOrNull { it.packageName == lastOwner && !it.title.isNullOrBlank() }
        } else {
            null
        }
        val owner = playing ?: sticky ?: sessions.firstOrNull { it.playbackState == PB_PAUSED }
        if (owner == null || !isSource(owner.packageName) || owner.title.isNullOrBlank()) return Target.Idle
        return Target.Show(
            Card(
                title = owner.title.trim(),
                artist = owner.artist?.trim().orEmpty(),
                musicState = if (owner === playing) MUSIC_PLAYING else MUSIC_PAUSED,
                progress = progressPercent(owner.positionMs, owner.durationMs),
            ),
            owner.packageName,
        )
    }

    /** The bar value as the stock OtherClient rounds it: position / duration * 100, clamped. */
    fun progressPercent(positionMs: Long?, durationMs: Long?): Int? {
        if (positionMs == null || durationMs == null || durationMs <= 0) return null
        return ((positionMs.coerceIn(0, durationMs) * 100.0 / durationMs) + 0.5).toInt()
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
