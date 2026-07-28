package com.bydmate.app.ui.radio

import android.content.Context
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.media.RadioPresets
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The data-saver substitution rules — the part that can quietly hijack a user's own stream. */
class RadioTracksTest {

    private val context = mockk<Context>(relaxed = true).also {
        every { it.packageName } returns "com.bydmate.app"
    }

    private fun row(key: String, url: String? = null): RadioStationEntity {
        val preset = RadioPresets.ALL.first { it.key == key }
        return RadioStationEntity(
            id = 1,
            name = preset.name,
            url = url ?: preset.url,
            iconUrl = RadioPresets.iconRef(preset.key)
        )
    }

    @Test
    fun `data saver swaps a built-in station to its lighter mount`() {
        val record = RadioPresets.ALL.first { it.key == "record" }

        val track = listOf(row("record")).toTracks(context, dataSaver = true).single()

        assertEquals(record.lowBitrateUrl, track.url)
    }

    @Test
    fun `data saver off keeps the normal stream`() {
        val record = RadioPresets.ALL.first { it.key == "record" }

        val track = listOf(row("record")).toTracks(context, dataSaver = false).single()

        assertEquals(record.url, track.url)
    }

    @Test
    fun `a station with no lighter mount is left at its normal bitrate`() {
        val nashe = RadioPresets.ALL.first { it.key == "nashe" }
        assertEquals(null, nashe.lowBitrateUrl)

        val track = listOf(row("nashe")).toTracks(context, dataSaver = true).single()

        assertEquals(nashe.url, track.url)
    }

    @Test
    fun `a built-in station the user re-pointed keeps their stream`() {
        val mine = "https://example.org/my-own-stream.mp3"

        val track = listOf(row("record", url = mine)).toTracks(context, dataSaver = true).single()

        assertEquals(mine, track.url)
    }

    @Test
    fun `a hand-added station is never touched`() {
        val station = RadioStationEntity(id = 5, name = "Моя", url = "https://example.org/s.mp3")

        val track = listOf(station).toTracks(context, dataSaver = true).single()

        assertEquals(station.url, track.url)
        assertEquals(null, track.artworkUri)
    }

    @Test
    fun `a directory station uses its own lighter stream under data saver`() {
        val station = RadioStationEntity(
            id = 12,
            name = "Record Chill",
            url = "https://r/chill96.aacp",
            lowBitrateUrl = "https://r/chill32.aacp",
        )

        val saving = listOf(station).toTracks(context, dataSaver = true).single()
        val full = listOf(station).toTracks(context, dataSaver = false).single()

        assertEquals("https://r/chill32.aacp", saving.url)
        assertEquals("https://r/chill96.aacp", full.url)
    }

    @Test
    fun `a hand-typed station with no lighter stream is unaffected by data saver`() {
        val station = RadioStationEntity(id = 13, name = "Мой поток", url = "https://x/s.mp3")

        val track = listOf(station).toTracks(context, dataSaver = true).single()

        assertEquals("https://x/s.mp3", track.url)
    }

    @Test
    fun `lighter mounts are well formed`() {
        val lighter = RadioPresets.ALL.mapNotNull { preset -> preset.lowBitrateUrl?.let { preset to it } }
        assertTrue(lighter.isNotEmpty())
        assertTrue(lighter.all { (_, url) -> url.startsWith("https://") })
        // A "lighter" mount that is the same stream would make the setting a lie.
        assertTrue(lighter.none { (preset, url) -> url == preset.url })
    }
}
