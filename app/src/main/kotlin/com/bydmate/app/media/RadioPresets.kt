package com.bydmate.app.media

import androidx.annotation.DrawableRes
import com.bydmate.app.R

/**
 * The built-in Russian-language stations seeded into an empty station list on first open of the
 * Radio tab. They are ordinary rows in `radio_stations` afterwards — the user can rename, re-point
 * or delete any of them, and deleted ones never come back (see
 * [com.bydmate.app.data.repository.RadioRepository.seedPresetsIfNeeded]).
 *
 * Station artwork is bundled in the APK ([logoRes]) rather than hot-linked, so a tile renders
 * identically with no connectivity — the normal state in a garage or a tunnel. Stations whose
 * official logo is not available as a usable image fall back to a [colorArgb] + [monogram] tile.
 * Pasting a logo URL in the edit dialog overrides either.
 */
data class RadioPreset(
    val key: String,
    val name: String,
    val url: String,
    val colorArgb: Long,
    val monogram: String,
    @DrawableRes val logoRes: Int? = null,
    /**
     * Lighter mount used while "Экономить трафик" is on, or null where the station publishes no
     * second stream — those keep playing at their normal bitrate and the setting is a no-op for
     * them. Preferred at 64–96 kbps HE-AAC, falling back to the lowest MP3 on offer.
     */
    val lowBitrateUrl: String? = null
)

object RadioPresets {

    /** Icon reference stored in `radio_stations.icon_url` for a preset station. */
    private const val ICON_SCHEME = "bydmate://preset/"

    /**
     * Stream URL of the built-in Русское Радио that shipped in 3.8.1 and was dropped in 3.9.
     *
     * It is an HE-AAC (`.aacp`) mount, which the previous MediaPlayer-based service could not
     * decode on DiLink — the station appeared in the list but never played. Kept here only so
     * [com.bydmate.app.data.repository.RadioRepository] can retire the row on existing installs.
     */
    const val RETIRED_RUSRADIO_URL = "https://rusradio.hostingradio.ru/rusradio96.aacp"

    /**
     * Ordered as the Dashboard prev/next buttons cycle them, most-listened first.
     *
     * Streams are the stations' own public mounts, verified to answer with an audio content type.
     * Bitrates are kept at 128–192 kbps: a head unit usually rides a phone hotspot, and 320 kbps
     * buys nothing over car speakers.
     */
    val ALL: List<RadioPreset> = listOf(
        RadioPreset(
            key = "europaplus",
            name = "Европа Плюс",
            url = "https://ep256.hostingradio.ru:8052/europaplus256.mp3",
            colorArgb = 0xFFE53935,
            monogram = "Е+",
            logoRes = R.drawable.radio_logo_europaplus,
            lowBitrateUrl = "https://ep128.hostingradio.ru:8030/ep128"
        ),
        RadioPreset(
            key = "loveradio",
            name = "Love Radio",
            url = "https://stream2.n340.com/12_love_192",
            colorArgb = 0xFFD81B60,
            monogram = "LR",
            logoRes = R.drawable.radio_logo_loveradio,
            lowBitrateUrl = "https://stream2.n340.com/12_love_64"
        ),
        RadioPreset(
            key = "hitfm",
            name = "Хит FM",
            url = "https://hitfm.hostingradio.ru/hitfm128.mp3",
            colorArgb = 0xFF283593,
            monogram = "ХИТ",
            logoRes = R.drawable.radio_logo_hitfm,
            lowBitrateUrl = "https://hitfm.hostingradio.ru/hitfm96.aacp"
        ),
        RadioPreset(
            key = "record",
            name = "Radio Record",
            url = "https://radiorecord.hostingradio.ru/rr_main96.aacp",
            colorArgb = 0xFF212121,
            monogram = "RR",
            logoRes = R.drawable.radio_logo_record,
            lowBitrateUrl = "https://radiorecord.hostingradio.ru/rr_main64.aacp"
        ),
        RadioPreset(
            key = "retrofm",
            name = "Ретро FM",
            url = "https://retroserver.streamr.ru:8043/retro256.mp3",
            colorArgb = 0xFFEF6C00,
            monogram = "РЕ"
        ),
        RadioPreset(
            key = "dorognoe",
            name = "Дорожное радио",
            url = "https://dorognoe.hostingradio.ru:8000/dorognoe",
            colorArgb = 0xFF2E7D32,
            monogram = "ДР"
        ),
        RadioPreset(
            key = "chanson",
            name = "Радио Шансон",
            url = "https://chanson.hostingradio.ru:8041/chanson256.mp3",
            colorArgb = 0xFF6A1B9A,
            monogram = "ШН",
            lowBitrateUrl = "https://chanson.hostingradio.ru:8041/chanson96.aacp"
        ),
        RadioPreset(
            key = "nashe",
            name = "Наше Радио",
            url = "https://nashe1.hostingradio.ru/nashe-256",
            colorArgb = 0xFF00838F,
            monogram = "НР",
            logoRes = R.drawable.radio_logo_nashe
        ),
    )

    fun iconRef(key: String): String = ICON_SCHEME + key

    fun isIconRef(value: String): Boolean = value.startsWith(ICON_SCHEME)

    /**
     * Resolves an `icon_url` back to its preset, or null for user icons and for preset keys that
     * no longer exist (an older DB row after a preset was dropped — falls back to the letter tile).
     */
    fun fromIconRef(value: String?): RadioPreset? {
        val text = value?.trim().orEmpty()
        if (!text.startsWith(ICON_SCHEME)) return null
        val key = text.removePrefix(ICON_SCHEME)
        return ALL.firstOrNull { it.key == key }
    }
}
