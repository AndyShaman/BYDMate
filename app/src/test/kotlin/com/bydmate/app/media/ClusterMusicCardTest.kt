package com.bydmate.app.media

import com.bydmate.app.media.ClusterMusicCard.Card
import com.bydmate.app.media.ClusterMusicCard.SessionSnapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClusterMusicCardTest {

    // PlaybackState.STATE_* values as literals — the pure logic is JVM-tested.
    private val stopped = 1
    private val paused = 2
    private val playing = 3
    private val navi = "ru.yandex.yandexnavi"

    @Test fun `no sessions means no card`() {
        assertNull(ClusterMusicCard.pick(emptyList()))
    }

    @Test fun `a playing navigator session becomes a playing card`() {
        val card = ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, " Кино ", "Цой")))
        assertEquals(Card("Кино", "Цой", ClusterMusicCard.MUSIC_PLAYING), card)
    }

    @Test fun `whitelisted players are left to the stock controller`() {
        assertNull(ClusterMusicCard.pick(listOf(SessionSnapshot("com.byd.mediacenter", playing, "Song", "Artist"))))
    }

    @Test fun `playing source wins over an earlier paused one`() {
        val card = ClusterMusicCard.pick(
            listOf(
                SessionSnapshot("ru.yandex.music", paused, "Old", "A"),
                SessionSnapshot(navi, playing, "New", "B"),
            )
        )
        assertEquals("New", card?.title)
    }

    @Test fun `paused session keeps its title with paused state`() {
        val card = ClusterMusicCard.pick(listOf(SessionSnapshot(navi, paused, "Song", null)))
        assertEquals(Card("Song", "", ClusterMusicCard.MUSIC_PAUSED), card)
    }

    @Test fun `stopped or untitled sessions produce nothing`() {
        assertNull(ClusterMusicCard.pick(listOf(SessionSnapshot(navi, stopped, "Song", "A"))))
        assertNull(ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, "  ", "A"))))
        assertNull(ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, null, "A"))))
    }

    @Test fun `encode is utf-16le without bom`() {
        assertArrayEquals(byteArrayOf(0x41, 0, 0x2F, 0x04), ClusterMusicCard.encode("AЯ"))
    }

    @Test fun `empty text is sent as a single space`() {
        assertArrayEquals(byteArrayOf(0x20, 0), ClusterMusicCard.encode(""))
    }

    @Test fun `long text is cut to the byte cap on a whole unit`() {
        val bytes = ClusterMusicCard.encode("x".repeat(500))
        assertEquals(ClusterMusicCard.MAX_TEXT_BYTES, bytes.size)
    }

    @Test fun `cut never splits a surrogate pair`() {
        val text = "x".repeat(ClusterMusicCard.MAX_TEXT_BYTES / 2 - 1) + "🎵" + "tail"
        val decoded = String(ClusterMusicCard.encode(text), Charsets.UTF_16LE)
        assertEquals("x".repeat(ClusterMusicCard.MAX_TEXT_BYTES / 2 - 1), decoded)
    }

    @Test fun `progress is position over duration rounded like the stock sender`() {
        assertEquals(50, ClusterMusicCard.progressPercent(90_000, 180_000))
        assertEquals(1, ClusterMusicCard.progressPercent(1_000, 180_000))
        assertEquals(100, ClusterMusicCard.progressPercent(200_000, 180_000))
        assertEquals(0, ClusterMusicCard.progressPercent(-5, 180_000))
    }

    @Test fun `no duration means no progress`() {
        assertNull(ClusterMusicCard.progressPercent(90_000, null))
        assertNull(ClusterMusicCard.progressPercent(90_000, 0))
        assertNull(ClusterMusicCard.progressPercent(null, 180_000))
    }

    @Test fun `card carries progress from the chosen session`() {
        val card = ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, "Song", "A", 45_000, 180_000)))
        assertEquals(25, card?.progress)
    }

    @Test fun `card carries played and total seconds`() {
        val card = ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, "Song", "A", 95_400, 214_000)))
        assertEquals(95, card?.positionSec)
        assertEquals(214, card?.durationSec)
    }

    @Test fun `hms splits like the stock sender`() {
        assertEquals(Triple(0, 3, 34), ClusterMusicCard.hms(214))
        assertEquals(Triple(1, 0, 5), ClusterMusicCard.hms(3605))
    }

    @Test fun `ticking position does not count as a new track`() {
        val a = ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, "Song", "A", 10_000, 214_000)))!!
        val b = ClusterMusicCard.pick(listOf(SessionSnapshot(navi, playing, "Song", "A", 12_000, 214_000)))!!
        assertEquals(a.steady(), b.steady())
    }
}
