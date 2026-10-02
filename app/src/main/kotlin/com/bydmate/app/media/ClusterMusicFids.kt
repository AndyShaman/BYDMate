package com.bydmate.app.media

import com.bydmate.app.data.nativestack.FidCatalog

/**
 * The card's write addresses, resolved from the running firmware's own catalog: the SDK derives
 * them per platform (INSTRUMENT_MUSIC_SOURCE_SET is 871366704 on CAN-FD units and 1138753584 on
 * DiLink 3/4), and some units lack a symbol altogether (the singer fid is absent on Song Plus).
 *
 * [resolve] returns null unless the catalog has every required symbol and the instrument device;
 * the feature then stays off instead of writing to a guessed address. The optional fids (singer,
 * progress) are skipped one by one when absent. Played / total time are not sent: the card
 * doesn't show them, and they were most of the write volume.
 */
data class ClusterMusicFids(
    val instrumentDev: Int,
    val info: Int,
    val state: Int,
    val source: Int,
    val progress: Int?,
    val audioDev: Int?,
    val singer: Int?,
) {
    companion object {
        const val INFO = "Instrument.INSTRUMENT_MUSIC_INFO_SET"
        const val STATE = "Instrument.INSTRUMENT_MUSIC_STATE_SET"
        const val SOURCE = "Instrument.INSTRUMENT_MUSIC_SOURCE_SET"
        const val PROGRESS = "Instrument.INSTRUMENT_MUSIC_PLAYBACK_PROGRESS_SET"
        const val SINGER = "Audio.AUDIO_ARMREST_SCREEN_SINGER_NAME_SET"
        const val DEVICE_INSTRUMENT = "INSTRUMENT"
        const val DEVICE_AUDIO = "AUDIO"

        /** Every symbol the card may use, for the catalog cache (FidCatalogManager keeps only wanted ones). */
        val SYMBOLS: List<String> = listOf(INFO, STATE, SOURCE, PROGRESS, SINGER)

        /** Null when the catalog is missing or lacks a required symbol or the instrument device. */
        fun resolve(catalog: FidCatalog?): ClusterMusicFids? {
            if (catalog == null) return null
            val instrument = catalog.deviceOf(DEVICE_INSTRUMENT) ?: return null
            val info = catalog.fidOf(INFO) ?: return null
            val state = catalog.fidOf(STATE) ?: return null
            val source = catalog.fidOf(SOURCE) ?: return null
            val audio = catalog.deviceOf(DEVICE_AUDIO)
            return ClusterMusicFids(
                instrumentDev = instrument,
                info = info,
                state = state,
                source = source,
                progress = catalog.fidOf(PROGRESS),
                audioDev = audio,
                singer = if (audio != null) catalog.fidOf(SINGER) else null,
            )
        }

        /** Names of the required symbols the catalog lacks, for the log line when [resolve] is null. */
        fun missing(catalog: FidCatalog?): List<String> {
            if (catalog == null) return listOf("catalog")
            val names = listOf(INFO, STATE, SOURCE).filter { catalog.fidOf(it) == null }
            return if (catalog.deviceOf(DEVICE_INSTRUMENT) == null) names + "device $DEVICE_INSTRUMENT" else names
        }
    }
}
