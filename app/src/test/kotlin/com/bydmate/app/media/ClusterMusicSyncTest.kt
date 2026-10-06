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
        audioDev = 1002, singer = 21,
    )

    /**
     * Records every write; [status] decides each reply (null = helper down). [readValue] is what
     * the source fid reads back (null = read failed); [reads] counts the reads.
     */
    private class FakePort(var status: (fid: Int) -> Int? = { 1 }) : ClusterMusicSync.Port {
        val writes = mutableListOf<Pair<Int, Any>>()
        var readValue: Int? = ClusterMusicCard.SOURCE_OTHERS
        var reads = 0
        override suspend fun readInt(dev: Int, fid: Int): Int? {
            reads++
            return readValue
        }
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

    private fun card(title: String = "Song", progress: Int? = 10) =
        Card(title, "Artist", ClusterMusicCard.MUSIC_PLAYING, progress)

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

    // Issue #96: after a hand-off, a paused source no longer takes the card (Idle); that writes nothing.
    @Test fun `idle after a hand-off writes nothing`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        sync.step(true, fids, Target.OtherPlaying("com.ilv.vradio"), 1_500)
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.step(true, fids, Target.Idle, 3_000))
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
        assertEquals(Outcome.NONE, sync.step(false, fids, Target.Idle, 3_000))
        port.status = { 1 }
        assertEquals(Outcome.CLEARED, sync.step(false, fids, Target.Idle, 1_500 + ClusterMusicSync.REASSERT_MS))
        assertFalse(sync.dirty)
    }

    // A dead helper must not burn the clear budget in 30 s: the clear waits for it at the re-assert pace.
    @Test fun `a clear that finds the helper unreachable is retried at the re-assert pace`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        port.status = { null }
        val outage = (1_500L..60_000L step 1_500L).map { sync.step(false, fids, Target.Idle, it) }
        assertEquals(Outcome.CLEAR_FAILED, outage.first())
        assertTrue("attempts ${port.valuesFor(12).size}", port.valuesFor(12).size <= 7)
        assertTrue(sync.dirty)

        port.status = { 1 }
        val after = (61_500L..75_000L step 1_500L).map { sync.step(false, fids, Target.Idle, it) }
        assertEquals(listOf(Outcome.CLEARED), after.filter { it != Outcome.NONE })
        assertFalse(sync.dirty)
    }

    // Codex finding: stop and switch-off clear only a card that is still ours.
    @Test fun `switching off after another player took the card writes nothing`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.HANDED_OFF, sync.step(false, fids, Target.OtherPlaying("com.byd.mediacenter"), 1_500))
        assertTrue(port.writes.isEmpty())
        assertFalse(sync.dirty)
        assertNull(sync.shown)
        assertEquals(Outcome.NONE, sync.step(false, fids, Target.Idle, 3_000))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `stopping after another player took the card since the last poll writes nothing`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.HANDED_OFF, sync.release(fids, Target.OtherPlaying("com.byd.mediacenter")))
        assertTrue(port.writes.isEmpty())
        assertFalse(sync.dirty)
        assertEquals(Outcome.NONE, sync.release(fids, Target.Idle))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `stopping while our source still plays clears the card`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        assertEquals(Outcome.CLEARED, sync.release(fids, Target.Show(card())))
        assertEquals(listOf(" "), port.valuesFor(11))
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

    // Review point 5: a new track without duration empties the bar.
    @Test fun `new track with unknown progress writes an empty bar`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card(progress = 70)), 0)
        port.clearTakes()
        sync.step(true, fids, Target.Show(card(title = "Radio", progress = null)), 1_500)
        assertEquals(listOf(0), port.valuesFor(14))
    }

    @Test fun `progress moves without rewriting the title`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card(progress = 10)), 0)
        port.clearTakes()
        assertEquals(Outcome.TICKED, sync.step(true, fids, Target.Show(card(progress = 11)), 1_500))
        assertEquals(listOf(11), port.valuesFor(14))
        assertTrue(port.valuesFor(11).isEmpty())
    }

    // Second review point 2: played / total time are not written at all.
    @Test fun `no time fids are written`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card(progress = 10)), 0)
        sync.step(true, fids, Target.Show(card(progress = 11)), 1_500)
        sync.step(true, fids, Target.Idle, 3_000)
        assertEquals(setOf(11, 12, 13, 14, 21), port.writes.map { it.first }.toSet())
    }

    // Second review point 1: a car that refuses the card gets no new show until restart.
    @Test fun `refused writes stop new shows until restart`() = runTest {
        val port = FakePort { fid -> if (fid == 13) -1 else 1 }
        val sync = ClusterMusicSync(port)
        assertEquals(Outcome.WRITE_FAILED, sync.step(true, fids, Target.Show(card()), 0))
        assertEquals(Outcome.RETRYING, sync.step(true, fids, Target.Show(card()), 1_500))
        assertEquals(Outcome.REFUSED, sync.step(true, fids, Target.Show(card()), 3_000))
        assertTrue(sync.refused)
        port.clearTakes()
        port.status = { 1 }
        assertEquals(Outcome.NONE, sync.step(true, fids, Target.Show(card()), 4_500))
        assertEquals(Outcome.NONE, sync.step(true, fids, Target.Show(card(title = "Next")), 6_000))
        assertTrue(port.writes.isEmpty())
    }

    /** Card A on the cluster, then the car refuses source for track B until [ClusterMusicSync.refused]. */
    private suspend fun refusedAfterShown(port: FakePort): ClusterMusicSync {
        val sync = ClusterMusicSync(port)
        assertEquals(Outcome.SHOWN, sync.step(true, fids, Target.Show(card(title = "A")), 0))
        port.status = { fid -> if (fid == 13) -1 else 1 }
        repeat(ClusterMusicSync.MAX_WRITE_REFUSALS) { sync.step(true, fids, Target.Show(card(title = "B")), (it + 1) * 1_500L) }
        assertTrue(sync.refused)
        port.clearTakes()
        return sync
    }

    // Codex finding 2: the refusal blocks new shows, not the clear we owe for card A.
    @Test fun `disabling after a refusal still clears our card`() = runTest {
        val port = FakePort()
        val sync = refusedAfterShown(port)
        assertEquals(Outcome.CLEARED, sync.step(false, fids, Target.Idle, 6_000))
        assertEquals(listOf(" "), port.valuesFor(11))
        assertFalse(sync.dirty)
    }

    @Test fun `playback stopping after a refusal still clears our card`() = runTest {
        val port = FakePort()
        val sync = refusedAfterShown(port)
        assertEquals(Outcome.CLEARED, sync.step(true, fids, Target.Idle, 6_000))
        assertEquals(listOf(ClusterMusicCard.MUSIC_STOPPED), port.valuesFor(12))
        assertFalse(sync.dirty)
    }

    @Test fun `a clear owed after a refusal keeps its attempt budget`() = runTest {
        val port = FakePort()
        val sync = refusedAfterShown(port)
        port.status = { -1 }
        var last = Outcome.NONE
        repeat(ClusterMusicSync.MAX_CLEAR_ATTEMPTS) { last = sync.step(false, fids, Target.Idle, 6_000 + it * 1_500L) }
        assertEquals(Outcome.CLEAR_GAVE_UP, last)
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.step(false, fids, Target.Idle, 100_000))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `another player after a refusal forgets our card without clearing it`() = runTest {
        val port = FakePort()
        val sync = refusedAfterShown(port)
        assertEquals(Outcome.HANDED_OFF, sync.step(true, fids, Target.OtherPlaying("com.byd.mediacenter"), 6_000))
        assertTrue(port.writes.isEmpty())
        assertFalse(sync.dirty)
        assertNull(sync.shown)
    }

    @Test fun `stopping after another player took the card writes nothing`() = runTest {
        val port = FakePort()
        val sync = refusedAfterShown(port)
        sync.step(true, fids, Target.OtherPlaying("com.byd.mediacenter"), 6_000)
        assertEquals(Outcome.NONE, sync.release(fids))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `stopping after a refusal clears the card that is still ours`() = runTest {
        val port = FakePort()
        val sync = refusedAfterShown(port)
        assertEquals(Outcome.CLEARED, sync.release(fids))
        assertEquals(listOf(" "), port.valuesFor(11))
    }

    // A Navigator voice prompt over Yandex Music: the music session keeps playing, so the card
    // is neither cleared nor written again.
    @Test fun `a navigator voice prompt over yandex music neither clears nor rewrites the card`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        val music = ClusterMusicCard.SessionSnapshot("ru.yandex.music", 3, "Song", "A")
        val prompt = ClusterMusicCard.SessionSnapshot("ru.yandex.yandexnavi", null, null, null)
        assertEquals(Outcome.SHOWN, sync.step(true, fids, ClusterMusicCard.decide(listOf(music)), 0))
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.step(true, fids, ClusterMusicCard.decide(listOf(prompt, music)), 1_500))
        assertEquals(Outcome.NONE, sync.step(true, fids, ClusterMusicCard.decide(listOf(prompt, music)), 3_000))
        assertEquals(Outcome.NONE, sync.step(true, fids, ClusterMusicCard.decide(listOf(music)), 4_500))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `a success resets the refusal count`() = runTest {
        var refuse = true
        val port = FakePort { fid -> if (fid == 13 && refuse) -1 else 1 }
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        sync.step(true, fids, Target.Show(card()), 1_500)
        refuse = false
        assertEquals(Outcome.SHOWN, sync.step(true, fids, Target.Show(card()), 3_000))
        refuse = true
        sync.step(true, fids, Target.Show(card(title = "Next")), 4_500)
        sync.step(true, fids, Target.Show(card(title = "Next")), 6_000)
        assertFalse(sync.refused)
    }

    @Test fun `an unreachable helper is retried quietly and never refuses`() = runTest {
        val sync = ClusterMusicSync(FakePort { null })
        assertEquals(Outcome.WRITE_FAILED, sync.step(true, fids, Target.Show(card()), 0))
        repeat(10) {
            val outcome = sync.step(true, fids, Target.Show(card()), (it + 1) * 1_500L)
            assertTrue(outcome == Outcome.RETRYING || outcome == Outcome.NONE)
        }
        assertFalse(sync.refused)
    }

    /** What the bridge logs: [ClusterMusicBridge] stays silent on these. */
    private fun reported(outcomes: List<Outcome>) =
        outcomes.filterNot { it in setOf(Outcome.NONE, Outcome.TICKED, Outcome.REASSERTED, Outcome.RETRYING) }

    // Codex finding 3: every attempt costs HelperClient log lines, so a dead helper is retried
    // at the re-assert pace, not on every poll, and the outage is logged once.
    @Test fun `an unreachable helper is retried at the re-assert pace with one outage line`() = runTest {
        val port = FakePort { null }
        val sync = ClusterMusicSync(port)
        val outcomes = (0L..60_000L step 1_500L).map { sync.step(true, fids, Target.Show(card()), it) }
        assertTrue("attempts ${port.valuesFor(13).size}", port.valuesFor(13).size <= 7)
        assertEquals(listOf(Outcome.WRITE_FAILED), reported(outcomes))

        port.status = { 1 }
        val after = (61_500L..90_000L step 1_500L).map { sync.step(true, fids, Target.Show(card()), it) }
        assertEquals(listOf(Outcome.SHOWN), reported(after))
        assertEquals(card(), sync.shown)
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

    // UI7 (platformized) firmware: the cluster renders only source 11 (MUSIC_SOURCE_OTHERS).
    @Test fun `ui7 firmware writes source 11`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        assertEquals(Outcome.SHOWN, sync.step(true, fids, Target.Show(card()), 0))
        assertEquals(listOf(ClusterMusicCard.SOURCE_OTHERS), port.valuesFor(13))
        assertEquals(ClusterMusicCard.SOURCE_OTHERS, sync.sourceCode)
    }

    // Sea Lion 07 and every other non-UI7 car: byte-for-byte the sequence it had, and no reads.
    @Test fun `non-ui7 firmware keeps source 26 and the exact write sequence`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port)
        sync.step(true, fids, Target.Show(card()), 0)
        assertEquals(
            listOf(13 to 26, 12 to ClusterMusicCard.MUSIC_PLAYING, 11 to "Song", 21 to "Artist", 14 to 10),
            port.writes,
        )
        port.clearTakes()
        port.readValue = 26
        assertEquals(Outcome.NONE, sync.watch(fids, 1_000, current = { Target.Show(card()) }))
        assertEquals(0, port.reads)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `ui7 watch rewrites the whole card when the firmware put its source back`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        port.readValue = 26
        assertEquals(Outcome.SOURCE_REASSERTED, sync.watch(fids, 1_000, current = { Target.Show(card()) }))
        assertEquals(
            listOf(13 to ClusterMusicCard.SOURCE_OTHERS, 12 to ClusterMusicCard.MUSIC_PLAYING, 11 to "Song", 21 to "Artist"),
            port.writes,
        )
        assertEquals(1, sync.sourceReasserts)
        assertEquals(26, sync.lastSourceRead)
        // The rewrite counts as the periodic re-assert: no second full write right after it.
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.step(true, fids, Target.Show(card()), 1_500))
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `ui7 watch writes nothing while the source still reads 11`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        (1..5).forEach { assertEquals(Outcome.NONE, sync.watch(fids, it * 1_000L, current = { Target.Show(card()) })) }
        assertEquals(5, port.reads)
        assertTrue(port.writes.isEmpty())
        assertEquals(0, sync.sourceReasserts)
    }

    @Test fun `ui7 watch writes nothing when the card is no longer ours by the time it rewrites`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        port.readValue = 26
        assertEquals(Outcome.NONE, sync.watch(fids, 1_000, current = { null }))
        assertEquals(Outcome.NONE, sync.watch(fids, 2_000, current = { Target.Idle }))
        assertTrue(port.writes.isEmpty())
    }

    // The watch saw another player: the same hand-off the poll does, nothing written over it.
    @Test fun `ui7 watch hands the card off when another player took it`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        port.readValue = 26
        assertEquals(Outcome.HANDED_OFF, sync.watch(fids, 1_000, current = { Target.OtherPlaying("com.byd.mediacenter") }))
        assertTrue(port.writes.isEmpty())
        assertFalse(sync.dirty)
        assertNull(sync.shown)
    }

    // Review finding: paused A owns the card, B starts between polls and the firmware flips the
    // source. The watch shows B; B pauses before the next poll. That poll must keep B, not go back
    // to A's older track through a stale owner.
    @Test fun `a player shown by the watch owns the card for the next poll`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        val owner = ClusterMusicOwner()
        val paused = 2
        val playing = 3
        fun session(pkg: String, state: Int, title: String) =
            ClusterMusicCard.SessionSnapshot(pkg, state, title, "Artist")
        suspend fun poll(sessions: List<ClusterMusicCard.SessionSnapshot>, nowMs: Long): Outcome {
            val target = ClusterMusicCard.decide(sessions, lastOwner = owner.lastOwner)
            owner.observe(target)
            return sync.step(true, fids, target, nowMs)
        }

        poll(listOf(session("app.a", paused, "Old")), 0)
        assertEquals("app.a", owner.lastOwner)

        val bPlays = listOf(session("app.a", paused, "Old"), session("app.b", playing, "New"))
        port.clearTakes()
        port.readValue = 26
        val watched = sync.watch(fids, 1_000, current = {
            ClusterMusicCard.decide(bPlays, lastOwner = owner.lastOwner).also { owner.observe(it) }
        })
        assertEquals(Outcome.SOURCE_REASSERTED, watched)
        assertEquals(listOf("New"), port.valuesFor(11))
        assertEquals("app.b", owner.lastOwner)
        assertEquals("show:app.b", owner.lastTargetKind)

        port.clearTakes()
        poll(listOf(session("app.a", paused, "Old"), session("app.b", paused, "New")), 1_500)
        assertTrue(port.valuesFor(11).none { it == "Old" })
        assertEquals("New", sync.shown?.title)
    }

    @Test fun `the owner reports a target kind only when it changes`() {
        val owner = ClusterMusicOwner()
        val show = Target.Show(Card("Song", "Artist", ClusterMusicCard.MUSIC_PLAYING), "app.a")
        assertEquals("show:app.a", owner.observe(show))
        assertNull(owner.observe(show))
        assertEquals("idle", owner.observe(Target.Idle))
        assertEquals("app.a", owner.lastOwner)
        assertEquals("other:com.byd.mediacenter", owner.observe(Target.OtherPlaying("com.byd.mediacenter")))
        assertEquals("com.byd.mediacenter", owner.lastOwner)
    }

    @Test fun `ui7 watch stops after a hand-off, a switch-off clear and with nothing shown`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        assertEquals(Outcome.NONE, sync.watch(fids, 0, current = { Target.Show(card()) }))
        sync.step(true, fids, Target.Show(card()), 0)
        sync.step(true, fids, Target.OtherPlaying("com.byd.mediacenter"), 1_500)
        port.clearTakes()
        port.readValue = 26
        assertEquals(Outcome.NONE, sync.watch(fids, 2_000, current = { Target.Show(card()) }))

        sync.step(true, fids, Target.Show(card()), 3_000)
        sync.step(false, fids, Target.Idle, 4_500)
        port.clearTakes()
        assertEquals(Outcome.NONE, sync.watch(fids, 5_000, current = { Target.Show(card()) }))
        assertEquals(0, port.reads)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `ui7 watch writes nothing once the switch is off`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        port.readValue = 26
        assertEquals(Outcome.NONE, sync.watch(fids, 1_000, current = { Target.Show(card()) }, stillWanted = { false }))
        assertEquals(0, port.reads)
        assertTrue(port.writes.isEmpty())
    }

    @Test fun `ui7 watch backs off after a failed read and reports it once`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.clearTakes()
        port.readValue = null
        val outage = (1_000L..30_000L step 1_000L).map { sync.watch(fids, it, current = { Target.Show(card()) }) }
        assertEquals(listOf(Outcome.READ_FAILED), reported(outage))
        assertTrue("reads ${port.reads}", port.reads <= 3)
        assertTrue(port.writes.isEmpty())

        port.readValue = 26
        val after = (31_000L..40_000L step 1_000L).map { sync.watch(fids, it, current = { Target.Show(card()) }) }
        assertEquals(Outcome.SOURCE_REASSERTED, after.first { it != Outcome.NONE })
    }

    @Test fun `ui7 watch stays off while the helper is unreachable for writes`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.status = { null }
        port.readValue = 26
        assertEquals(Outcome.WRITE_FAILED, sync.watch(fids, 1_000, current = { Target.Show(card()) }))
        port.clearTakes()
        val reads = port.reads
        (2_000L..9_000L step 1_000L).forEach { assertEquals(Outcome.NONE, sync.watch(fids, it, current = { Target.Show(card()) })) }
        assertEquals(reads, port.reads)
        assertTrue(port.writes.isEmpty())
    }

    // A refused rewrite drops the card from "shown": the watch goes quiet, and the poll's own
    // retries run the refusal budget down to "card off until restart" as before.
    @Test fun `ui7 watch stops after a refused rewrite and stays off once refused`() = runTest {
        val port = FakePort()
        val sync = ClusterMusicSync(port, ui7 = true)
        sync.step(true, fids, Target.Show(card()), 0)
        port.status = { -1 }
        port.readValue = 26
        assertEquals(Outcome.WRITE_FAILED, sync.watch(fids, 1_000, current = { Target.Show(card()) }))
        val reads = port.reads
        (2_000L..5_000L step 1_000L).forEach { assertEquals(Outcome.NONE, sync.watch(fids, it, current = { Target.Show(card()) })) }
        assertEquals(reads, port.reads)
        sync.step(true, fids, Target.Show(card()), 6_000)
        assertEquals(Outcome.REFUSED, sync.step(true, fids, Target.Show(card()), 7_500))
        assertTrue(sync.refused)
        assertEquals(Outcome.NONE, sync.watch(fids, 10_000, current = { Target.Show(card()) }))
        assertEquals(reads, port.reads)
    }
}
