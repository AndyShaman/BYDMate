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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The app half of the power-off report: the start-up de-dupe by the daemon's outcome, the old
 * daemon, and the arming loop. Reporter, daemon client and settings are fakes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PowerOffArmerTest {

    private val reporter = mockk<TelegramReporter>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val state = PowerOffArmState()
    private val store = MemoryStore()
    private lateinit var armer: PowerOffArmer

    private val armedAt = 1_790_000_000_000L
    private val offAt = armedAt + 7_000L
    private val record = ArmedRecord("r1", 42L, "<b>off ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>", armedAt)
    private val queued = mutableListOf<OutboxEntry>()

    private val enabled = MutableStateFlow<String?>("true")

    private class MemoryStore : ArmedStore {
        var record: ArmedRecord? = null
        override fun load(): ArmedRecord? = record
        override fun save(record: ArmedRecord) { this.record = record }
        override fun clear() { record = null }
    }

    @Before fun setUp() {
        armer = PowerOffArmer(mockk<Context>(relaxed = true), reporter, helper, settings, state)
        armer.store = store
        armer.pause = { }
        coEvery { settings.isTgReportOffEnabled() } returns true
        every { settings.observeString(any()) } returns MutableStateFlow(null)
        every { settings.observeString(SettingsRepository.KEY_TG_REPORT_OFF_ENABLED) } returns enabled
        val entry = slot<OutboxEntry>()
        coEvery { reporter.enqueue(capture(entry)) } answers { queued += entry.captured }
        coEvery { helper.isAlive() } returns true
    }

    private fun status(state: Int, powerOffMs: Long = 0L, listening: Int = 2, rc: String = "-") = OffReportStatus(
        queried = OffReportOutcome("r1", state, powerOffMs = powerOffMs, sentAtMs = if (state == OffReportState.SENT) offAt + 900 else 0L, attempts = 1, rc = rc),
        armedId = "",
        armedAtMs = 0L,
        listening = listening,
        last = null,
    )

    // --- start-up de-dupe ---

    @Test fun `a report the daemon sent is dropped, not sent again`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.SENT, offAt)
        armer.checkBeforeBootstrap()
        assertTrue(queued.isEmpty())
        assertNull(store.record)
    }

    @Test fun `a report the daemon failed goes to the outbox with the power-off time`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.FAILED, offAt, rc = "io:UnknownHostException")
        armer.checkBeforeBootstrap()
        assertEquals(1, queued.size)
        val e = queued.single()
        assertEquals("r1", e.id)
        assertEquals(42L, e.chatId)
        assertEquals(offAt, e.createdMs)
        assertFalse(e.lateMark)
        assertEquals("<b>off ${TelegramReportBuilder.formatTime(offAt)}</b>", e.text)
        assertNull(store.record)
        coVerify { reporter.drainOutbox("power_off") }
    }

    @Test fun `a report the daemon does not know (reboot) goes out with the last refresh time`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.UNKNOWN)
        armer.checkBeforeBootstrap()
        assertEquals(armedAt, queued.single().createdMs)
    }

    @Test fun `a report still armed with a live listener is kept, the app restarted on a running car`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.ARMED)
        armer.checkBeforeBootstrap()
        assertTrue(queued.isEmpty())
        assertEquals(record, store.record)
    }

    @Test fun `a report still armed with no listener cannot have fired, so it goes out`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.ARMED, listening = 0)
        armer.checkBeforeBootstrap()
        assertEquals(1, queued.size)
    }

    @Test fun `a report still sending is asked again until it settles`() = runTest {
        store.record = record
        var calls = 0
        coEvery { helper.offReportStatus("r1") } answers {
            calls++
            if (calls < 3) status(OffReportState.SENDING, offAt) else status(OffReportState.SENT, offAt)
        }
        armer.checkBeforeBootstrap()
        assertEquals(3, calls)
        assertTrue(queued.isEmpty())
    }

    @Test fun `an old daemon without the verbs is reported outdated and the report goes by re-send`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus(any()) } returns null
        coEvery { helper.isAlive() } returns true
        armer.checkBeforeBootstrap()
        assertEquals(PowerOffArmState.DAEMON_OUTDATED, state.daemon)
        assertEquals(armedAt, queued.single().createdMs)
    }

    @Test fun `with no daemon up yet the question waits for the bootstrap`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus(any()) } returns null
        coEvery { helper.isAlive() } returns false
        armer.checkBeforeBootstrap()
        assertTrue(queued.isEmpty())

        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.SENT, offAt)
        coEvery { reporter.powerOffReport(any()) } returns null
        val job = launch { armer.run() }
        runCurrent()
        assertTrue(queued.isEmpty())
        armer.bootstrapAttempted()
        runCurrent()
        assertTrue(queued.isEmpty())
        assertNull(store.record)
        job.cancel()
    }

    @Test fun `the check runs once per process`() = runTest {
        store.record = record
        coEvery { helper.offReportStatus("r1") } returns status(OffReportState.UNKNOWN)
        armer.checkBeforeBootstrap()
        store.record = record
        armer.checkBeforeBootstrap()
        assertEquals(1, queued.size)
    }

    @Test fun `a stored report is dropped when the report was switched off since`() = runTest {
        store.record = record
        coEvery { settings.isTgReportOffEnabled() } returns false
        armer.checkBeforeBootstrap()
        assertTrue(queued.isEmpty())
        assertNull(store.record)
    }

    // --- arming loop ---

    private val report1 = PowerOffReport("p1", "tok", 42L, "t1")
    private val report2 = PowerOffReport("p2", "tok", 42L, "t2")

    @Test fun `the loop arms on a change only, and re-arms a daemon that lost the report`() = runTest {
        var current: PowerOffReport = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        coEvery { helper.offReportArm(any(), any(), any(), any()) } returns true
        coEvery { helper.offReportStatus(any()) } answers {
            OffReportStatus(OffReportOutcome(firstArg(), OffReportState.ARMED), firstArg(), 1L, 2, null)
        }
        armer.bootstrapAttempted()
        val job = launch { armer.run() }
        runCurrent()
        coVerify(exactly = 1) { helper.offReportArm("p1", "tok", 42L, "t1") }
        assertEquals("p1", store.record?.id)
        assertEquals(PowerOffArmState.DAEMON_OK, state.daemon)

        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 1) { helper.offReportArm("p1", any(), any(), any()) }

        current = report2
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 1) { helper.offReportArm("p2", "tok", 42L, "t2") }

        coEvery { helper.offReportStatus(any()) } answers {
            OffReportStatus(OffReportOutcome(firstArg(), OffReportState.UNKNOWN), "", 0L, -1, null)
        }
        advanceTimeBy(PowerOffArmer.REFRESH_MS + 1)
        coVerify(exactly = 2) { helper.offReportArm("p2", any(), any(), any()) }
        job.cancel()
    }

    @Test fun `switching the report off disarms at once and forgets the stored report`() = runTest {
        var current: PowerOffReport? = report1
        coEvery { reporter.powerOffReport(any()) } answers { current }
        coEvery { helper.offReportArm(any(), any(), any(), any()) } returns true
        coEvery { helper.offReportDisarm() } returns true
        armer.bootstrapAttempted()
        val job = launch { armer.run() }
        runCurrent()
        assertEquals("p1", store.record?.id)

        current = null
        enabled.value = "false"
        runCurrent()
        coVerify(exactly = 1) { helper.offReportDisarm() }
        assertNull(store.record)
        assertEquals(0L, state.armedAtMs)
        job.cancel()
    }

    @Test fun `an old daemon still gets the report kept on disk for the next start`() = runTest {
        coEvery { reporter.powerOffReport(any()) } returns report1
        coEvery { helper.offReportArm(any(), any(), any(), any()) } returns false
        coEvery { helper.offReportStatus(any()) } returns null
        coEvery { helper.isAlive() } returns true
        armer.bootstrapAttempted()
        val job = launch { armer.run() }
        runCurrent()
        assertEquals(PowerOffArmState.DAEMON_OUTDATED, state.daemon)
        assertEquals("p1", store.record?.id)
        assertEquals(0L, state.armedAtMs)
        job.cancel()
    }
}
