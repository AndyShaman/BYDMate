package com.bydmate.app.media

import com.bydmate.app.media.ClusterMusicCard.Card
import com.bydmate.app.media.ClusterMusicCard.SessionSnapshot
import com.bydmate.app.media.ClusterMusicCard.Target
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

    private fun shown(sessions: List<SessionSnapshot>): Card? = (ClusterMusicCard.decide(sessions) as? Target.Show)?.card

    @Test fun `no sessions means idle`() {
        assertEquals(Target.Idle, ClusterMusicCard.decide(emptyList()))
    }

    @Test fun `a playing navigator session becomes a playing card`() {
        val card = shown(listOf(SessionSnapshot(navi, playing, " Кино ", "Цой")))
        assertEquals(Card("Кино", "Цой", ClusterMusicCard.MUSIC_PLAYING), card)
    }

    @Test fun `a playing whitelisted player is handed off to the stock controller`() {
        assertEquals(
            Target.OtherPlaying("com.byd.mediacenter"),
            ClusterMusicCard.decide(listOf(SessionSnapshot("com.byd.mediacenter", playing, "Song", "Artist"))),
        )
    }

    // Review point 2: a paused Yandex session must not outrank a player that is actually playing.
    @Test fun `paused yandex loses to the stock player that is playing`() {
        val target = ClusterMusicCard.decide(
            listOf(
                SessionSnapshot(navi, paused, "Old", "A"),
                SessionSnapshot("com.byd.mediacenter", playing, "Song", "B"),
            )
        )
        assertEquals(Target.OtherPlaying("com.byd.mediacenter"), target)
    }

    @Test fun `paused yandex loses to bluetooth that is playing`() {
        val target = ClusterMusicCard.decide(
            listOf(SessionSnapshot(navi, paused, "Old", "A"), SessionSnapshot("com.android.bluetooth", playing, null, null))
        )
        assertEquals(Target.OtherPlaying("com.android.bluetooth"), target)
    }

    @Test fun `playing yandex wins over an earlier paused stock session`() {
        val card = shown(
            listOf(
                SessionSnapshot("com.byd.mediacenter", paused, "No songs", "No artists"),
                SessionSnapshot(navi, playing, "New", "B"),
            )
        )
        assertEquals("New", card?.title)
    }

    @Test fun `paused yandex on top keeps its title with paused state`() {
        val card = shown(
            listOf(SessionSnapshot(navi, paused, "Song", null), SessionSnapshot("com.byd.mediacenter", paused, "No songs", null))
        )
        assertEquals(Card("Song", "", ClusterMusicCard.MUSIC_PAUSED), card)
    }

    @Test fun `a paused stock session on top with nothing playing is idle, not a hand-off`() {
        val target = ClusterMusicCard.decide(
            listOf(SessionSnapshot("com.byd.mediacenter", paused, "No songs", null), SessionSnapshot(navi, stopped, "Song", "A"))
        )
        assertEquals(Target.Idle, target)
    }

    // Whoever plays owns the card: a Navigator voice prompt takes audio focus, but Yandex Music's
    // session keeps playing, so its card stays.
    @Test fun `a navigator voice prompt over yandex music keeps the music card`() {
        val card = shown(
            listOf(SessionSnapshot(navi, null, null, null), SessionSnapshot("ru.yandex.music", playing, "Song", "A"))
        )
        assertEquals(Card("Song", "A", ClusterMusicCard.MUSIC_PLAYING), card)
    }

    @Test fun `a paused old session of another app does not replace the playing one`() {
        val card = shown(
            listOf(SessionSnapshot(navi, paused, "Old", "B"), SessionSnapshot("ru.yandex.music", playing, "Song", "A"))
        )
        assertEquals(Card("Song", "A", ClusterMusicCard.MUSIC_PLAYING), card)
    }

    @Test fun `the first playing session in priority order owns the card`() {
        val music = SessionSnapshot("ru.yandex.music", playing, "Song", "A")
        val stock = SessionSnapshot("com.byd.mediacenter", playing, "Other", "B")
        assertEquals("Song", shown(listOf(music, stock))?.title)
        assertEquals(Target.OtherPlaying("com.byd.mediacenter"), ClusterMusicCard.decide(listOf(stock, music)))
    }

    @Test fun `the sessions alone decide the owner`() {
        val sessions = listOf(SessionSnapshot(navi, paused, "Old", "A"), SessionSnapshot("com.byd.mediacenter", playing, "Song", "B"))
        assertEquals(Target.OtherPlaying("com.byd.mediacenter"), ClusterMusicCard.decide(sessions))
    }

    @Test fun `stopped or untitled sessions are idle`() {
        assertEquals(Target.Idle, ClusterMusicCard.decide(listOf(SessionSnapshot(navi, stopped, "Song", "A"))))
        assertEquals(Target.Idle, ClusterMusicCard.decide(listOf(SessionSnapshot(navi, playing, "  ", "A"))))
        assertEquals(Target.Idle, ClusterMusicCard.decide(listOf(SessionSnapshot(navi, playing, null, "A"))))
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

    @Test fun `card carries progress from the owner`() {
        val card = shown(listOf(SessionSnapshot(navi, playing, "Song", "A", 95_400, 214_000)))
        assertEquals(45, card?.progress)
    }

    @Test fun `ticking progress does not count as a new track`() {
        val a = shown(listOf(SessionSnapshot(navi, playing, "Song", "A", 10_000, 214_000)))!!
        val b = shown(listOf(SessionSnapshot(navi, playing, "Song", "A", 12_000, 214_000)))!!
        assertEquals(a.steady(), b.steady())
    }
}
