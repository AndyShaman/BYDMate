package com.bydmate.app.media

import com.bydmate.app.media.ClusterMusicCard.Card
import com.bydmate.app.media.ClusterMusicCard.Target
import com.bydmate.app.media.ClusterMusicSync.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClusterMusicSyncTest {

    private val fids = ClusterMusicFids(
        instrumentDev = 1007, info = 11, state = 12, source = 13, progress = 14,
        audioDev = 1002, singer = 21, playTime = listOf(31, 32, 33), totalTime = listOf(41, 42, 43),
    )

    /** Records every write; [status] decides each reply (null = helper down). */
    private class FakePort(var status: (fid: Int) -> Int? = { 1 }) : ClusterMusicSync.Port {
        val writes = mutableListOf<Pair<Int, Any>>()
        override suspend fun writeInt(dev: Int, fid: Int, value: Int): Int? {
            writes += fid to value
            return status(fid)
        }
        override suspend fun writeBuffer(dev: Int, fid: Int, bytes: ByteArray): Int? {
            writes += fid to String(bytes, Charsets.UTF_16LE)
            return status(fid)
        }
        fun valuesFor(fid: Int) = writes.filter { it.first == fid }.map { it.second }
        fun clearTakes() = writes.clear()
    }

    private fun card(title: String = "Song", progress: Int? = 10, pos: Int? = 20, dur: Int? = 200) =
        Card(title, "Artist", ClusterMusicCard.MUSIC_PLAYING, progress, pos, dur)

    @Test fun `a shown card writes source, state, title and singer`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        assertEquals(Outcome.SHOWN, sync.step(true, fids, Target.Show(card()), 0))
        assertEquals(listOf(26), port.valuesFor(13))
        assertEquals(listOf("Song"), port.valuesFor(11))
        assertEquals(listOf("Artist"), port.valuesFor(21))
        assertEquals(card(), sync.shown)
    }

    // Review point 1: no resolved fids (unsupported firmware) means nothing is written at all.
    @Test fun `no fids means no writes`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        assertEquals(Outcome.NONE, sync.step(true, null, Target.Show(card()), 0))
        assertTrue(port.writes.isEmpty())
    }

    // Review point 2: another player takes over, ours is dropped without wiping what the stock app wrote.
    @Test fun `hand-off to another player forgets the card without clearing it`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.HANDED_OFF, sync.step(true, fids, Target.OtherPlaying("com.byd.mediacenter"), 1_500))
        assertTrue(port.writes.isEmpty())
        assertFalse(sync.dirty)
        assertEquals(Outcome.NONE, sync.step(true, fids, Target.OtherPlaying("com.byd.mediacenter"), 3_000))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `idle after our card clears it`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.CLEARED, sync.step(true, fids, Target.Idle, 1_500))
        assertEquals(listOf(ClusterMusicCard.MUSIC_STOPPED), port.valuesFor(12))
        assertEquals(listOf(" "), port.valuesFor(11))
        assertFalse(sync.dirty)
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.step(true, fids, Target.Idle, 3_000))
        assertTrue(port.writes.isEmpty())
    }

    // Review point 3: a failed write is not recorded as shown.
    @Test fun `a failed required write leaves nothing confirmed and retries next step`() = runTest {
        val port = FakePort { fid -> if (fid == 11) -1 else 1 }
        val sync = ClusterMusicSync(port)
        assertEquals(Outcome.WRITE_FAILED, sync.step(true, fids, Target.Show(card()), 0))
        assertNull(sync.shown)
        assertTrue(sync.dirty)
        port.status = { 1 }
        assertEquals(Outcome.SHOWN, sync.step(true, fids, Target.Show(card()), 1_500))
        assertEquals(card(), sync.shown)
    }

    @Test fun `helper down counts as a failure`() = runTest {
        val sync = ClusterMusicSync(FakePort { null })
        assertEquals(Outcome.WRITE_FAILED, sync.step(true, fids, Target.Show(card()), 0))
        assertNull(sync.shown)
    }

    // Review point 3: the switch goes off while the helper is down; the clear stays owed and is retried.
    @Test fun `a failed clear is retried until it lands`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.status = { null }
        assertEquals(Outcome.CLEAR_FAILED, sync.step(false, fids, Target.Idle, 1_500))
        assertTrue(sync.dirty)
        assertEquals(Outcome.CLEAR_FAILED, sync.step(false, fids, Target.Idle, 3_000))
        port.status = { 1 }
        assertEquals(Outcome.CLEARED, sync.step(false, fids, Target.Idle, 4_500))
        assertFalse(sync.dirty)
    }

    @Test fun `a clear that never lands gives up after the budget`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.status = { -1 }
        var last = Outcome.NONE
        repeat(ClusterMusicSync.MAX_CLEAR_ATTEMPTS) { last = sync.step(false, fids, Target.Idle, it * 1_500L) }
        assertEquals(Outcome.CLEAR_GAVE_UP, last)
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.step(false, fids, Target.Idle, 100_000))
        assertTrue(port.writes.isEmpty())
    }

    // Review point 4: switching off mid-card stops the rest of that card and leaves a clear owed.
    @Test fun `switch off between writes aborts the card`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        var checks = 0
        val outcome = sync.step(true, fids, Target.Show(card()), 0) { ++checks <= 1 }
        assertEquals(Outcome.ABORTED, outcome)
        assertEquals(listOf(26), port.valuesFor(13))
        assertTrue(port.valuesFor(11).isEmpty())
        assertTrue(sync.dirty)
        assertNull(sync.shown)
    }

    // Review point 4: the stop path's last clear runs even after the retry budget was spent.
    @Test fun `release clears whatever of ours may be there`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.CLEARED, sync.release(fids))
        assertEquals(listOf(" "), port.valuesFor(11))
        assertEquals(Outcome.NONE, sync.release(fids))
    }

    @Test fun `release after an aborted card still clears`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0) { false }
        assertEquals(Outcome.CLEARED, sync.release(fids))
    }

    @Test fun `release with nothing of ours writes nothing`() = runTest {
        val port = FakePort()
        assertEquals(Outcome.NONE, ClusterMusicSync(port).release(fids))
        assertTrue(port.writes.isEmpty())
    }

    // Review point 5: a new track without duration zeroes the bar and the clock.
    @Test fun `new track with unknown progress and duration writes zeros`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card(progress = 70, pos = 140, dur = 200)), 0)
        port.clearTakes()
        sync.step(true, fids, Target.Show(card(title = "Radio", progress = null, pos = null, dur = null)), 1_500)
        assertEquals(listOf(0), port.valuesFor(14))
        assertEquals(listOf(0), port.valuesFor(41))
        assertEquals(listOf(0), port.valuesFor(42))
        assertEquals(listOf(0), port.valuesFor(43))
        assertEquals(listOf(0), port.valuesFor(33))
    }

    @Test fun `new track writes its total time once`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card(dur = 214)), 0)
        assertEquals(listOf(3), port.valuesFor(42))
        assertEquals(listOf(34), port.valuesFor(43))
        port.clearTakes()
        sync.step(true, fids, Target.Show(card(progress = 11, pos = 22, dur = 214)), 1_500)
        assertTrue(port.valuesFor(42).isEmpty())
    }

    @Test fun `progress and played seconds move without rewriting the title`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card(progress = 10, pos = 20)), 0)
        port.clearTakes()
        assertEquals(Outcome.TICKED, sync.step(true, fids, Target.Show(card(progress = 11, pos = 22)), 1_500))
        assertEquals(listOf(11), port.valuesFor(14))
        assertEquals(listOf(22), port.valuesFor(33))
        assertTrue(port.valuesFor(32).isEmpty())
        assertTrue(port.valuesFor(11).isEmpty())
    }

    @Test fun `the card is re-asserted after the interval`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.REASSERTED, sync.step(true, fids, Target.Show(card()), ClusterMusicSync.REASSERT_MS))
        assertEquals(listOf("Song"), port.valuesFor(11))
    }

    @Test fun `a missing singer fid is skipped, the card still shows`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        assertEquals(Outcome.SHOWN, sync.step(true, fids.copy(singer = null), Target.Show(card()), 0))
        assertTrue(port.valuesFor(21).isEmpty())
    }

    // Review point 6: access is checked when the switch goes on, and re-armed while refused.
    @Test fun `access is re-armed on the switch's rising edge only`() {
        val access = ClusterMusicAccess(retryMs = 60_000)
        assertTrue(access.onSwitch(true))
        assertFalse(access.onSwitch(true))
        assertFalse(access.onSwitch(false))
        assertTrue(access.onSwitch(true))
    }

    @Test fun `a refused session read re-arms at once, then at most every retry interval`() {
        val access = ClusterMusicAccess(retryMs = 60_000)
        assertTrue(access.onRefused(0))
        assertFalse(access.onRefused(1_500))
        assertTrue(access.onRefused(60_000))
        access.onGranted()
        assertTrue(access.onRefused(61_500))
    }
}
