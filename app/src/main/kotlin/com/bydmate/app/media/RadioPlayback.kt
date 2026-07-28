package com.bydmate.app.media

/** One entry of the playlist handed to the player. [artworkUri] feeds the media notification. */
data class RadioTrack(
    val id: Long,
    val name: String,
    val url: String,
    val artworkUri: String? = null,
    /** Raw `radio_stations.icon_url`, carried through the session so surfaces that render
     *  their own tiles (the floating widget) can resolve the same logo the Radio tab shows. */
    val iconRef: String? = null
)

enum class RadioStatus { IDLE, BUFFERING, PLAYING, PAUSED, ERROR }

/** What the Radio tab and the dashboard strip render; published by [RadioController.state]. */
data class RadioPlayback(
    val stationId: Long = 0L,
    val stationName: String = "",
    val status: RadioStatus = RadioStatus.IDLE,
    val errorMessage: String? = null,
    /** Icon reference of the station on air, for surfaces that draw their own tile. */
    val stationIcon: String? = null
) {
    /** True while this station occupies the player (buffering, playing or paused). */
    val isActive: Boolean
        get() = status == RadioStatus.BUFFERING ||
            status == RadioStatus.PLAYING ||
            status == RadioStatus.PAUSED
}
