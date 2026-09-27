package com.bydmate.app.data.telegram

import android.content.Context
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app half of the power-off report: the start-up settle by the daemon's fired outcome (or by
 * the heartbeat gap when there is none), and the arming loop. Reporter, daemon client and settings
 * are fakes; the loop runs on virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerOffArmerTest {

    private val reporter = mockk<TelegramReporter>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val state = PowerOffArmState()
    private val store = MemoryStore()
    private lateinit var armer: PowerOffArmer

    private val base = 1_790_000_000_000L
    private val offAt = base - 60_000L
    private val older = ArmedRecord("r1", 42L, "<b>off ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>\nold", base - 90_000L)
    private val newer = ArmedRecord("r2", 42L, "<b>off ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>\nnew", base - 70_000L)
    private val queued = mutableListOf<OutboxEntry>()
    private var pauses = 0

    private val enabled = MutableStateFlow<String?>("true")

    private class MemoryStore : PowerOffStore {
        val records = mutableListOf<ArmedRecord>()
        var beat = 0L
        var heartbeatWrites = 0
        var captured: OffReportOutcome? = null
        override fun records(): List<ArmedRecord> = records.toList()
        override fun pushRecord(record: ArmedRecord) {
            records.removeAll { it.id == record.id }
            records.add(0, record)
            while (records.size > PowerOffStore.MAX_RECORDS) records.removeAt(records.lastIndex)
        }
        override fun clearRecords() = records.clear()
        override fun heartbeat(): Long = beat
        override fun setHeartbeat(ms: Long) { beat = ms; heartbeatWrites++ }
        override fun putCaptured(outcome: OffReportOutcome) { captured = outcome }
        override fun takeCaptured(): OffReportOutcome? = captured.also { captured = null }
    }

    @Before fun setUp() {
        armer = PowerOffArmer(mockk<Context>(relaxed = true), reporter, helper, settings, state)
        armer.store = store
        armer.pause = { pauses++ }
        coEvery { settings.isTgReportOffEnabled() } returns true
        every { settings.observeString(any()) } returns MutableStateFlow(null)
        every { settings.observeString(SettingsRepository.KEY_TG_REPORT_OFF_ENABLED) } returns enabled
        val entry = slot<OutboxEntry>()
        coEvery { reporter.enqueue(capture(entry)) } answers { queued += entry.captured }
        coEvery { reporter.powerOffReport(any()) } returns null
        every { reporter.lastStateHeader(any()) } answers { "last state ${firstArg<Long>()}" }
        coEvery { helper.isAlive() } returns true
        coEvery { helper.offReportStatus(any()) } returns null
    }

    private fun outcome(id: String, state: Int, rc: String = "-") = OffReportOutcome(
        id, state, powerOffMs = offAt,
        sentAtMs = if (state == OffReportState.SENT) offAt + 900 else 0L, attempts = 1, rc = rc,
    )

    private fun live(queried: Int = OffReportState.UNKNOWN, last: OffReportOutcome? = null) = OffReportStatus(
        queried = OffReportOutcome("r2", queried), armedId = "", armedAtMs = 0L, listening = 2, last = last,
    )

    private fun stored(vararg records: ArmedRecord) {
        store.records.clear()
        store.records.addAll(records)
    }

    /** Starts the armer after the bootstrap and lets the start-up settle run. */
    private fun TestScope.start(): Job {
        armer.clock = { base + testScheduler.currentTime }
        armer.bootstrapAttempted()
        val job = launch { armer.run() }
        runCurrent()
        return job
    }

    // --- decision 1: no fired outcome, the heartbeat gap decides ---

    @Test fun `no fired outcome and a long gap re-sends the newest under the neutral header`() = runTest {
        stored(newer, older)
        store.beat = base - 4 * 60_000L
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN)
        val job = start()
        val e = queued.single()
        assertEquals("r2", e.id)
        val at = maxOf(base - 4 * 60_000L, newer.createdMs)
        assertEquals(at, e.createdMs)
        assertEquals("<b>last state $at</b>\nnew", e.text)
        assertTrue(store.records().none { it.id == "r2" || it.id == "r1" })
        coVerify { reporter.drainOutbox("power_off") }
        job.cancel()
    }

    @Test fun `no fired outcome and a short gap is an app restart on a running car, dropped`() = runTest {
        stored(newer)
        store.beat = base - 2 * 60_000L
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.ARMED)
        val job = start()
        assertTrue(queued.isEmpty())
        assertTrue(store.records().isEmpty())
        job.cancel()
    }

    @Test fun `no heartbeat at all is no proof either, dropped`() = runTest {
        stored(newer)
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN)
        val job = start()
        assertTrue(queued.isEmpty())
        job.cancel()
    }

    @Test fun `an old daemon without the verbs is reported outdated and goes by the gap rule`() = runTest {
        stored(newer)
        store.beat = base - 10 * 60_000L
        coEvery { helper.offReportStatus(any()) } returns null
        val job = start()
        assertEquals(PowerOffArmState.DAEMON_OUTDATED, state.daemon)
        assertTrue(queued.single().text.startsWith("<b>last state"))
        job.cancel()
    }

    @Test fun `a stored report is dropped when the report was switched off since`() = runTest {
        stored(newer)
        store.beat = base - 10 * 60_000L
        coEvery { settings.isTgReportOffEnabled() } returns false
        val job = start()
        assertTrue(queued.isEmpty())
        assertTrue(store.records().isEmpty())
        job.cancel()
    }

    // --- decisions 2 and 4: the fired outcome picks the report ---

    @Test fun `an outcome captured before the daemon was replaced wins over the new daemon's blank`() = runTest {
        stored(newer, older)
        store.captured = outcome("r2", OffReportState.FAILED, rc = "io:UnknownHostException")
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN)
        val job = start()
        val e = queued.single()
        assertEquals("r2", e.id)
        assertEquals(offAt, e.createdMs)
        assertEquals("<b>off ${TelegramReportBuilder.formatTime(offAt)}</b>\nnew", e.text)
        assertEquals(null, store.captured)
        job.cancel()
    }

    @Test fun `a captured sent outcome drops the reports`() = runTest {
        stored(newer)
        store.beat = base - 10 * 60_000L
        store.captured = outcome("r2", OffReportState.SENT)
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN)
        val job = start()
        assertTrue(queued.isEmpty())
        assertTrue(store.records().isEmpty())
        job.cancel()
    }

    @Test fun `the daemon failed the older report, that text goes out with its power-off time`() = runTest {
        stored(newer, older)
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN, outcome("r1", OffReportState.FAILED))
        val job = start()
        val e = queued.single()
        assertEquals("r1", e.id)
        assertEquals(42L, e.chatId)
        assertEquals(offAt, e.createdMs)
        assertEquals("<b>off ${TelegramReportBuilder.formatTime(offAt)}</b>\nold", e.text)
        assertTrue(store.records().isEmpty())
        job.cancel()
    }

    @Test fun `the daemon sent the older report, both stored reports are dropped`() = runTest {
        stored(newer, older)
        store.beat = base - 10 * 60_000L
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN, outcome("r1", OffReportState.SENT))
        val job = start()
        assertTrue(queued.isEmpty())
        assertTrue(store.records().isEmpty())
        job.cancel()
    }

    @Test fun `a fired outcome of a report we never stored is no proof for ours`() = runTest {
        stored(newer)
        store.beat = base - 60_000L
        coEvery { helper.offReportStatus("r2") } returns live(OffReportState.UNKNOWN, outcome("zz", OffReportState.FAILED))
        val job = start()
        assertTrue(queued.isEmpty())
        job.cancel()
    }

    // --- decision 6: never in parallel with a daemon still sending ---

    private fun sendingThen(vararg later: Int) {
        var calls = 0
        coEvery { helper.offReportStatus("r2") } answers {
            val s = if (calls == 0) OffReportState.SENDING else later.getOrElse(calls - 1) { OffReportState.SENDING }
            calls++
            live(OffReportState.UNKNOWN, outcome("r2", s)).copy(queried = outcome("r2", s))
        }
    }

    @Test fun `still sending is asked again and dropped once the daemon sent it`() = runTest {
        stored(newer)
        sendingThen(OffReportState.SENDING, OffReportState.SENT)
        val job = start()
        assertTrue(queued.isEmpty())
        assertEquals(2, pauses)
        job.cancel()
    }

    @Test fun `still sending then failed is re-sent once, with the power-off time`() = runTest {
        stored(newer)
        sendingThen(OffReportState.FAILED)
        val job = start()
        assertEquals(offAt, queued.single().createdMs)
        job.cancel()
    }

    @Test fun `still sending after two minutes is left alone, nothing sent`() = runTest {
        stored(newer)
        sendingThen()
        val job = start()
        assertTrue(queued.isEmpty())
        assertEquals((PowerOffArmer.SENDING_WAIT_MS / PowerOffArmer.SENDING_POLL_MS).toInt(), pauses)
        job.cancel()
    }

    // --- decision 12: re-sent reports carry the late mark ---

    @Test fun `every re-send goes through the outbox with the late mark on`() = runTest {
        stored(newer)
        store.captured = outcome("r2", OffReportState.FAILED)
        val job = start()
        assertTrue(queued.single().lateMark)
        job.cancel()
    }

    @Test fun `the settle runs once per process`() = runTest {
        stored(newer)
        store.captured = outcome("r2", OffReportState.FAILED)
        start().cancel()
        stored(newer)
        store.captured = outcome("r2", OffReportState.FAILED)
        val job = start()
        assertEquals(1, queued.size)
        job.cancel()
    }

    // --- arming loop ---

    private val report1 = PowerOffReport("p1", "tok", 42L, "t1")
    private val report2 = PowerOffReport("p2", "tok", 42L, "t2")

    @Test fun `the loop arms and stores on a change only, and re-arms a daemon that lost the report`() = runTest {
        var current: PowerOffReport = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        coEvery { helper.offReportArm(any(), any(), any(), any()) } returns true
        coEvery { helper.offReportStatus(any()) } answers {
            OffReportStatus(OffReportOutcome(firstArg(), OffReportState.ARMED), firstArg(), 1L, 2, null)
        }
        val job = start()
        coVerify(exactly = 1) { helper.offReportArm("p1", "tok", 42L, "t1") }
        assertEquals(listOf("p1"), store.records().map { it.id })
        assertEquals(PowerOffArmState.DAEMON_OK, state.daemon)

        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 1) { helper.offReportArm("p1", any(), any(), any()) }
        assertEquals(1, store.records().size)

        current = report2
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 1) { helper.offReportArm("p2", "tok", 42L, "t2") }
        assertEquals(listOf("p2", "p1"), store.records().map { it.id })

        coEvery { helper.offReportStatus(any()) } answers {
            OffReportStatus(OffReportOutcome(firstArg(), OffReportState.UNKNOWN), "", 0L, -1, null)
        }
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 2) { helper.offReportArm("p2", any(), any(), any()) }
        job.cancel()
    }

    @Test fun `the heartbeat is written at most once a minute`() = runTest {
        val job = start()
        assertEquals(1, store.heartbeatWrites)
        advanceTimeBy(PowerOffArmer.HEARTBEAT_EVERY_MS - 1)
        assertEquals(1, store.heartbeatWrites)
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        assertEquals(2, store.heartbeatWrites)
        job.cancel()
    }

    @Test fun `a disarm the daemon did not confirm is retried, the local state stays until it does`() = runTest {
        var current: PowerOffReport? = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        coEvery { helper.offReportArm(any(), any(), any(), any()) } returns true
        coEvery { helper.offReportDisarm() } returns false
        val job = start()
        assertEquals("p1", store.records().single().id)
        val armedAt = state.armedAtMs
        assertTrue(armedAt > 0L)

        current = null
        enabled.value = "false"
        runCurrent()
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(atLeast = 2) { helper.offReportDisarm() }
        assertEquals("p1", store.records().single().id)
        assertEquals(armedAt, state.armedAtMs)

        coEvery { helper.offReportDisarm() } returns true
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        assertTrue(store.records().isEmpty())
        assertEquals(0L, state.armedAtMs)
        job.cancel()
    }

    @Test fun `an old daemon still gets the report kept on disk for the next start`() = runTest {
        coEvery { reporter.powerOffReport(any()) } returns report1
        coEvery { helper.offReportArm(any(), any(), any(), any()) } returns false
        coEvery { helper.offReportStatus(any()) } returns null
        val job = start()
        assertEquals(PowerOffArmState.DAEMON_OUTDATED, state.daemon)
        assertEquals("p1", store.records().single().id)
        assertEquals(0L, state.armedAtMs)
        job.cancel()
    }
}
