package com.bydmate.app.data.vehicle

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Generation probe + percent→CTRL mapping for the window channel (#79).
 *
 * The probe walks the four DiLink 5.0 window percent fids one at a time and stops at the
 * first conclusive answer, so a percent-capable unit pays a single read. Only when every one
 * of them answers DEVICE_THE_FEATURE_LINK_ERROR does it consult the DiLink 3.0 twin of the
 * rear-right fid. CTRL is remembered only after a SECOND probe sees that signature at least
 * ten minutes after the first, so no cold-start link error storm can cement the wrong channel.
 */
class WindowChannelRouterTest {

    private companion object {
        const val DEV = 1001
        const val FL_FID = 947912728
        const val FR_FID = 1267728400
        const val RL_FID = 947912736
        const val RR_FID = 947912752
        const val ALT_FID = 1267728408
        const val LINK_ERROR = 65535L
        val PERCENT_FIDS = listOf(FL_FID, FR_FID, RL_FID, RR_FID)

        /** Any non-zero wall clock: 0 is the store's "no pending candidate" marker. */
        const val BASE_MS = 1_700_000_000_000L
    }

    private class FakeStore(private var value: WindowChannel = WindowChannel.UNKNOWN) : WindowChannelStore {
        var writes = 0
        var candidateAt = 0L
        var candidateWrites = 0
        override fun winner() = value
        override fun setWinner(channel: WindowChannel) { value = channel; candidateAt = 0L; writes++ }
        override fun ctrlCandidateAtMs() = candidateAt
        override fun setCtrlCandidateAtMs(ts: Long) { candidateAt = ts; candidateWrites++ }
    }

    /** Counts reads per fid so single-flight, short-circuit and memoization can be asserted.
     *  [answers] is mutable so one helper can play a whole timeline against one router.
     *  [entered] completes as soon as a caller is inside a read, [gate] holds it there. */
    private class CountingHelper(
        var answers: Map<Int, Long?>,
        private val gate: CompletableDeferred<Unit>? = null,
        private val entered: CompletableDeferred<Unit>? = null,
    ) : HelperClient by mockk(relaxed = true) {
        val reads = AtomicInteger(0)
        override suspend fun read(dev: Int, fid: Int, tx: Int): Long? {
            entered?.complete(Unit)
            gate?.await()
            reads.incrementAndGet()
            return answers[fid]
        }
    }

    private fun router(helper: HelperClient, store: WindowChannelStore, clock: () -> Long = { BASE_MS }) =
        WindowChannelRouter(helper, store, clock)

    private fun allLinkError(alt: Long? = 0L): Map<Int, Long?> =
        PERCENT_FIDS.associateWith { LINK_ERROR } + mapOf(ALT_FID to alt)

    @Test fun `valid primary percent caches PERCENT and leaves the write untouched`() = runTest {
        val helper = mockk<HelperClient>()
        PERCENT_FIDS.forEach { coEvery { helper.read(DEV, it) } returns 40L }
        val store = FakeStore()

        val routed = router(helper, store).route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.PERCENT, store.winner())
        coVerify(exactly = 0) { helper.read(DEV, ALT_FID) }
    }

    /** A healthy unit must not be walked fid by fid: the first valid percent ends the probe. */
    @Test fun `a valid first fid short-circuits the probe after one read`() = runTest {
        val helper = CountingHelper(PERCENT_FIDS.associateWith { 40L } + mapOf(ALT_FID to 0L))
        val store = FakeStore()

        router(helper, store).route("window_driver_pos", 100)

        assertEquals(WindowChannel.PERCENT, store.winner())
        assertEquals("one binder read is the whole cost on a percent-capable unit", 1, helper.reads.get())
    }

    /** One live percent fid is enough: the family clearly exists on this firmware. */
    @Test fun `a later percent fid still decides PERCENT after link errors ahead of it`() = runTest {
        val helper = mockk<HelperClient>()
        coEvery { helper.read(DEV, FL_FID) } returns LINK_ERROR
        coEvery { helper.read(DEV, FR_FID) } returns LINK_ERROR
        coEvery { helper.read(DEV, RL_FID) } returns LINK_ERROR
        coEvery { helper.read(DEV, RR_FID) } returns 0L
        val store = FakeStore()

        val routed = router(helper, store).route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.PERCENT, store.winner())
        coVerify(exactly = 0) { helper.read(DEV, ALT_FID) }
    }

    /** Rail (a): a single DiLink 5.0 fid answering 65535 is not evidence of a missing family. */
    @Test fun `one link error among live percent fids never reaches the alt fid`() = runTest {
        val helper = mockk<HelperClient>()
        coEvery { helper.read(DEV, FL_FID) } returns LINK_ERROR
        coEvery { helper.read(DEV, FR_FID) } returns 0L
        val store = FakeStore()

        val routed = router(helper, store).route("window_rear_right_pos", 100)

        assertEquals(RoutedWrite("window_rear_right_pos", 100), routed)
        assertEquals(WindowChannel.PERCENT, store.winner())
        coVerify(exactly = 0) { helper.read(DEV, ALT_FID) }
    }

    /** A read that simply failed mid-walk says nothing about the family — stop and stay put. */
    @Test fun `a failed read mid-probe ends the probe undecided`() = runTest {
        val helper = CountingHelper(mapOf(FL_FID to LINK_ERROR, FR_FID to null) + mapOf(ALT_FID to 0L))
        val store = FakeStore()

        val routed = router(helper, store).route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals("the walk must stop on the failed fid", 2, helper.reads.get())
        assertEquals("a failed read is not a candidate", 0, store.candidateWrites)
    }

    /** Rail (b): the full signature routes on percent the first time and CTRL ten minutes later. */
    @Test fun `CTRL needs a second candidate probe ten minutes after the first`() = runTest {
        val helper = CountingHelper(allLinkError())
        val store = FakeStore()
        var now = BASE_MS
        val r = router(helper, store) { now }

        val first = r.route("window_driver_pos", 100)
        assertEquals("first candidate probe must stay on percent", RoutedWrite("window_driver_pos", 100), first)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals(BASE_MS, store.candidateAt)
        assertEquals("nothing may be cemented on one sample", 0, store.writes)

        now += WindowChannelRouter.CTRL_CONFIRM_MIN_GAP_MS
        val second = r.route("window_driver_pos", 100)
        assertEquals(RoutedWrite("window_driver_ctrl", 1), second)
        assertEquals(WindowChannel.CTRL, store.winner())
    }

    /** A cold start can produce several candidate probes in a row; they must not add up. */
    @Test fun `a second candidate inside ten minutes does not count as the pair`() = runTest {
        val helper = CountingHelper(allLinkError())
        val store = FakeStore()
        var now = BASE_MS
        val r = router(helper, store) { now }

        r.route("window_driver_pos", 100)
        now += WindowChannelRouter.CTRL_CONFIRM_MIN_GAP_MS - 1
        val routed = r.route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals("it becomes the new first candidate", now, store.candidateAt)
        assertEquals(0, store.writes)

        // And from that new first candidate a full gap does fix CTRL.
        now += WindowChannelRouter.CTRL_CONFIRM_MIN_GAP_MS
        assertEquals(RoutedWrite("window_driver_ctrl", 1), r.route("window_driver_pos", 100))
    }

    /** Evidence about a car that has been driven for a week is stale — the pair starts over. */
    @Test fun `a candidate older than a week starts the pair over`() = runTest {
        val helper = CountingHelper(allLinkError())
        val store = FakeStore()
        var now = BASE_MS
        val r = router(helper, store) { now }

        r.route("window_driver_pos", 100)
        now += WindowChannelRouter.CTRL_CONFIRM_MAX_GAP_MS + 1
        val routed = r.route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals(now, store.candidateAt)
        assertEquals(0, store.writes)
    }

    /** A candidate followed by a valid percent reading must start over. */
    @Test fun `a valid percent reading clears the pending candidate`() = runTest {
        val store = FakeStore()
        var now = BASE_MS
        router(CountingHelper(allLinkError()), store) { now }.route("window_driver_pos", 100)
        assertEquals(BASE_MS, store.candidateAt)

        now += WindowChannelRouter.MEMO_TTL_MS
        val healthy = CountingHelper(PERCENT_FIDS.associateWith { 30L })
        val routed = router(healthy, store) { now }.route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.PERCENT, store.winner())
        assertEquals("candidate must be cleared", 0L, store.candidateAt)
    }

    @Test fun `an ambiguous probe clears the pending candidate`() = runTest {
        val store = FakeStore()
        var now = BASE_MS
        router(CountingHelper(allLinkError()), store) { now }.route("window_driver_pos", 100)
        assertEquals(BASE_MS, store.candidateAt)

        now += WindowChannelRouter.MEMO_TTL_MS
        // Percent family reports a link error, but so does the alt fid: no verdict.
        router(CountingHelper(allLinkError(alt = LINK_ERROR)), store) { now }.route("window_driver_pos", 100)

        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals(0L, store.candidateAt)
    }

    @Test fun `primary link error with dead alt stays undecided on the percent path`() = runTest {
        val helper = CountingHelper(allLinkError(alt = LINK_ERROR))
        val store = FakeStore()

        val routed = router(helper, store).route("window_rear_left_pos", 50)

        assertEquals(RoutedWrite("window_rear_left_pos", 50), routed)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals("nothing may be cached on an ambiguous probe", 0, store.writes)
    }

    @Test fun `unreachable daemon caches nothing and keeps the percent path`() = runTest {
        val helper = CountingHelper(PERCENT_FIDS.associateWith { null })
        val store = FakeStore()

        val routed = router(helper, store).route("window_driver_pos", 0)

        assertEquals(RoutedWrite("window_driver_pos", 0), routed)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals(0, store.writes)
        assertEquals("a failed read is not a candidate", 0, store.candidateWrites)
    }

    @Test fun `a throwing probe read is treated as undecided`() = runTest {
        val helper = mockk<HelperClient>()
        coEvery { helper.read(DEV, any(), any()) } throws java.io.IOException("binder died")
        val store = FakeStore()

        val routed = router(helper, store).route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(0, store.writes)
    }

    @Test fun `an unexpected sentinel is not read as a missing percent family`() = runTest {
        val helper = CountingHelper(PERCENT_FIDS.associateWith { -10011L } + mapOf(ALT_FID to 0L))
        val store = FakeStore()

        val routed = router(helper, store).route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals("another sentinel ends the walk, alt fid untouched", 1, helper.reads.get())
    }

    /** Composite commands fan out to four doors; all of them must get one verdict. */
    @Test fun `an undecided verdict is memoized across a composite fan-out`() = runTest {
        val helper = CountingHelper(PERCENT_FIDS.associateWith { null })
        val store = FakeStore()
        val r = router(helper, store)

        for (action in listOf(
            "window_driver_pos", "window_passenger_pos", "window_rear_left_pos", "window_rear_right_pos",
        )) {
            assertEquals(RoutedWrite(action, 100), r.route(action, 100))
        }

        assertEquals("four doors must share one probe", 1, helper.reads.get())
    }

    @Test fun `a stale memo is re-probed`() = runTest {
        val helper = CountingHelper(PERCENT_FIDS.associateWith { null })
        val store = FakeStore()
        var now = BASE_MS
        val r = router(helper, store) { now }

        r.route("window_driver_pos", 100)
        now += WindowChannelRouter.MEMO_TTL_MS
        r.route("window_driver_pos", 100)

        assertEquals(2, helper.reads.get())
    }

    /**
     * Two concurrent window writes must produce ONE probe, not two racing ones. The writers are
     * driven to a real overlap before the read is allowed to finish: the first parks inside the
     * probe (on [gate]), the second parks on the probe mutex. Without the mutex the second
     * writer would enter the probe too and the read count below would be 2.
     */
    @Test fun `the probe is single-flight`() = runTest {
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val helper = CountingHelper(PERCENT_FIDS.associateWith { null }, gate, entered)
        val store = FakeStore()
        val r = router(helper, store)
        val verdicts = mutableListOf<RoutedWrite>()

        val a = launch { verdicts += r.route("window_driver_pos", 100) }
        val b = launch { verdicts += r.route("window_driver_pos", 100) }
        advanceUntilIdle()   // run both to their suspension points

        assertTrue("the first writer must be parked inside the probe", entered.isCompleted)
        assertEquals("no read may complete while the gate is shut", 0, helper.reads.get())
        assertEquals("neither writer may have produced a verdict yet", 0, verdicts.size)

        gate.complete(Unit)
        a.join(); b.join()

        assertEquals("a second writer must reuse the first verdict", 1, helper.reads.get())
        assertEquals(2, verdicts.size)
        assertEquals(
            "both writers must route the same way",
            setOf(RoutedWrite("window_driver_pos", 100)),
            verdicts.toSet(),
        )
    }

    /** While a candidate is pending the verdict must NOT be memoized: re-probing is what lets
     *  the next healthy read clear the stamp (codex, wave C). */
    @Test fun `a pending candidate is re-probed instead of memoized`() = runTest {
        val helper = CountingHelper(allLinkError())
        val store = FakeStore()
        val r = router(helper, store)   // clock frozen: well inside MEMO_TTL_MS

        r.route("window_driver_pos", 100)
        val afterFirst = helper.reads.get()
        r.route("window_passenger_pos", 100)

        assertEquals("the second command must run a full probe", 2 * afterFirst, helper.reads.get())
        assertEquals(WindowChannel.UNKNOWN, store.winner())
        assertEquals("and it may not pair with its own stamp", 0, store.writes)
    }

    /**
     * The sequence codex reconstructed: a candidate at t=0, a healthy command 15 s later (inside
     * the memo window), then a second anomaly past the confirm gap. The healthy command must
     * actually probe, latch PERCENT and wipe the stamp, so the later anomaly cannot pair up
     * with it.
     */
    @Test fun `a healthy command inside the memo window disproves a pending candidate`() = runTest {
        val helper = CountingHelper(allLinkError())
        val store = FakeStore()
        var now = BASE_MS
        val r = router(helper, store) { now }

        r.route("window_driver_pos", 100)
        assertEquals(BASE_MS, store.candidateAt)
        val afterCandidate = helper.reads.get()

        now += 15_000L
        helper.answers = PERCENT_FIDS.associateWith { 40L }
        r.route("window_driver_pos", 100)
        assertEquals("the memo must not hide the healthy read", afterCandidate + 1, helper.reads.get())
        assertEquals(WindowChannel.PERCENT, store.winner())
        assertEquals("one healthy observation ends the candidate history", 0L, store.candidateAt)

        now += 11 * 60 * 1000L
        helper.answers = allLinkError()
        val routed = r.route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals("a later anomaly must not resurrect the disproved candidate", WindowChannel.PERCENT, store.winner())
    }

    @Test fun `a cached channel is reused without probing again`() = runTest {
        val helper = mockk<HelperClient>()
        val store = FakeStore(WindowChannel.CTRL)

        val routed = router(helper, store).route("window_passenger_pos", 0)

        assertEquals(RoutedWrite("window_passenger_ctrl", 2), routed)
        coVerify(exactly = 0) { helper.read(any(), any()) }
    }

    @Test fun `a cached PERCENT channel survives a later link error reading`() = runTest {
        val helper = CountingHelper(allLinkError())
        val store = FakeStore(WindowChannel.PERCENT)

        val routed = router(helper, store).route("window_driver_pos", 100)

        assertEquals(RoutedWrite("window_driver_pos", 100), routed)
        assertEquals(WindowChannel.PERCENT, store.winner())
        assertEquals(0, helper.reads.get())
    }

    @Test fun `a non-window action never triggers a probe`() = runTest {
        val helper = mockk<HelperClient>()
        val store = FakeStore()

        val routed = router(helper, store).route("sunroof_open", 1)

        assertEquals(RoutedWrite("sunroof_open", 1), routed)
        coVerify(exactly = 0) { helper.read(any(), any()) }
    }

    @Test fun `out of range percent keeps the percent action for the range gate`() = runTest {
        val helper = mockk<HelperClient>()
        val store = FakeStore(WindowChannel.CTRL)

        assertEquals(RoutedWrite("window_driver_pos", 150), router(helper, store).route("window_driver_pos", 150))
        assertEquals(RoutedWrite("window_driver_pos", -1), router(helper, store).route("window_driver_pos", -1))
    }

    @Test fun `all four percent actions map to their ctrl twins`() = runTest {
        val helper = mockk<HelperClient>()
        val store = FakeStore(WindowChannel.CTRL)
        val r = router(helper, store)

        assertEquals("window_driver_ctrl", r.route("window_driver_pos", 100).actionName)
        assertEquals("window_passenger_ctrl", r.route("window_passenger_pos", 100).actionName)
        assertEquals("window_rear_left_ctrl", r.route("window_rear_left_pos", 100).actionName)
        assertEquals("window_rear_right_ctrl", r.route("window_rear_right_pos", 100).actionName)
    }

    /** 0=close, vent aperture=vent detent, 50=half, 100=open. VENT_PCT (10) is the
     *  aperture CommandTranslator emits for every 通风 window command. */
    @Test fun `percent maps to the matching ctrl detent`() {
        assertEquals(2, WindowChannelRouter.ctrlValue(0))
        assertEquals(5, WindowChannelRouter.ctrlValue(10))
        assertEquals(5, WindowChannelRouter.ctrlValue(25))
        assertEquals(4, WindowChannelRouter.ctrlValue(26))
        assertEquals(4, WindowChannelRouter.ctrlValue(50))
        assertEquals(4, WindowChannelRouter.ctrlValue(75))
        assertEquals(1, WindowChannelRouter.ctrlValue(76))
        assertEquals(1, WindowChannelRouter.ctrlValue(100))
    }
}
