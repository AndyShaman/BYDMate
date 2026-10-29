package com.bydmate.app.data.dtc

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramError
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.backup.TgBackupConfig
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.DtcFixtures
import com.bydmate.app.helper.dtc.DtcScanStatus
import com.bydmate.app.helper.dtc.DtcWire
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The repository must run its work on the scope it is given (no Dispatchers.IO switch), so the
 * test scheduler decides when a poll happens.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class DtcScanRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("dtc_scan", Context.MODE_PRIVATE)

    private val helper = mockk<HelperClient>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val sink = mockk<TelegramBackupSink>()

    private var startReply: Int? = 0
    private var statuses = ArrayDeque<DtcScanStatus?>()
    private var fallbackStatus: DtcScanStatus? = running()

    private var gear: Int? = 1
    private var sampleAgeMs = 1_000L
    private var wall = 1_000_000L

    @Before fun setUp() {
        prefs.edit().clear().commit()
        coEvery { helper.dtcScanStart(any(), any()) } answers { startReply }
        coEvery { helper.dtcScanStatus() } answers { statuses.removeFirstOrNull() ?: fallbackStatus }
        coEvery { sink.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { settings.getTgBackupConfig() } returns TgBackupConfig("tok", 5L, "bot", "chat")
    }

    private fun running() = DtcScanStatus(DtcWire.STATE_RUNNING, 5, 18, "", emptyList())
    private fun done() = DtcScanStatus(DtcWire.STATE_DONE, 18, 18, "", DtcFixtures.preClearScan())
    private fun ended(state: Int, reason: String) = DtcScanStatus(state, 3, 18, reason, emptyList())

    private fun repo(scope: TestScope) = DtcScanRepository(
        helper = helper,
        prefs = prefs,
        settings = settings,
        sink = sink,
        scope = scope.backgroundScope,
        liveSample = { gear?.let { TimedSnapshot(diParsData().copy(gear = it), 100_000L - sampleAgeMs) } },
        serviceRunning = { true },
        elapsedNow = { 100_000L },
        wallNow = { wall },
    )

    private suspend fun TestScope.finishScan(r: DtcScanRepository) {
        statuses.addLast(done())
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test fun `an old sample or a gear outside P sends nothing`() = runTest {
        sampleAgeMs = 11_000
        val r = repo(this)
        r.start()
        runCurrent()
        assertEquals(DtcScanPhase.IDLE, r.state.value.phase)
        assertEquals(DtcScanError.NOT_PARK, r.state.value.error)

        sampleAgeMs = 1_000
        gear = 4
        r.start()
        runCurrent()
        assertEquals(DtcScanError.NOT_PARK, r.state.value.error)
        gear = null
        r.start()
        runCurrent()
        assertEquals(DtcScanError.NOT_PARK, r.state.value.error)
        coVerify(exactly = 0) { helper.dtcScanStart(any(), any()) }
    }

    @Test fun `a fresh P starts the scan once with the gear address`() = runTest {
        val r = repo(this)
        r.start()
        runCurrent()
        val gearAddress = FidAddresses.of("gear")
        coVerify(exactly = 1) { helper.dtcScanStart(gearAddress.device, gearAddress.fid) }
        assertEquals(DtcScanPhase.SCANNING, r.state.value.phase)
        assertEquals(18, r.state.value.total)
        assertNull(r.state.value.error)
    }

    @Test fun `a tap while scanning is ignored`() = runTest {
        val r = repo(this)
        r.start()
        runCurrent()
        r.start()
        runCurrent()
        coVerify(exactly = 1) { helper.dtcScanStart(any(), any()) }
    }

    @Test fun `a busy reply attaches to the running scan and polls it`() = runTest {
        startReply = 1
        statuses.addLast(running())
        statuses.addLast(done())
        val r = repo(this)
        r.start()
        runCurrent()
        assertEquals(DtcScanPhase.SCANNING, r.state.value.phase)
        advanceTimeBy(5_000)
        runCurrent()
        coVerify(atLeast = 2) { helper.dtcScanStatus() }
        assertEquals(DtcScanPhase.IDLE, r.state.value.phase)
        assertNotNull(r.last.value)
    }

    @Test fun `a finished scan is saved with its time and parsed as before`() = runTest {
        val r = repo(this)
        finishScan(r)
        assertEquals(DtcScanPhase.IDLE, r.state.value.phase)
        assertNull(r.state.value.error)
        val last = r.last.value!!
        assertEquals(wall, last.at)
        val s = DtcParser.summarize(last.results)
        assertEquals(14, s.answered)
        assertEquals(18, s.total)
        assertEquals(1, s.now)
        assertEquals(14, s.old)
        assertNotNull(prefs.getString("last", null))
    }

    @Test fun `an unavailable or aborted scan keeps the previous result`() = runTest {
        val r = repo(this)
        finishScan(r)
        val before = r.last.value!!.at
        wall += 60_000

        statuses.addLast(ended(DtcWire.STATE_UNAVAILABLE, "transport_rejected rc=-2147482648"))
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(DtcScanError.UNAVAILABLE, r.state.value.error)
        assertEquals(before, r.last.value!!.at)

        statuses.addLast(ended(DtcWire.STATE_ABORTED, "not_park gear=4"))
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(DtcScanError.NOT_PARK, r.state.value.error)
        assertEquals(before, r.last.value!!.at)
    }

    @Test fun `an unreachable or outdated daemon fails the tap`() = runTest {
        val r = repo(this)
        startReply = null
        r.start()
        runCurrent()
        assertEquals(DtcScanError.DAEMON_UNREACHABLE, r.state.value.error)
        assertEquals(DtcScanPhase.IDLE, r.state.value.phase)

        startReply = -2
        r.start()
        runCurrent()
        assertEquals(DtcScanError.DAEMON_OUTDATED, r.state.value.error)
        assertEquals(DtcScanPhase.IDLE, r.state.value.phase)
    }

    @Test fun `three lost polls in a row fail the scan`() = runTest {
        fallbackStatus = null
        val r = repo(this)
        r.start()
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(DtcScanError.STATUS_LOST, r.state.value.error)
        assertEquals(DtcScanPhase.IDLE, r.state.value.phase)
    }

    @Test fun `the saved scan survives a new repository`() = runTest {
        val r = repo(this)
        finishScan(r)
        val again = repo(this).last.value!!
        val first = r.last.value!!
        assertEquals(first.at, again.at)
        assertEquals(first.results.map { it.addr }, again.results.map { it.addr })
        assertEquals(first.results.map { it.outcome }, again.results.map { it.outcome })
        assertEquals(DtcParser.summarize(first.results), DtcParser.summarize(again.results))
    }

    @Test fun `corrupt saved json reads as no scan`() = runTest {
        prefs.edit().putString("last", "{not json").commit()
        assertNull(repo(this).last.value)
    }

    @Test fun `nothing is sent to telegram by itself`() = runTest {
        val r = repo(this)
        finishScan(r)
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `the send button sends one plain message with the codes`() = runTest {
        val r = repo(this)
        finishScan(r)
        val result = r.sendLastScan(DtcFixtures.labels)
        assertEquals(DtcSendResult(DtcSendOutcome.SENT), result)
        coVerify(exactly = 1) {
            sink.sendMessage("tok", 5L, match { it.contains("U1166-87") && it.contains("Есть сейчас:") }, null, any())
        }
    }

    @Test fun `without a bot nothing goes out`() = runTest {
        coEvery { settings.getTgBackupConfig() } returns TgBackupConfig("", null, "", "")
        val r = repo(this)
        finishScan(r)
        assertEquals(DtcSendOutcome.NO_BOT, r.sendLastScan(DtcFixtures.labels).outcome)
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `a transient failure is one attempt and never reaches the outbox`() = runTest {
        coEvery { sink.sendMessage(any(), any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))
        val r = repo(this)
        finishScan(r)
        val result = r.sendLastScan(DtcFixtures.labels)
        assertEquals(DtcSendResult(DtcSendOutcome.FAILED, TelegramError.NO_NETWORK), result)
        coVerify(exactly = 1) { sink.sendMessage(any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { settings.getTgReportOutbox() }
        coVerify(exactly = 0) { settings.setTgReportOutbox(any()) }
    }
}
