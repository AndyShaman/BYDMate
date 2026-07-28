package com.bydmate.app.ui.radio

import android.content.Context
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.media.RadioPreset
import com.bydmate.app.media.RadioPresets
import com.bydmate.app.media.RadioTrack

/**
 * Maps stored stations onto the playlist the player understands.
 *
 * Built-in stations point at their bundled logo through an `android.resource://` URI so the media
 * notification shows real artwork with no network round trip; user stations pass their own icon
 * URL or content:// URI straight through.
 *
 * With [dataSaver] on, a station plays from its lighter mount where one is known — from
 * [RadioPresets] for the built-ins, from the station's own row for anything added from a
 * directory that publishes bitrate tiers.
 * The swap is deliberately not written to the database: the row keeps its normal URL, so turning
 * the setting back off restores full bitrate with nothing to undo.
 */
fun List<RadioStationEntity>.toTracks(
    context: Context,
    dataSaver: Boolean = false
): List<RadioTrack> =
    map { station ->
        val preset = RadioPresets.fromIconRef(station.iconUrl)?.takeIf { it.url == station.url }
        RadioTrack(
            id = station.id,
            name = station.name,
            url = preset?.streamUrl(dataSaver) ?: station.streamUrl(dataSaver),
            artworkUri = station.artworkUri(context),
            iconRef = station.iconUrl
        )
    }

/**
 * A station the user re-pointed at another stream is theirs — matching only on the icon reference
 * would silently drag their URL back to ours, so [toTracks] also requires the URL to be untouched
 * before it substitutes anything.
 */
private fun RadioStationEntity.streamUrl(dataSaver: Boolean): String =
    if (dataSaver) lowBitrateUrl?.takeIf { it.isNotBlank() } ?: url else url

private fun RadioPreset.streamUrl(dataSaver: Boolean): String =
    if (dataSaver) lowBitrateUrl ?: url else url

private fun RadioStationEntity.artworkUri(context: Context): String? {
    val preset = RadioPresets.fromIconRef(iconUrl)
    if (preset != null) {
        val logo = preset.logoRes ?: return null
        return "android.resource://${context.packageName}/$logo"
    }
    // A preset reference we no longer know about is not a loadable image either.
    return iconUrl?.takeIf { it.isNotBlank() && !RadioPresets.isIconRef(it) }
}
