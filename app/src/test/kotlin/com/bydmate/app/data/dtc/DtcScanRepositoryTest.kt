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
import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcEraseResult
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

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
        assertEquals(9, s.old)
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

    @Test fun `a guard_absent reason is its own error, other unavailable reasons are not`() = runTest {
        val r = repo(this)
        statuses.addLast(ended(DtcWire.STATE_UNAVAILABLE, "guard_absent"))
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(DtcScanError.NO_DIAG_SERVICE, r.state.value.error)

        statuses.addLast(ended(DtcWire.STATE_UNAVAILABLE, "no_frames"))
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(DtcScanError.UNAVAILABLE, r.state.value.error)
    }

    @Test fun `erase with a scan older than ten minutes does not call the daemon`() = runTest {
        val r = repo(this)
        finishScan(r)
        wall += 11 * 60_000L
        r.startErase(intArrayOf(0x7E0, 0x7E2))
        runCurrent()
        coVerify(exactly = 0) { helper.dtcEraseStart(any(), any(), any(), any(), any()) }
        assertEquals(DtcScanError.ERASE_NEEDS_SCAN, r.erase.value?.error)
    }

    @Test fun `erase logs the visible codes first, then starts the daemon with the requested blocks`() = runTest {
        ShadowLog.clear()
        var beforeAtCall = -1
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } answers {
            beforeAtCall = ShadowLog.getLogs().count { it.msg.startsWith("dtc erase before") }
            0
        }
        val r = repo(this)
        finishScan(r)
        val requested = intArrayOf(0x7E0, 0x7E2)
        r.startErase(requested)
        runCurrent()
        assertEquals(1, beforeAtCall)
        val blocks = ShadowLog.getLogs().filter { it.msg.startsWith("dtc erase before") }
        assertEquals(1, blocks.size)
        for (f in DtcParser.summarize(DtcFixtures.preClearScan()).found) {
            assertTrue(f.row.code, blocks.single().msg.contains(f.row.code))
        }
        assertTrue(blocks.single().msg.contains("U1166-87"))
        coVerify(exactly = 1) {
            helper.dtcEraseStart(any(), any(), any(), any(), match { it.contentEquals(requested) })
        }
    }

    private fun zeroReread(addr: Int) =
        DtcAddressResult(addr, DtcWire.OUTCOME_OK, byteArrayOf(0x59, 0x01, 0xFF.toByte(), 0, 0, 0), null, null)

    private fun eraseDone(vararg erase: DtcEraseResult) = DtcScanStatus(
        DtcWire.STATE_DONE, erase.size, erase.size, "", DtcFixtures.preClearScan().take(2), erase.toList(), DtcWire.MODE_ERASE,
    )

    private fun confirmed(addr: Int, reread: DtcAddressResult?) =
        DtcEraseResult(addr, DtcWire.CONFIRM_CONFIRMED, null, "", reread)

    private fun rowAddrs(scan: DtcSavedScan) = DtcParser.summarize(scan.results).found.map { it.addr }

    @Test fun `an erase run never replaces the whole saved scan`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        finishScan(r)
        val saved = r.last.value!!
        wall += 60_000
        statuses.addLast(eraseDone(confirmed(0x7E0, zeroReread(0x7E0))))
        r.startErase(intArrayOf(0x7E0, 0x7E2))
        advanceTimeBy(5_000)
        runCurrent()
        val now = r.last.value!!
        assertEquals(saved.at, now.at)
        assertEquals(saved.results.size, now.results.size)
        saved.results.zip(now.results).filter { it.first.addr != 0x7E0 }.forEach { (before, after) ->
            assertSame(before, after)
        }
        assertEquals(rowAddrs(saved).filter { it != 0x7E0 }, rowAddrs(now))
        assertEquals(1, r.erase.value?.results?.size)
    }

    @Test fun `blocks erased and re-read empty disappear from the saved scan and from the prefs`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        finishScan(r)
        val saved = r.last.value!!
        wall += 60_000
        statuses.addLast(eraseDone(confirmed(0x7E0, zeroReread(0x7E0)), confirmed(0x7E2, zeroReread(0x7E2))))
        r.startErase(intArrayOf(0x7E0, 0x7E2))
        advanceTimeBy(5_000)
        runCurrent()
        val expected = rowAddrs(saved).filter { it != 0x7E0 && it != 0x7E2 }
        assertTrue(rowAddrs(saved).size > expected.size)
        assertEquals(expected, rowAddrs(r.last.value!!))
        assertEquals(saved.at, r.last.value!!.at)
        val reloaded = repo(this).last.value!!
        assertEquals(expected, rowAddrs(reloaded))
        assertEquals(saved.at, reloaded.at)
    }

    @Test fun `after an erase another block can be erased from the same scan`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        finishScan(r)
        wall += 60_000
        statuses.addLast(eraseDone(confirmed(0x7E0, zeroReread(0x7E0)), confirmed(0x7E2, zeroReread(0x7E2))))
        r.startErase(intArrayOf(0x7E0, 0x7E2))
        advanceTimeBy(5_000)
        runCurrent()
        wall += 60_000
        r.startErase(intArrayOf(0x712))
        runCurrent()
        coVerify(exactly = 1) {
            helper.dtcEraseStart(any(), any(), any(), any(), match { it.contentEquals(intArrayOf(0x712)) })
        }
        assertNotEquals(DtcScanError.ERASE_NEEDS_SCAN, r.erase.value?.error)
    }

    @Test fun `a block without a re-read keeps its rows in the saved scan`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        finishScan(r)
        val saved = r.last.value!!
        wall += 60_000
        statuses.addLast(eraseDone(confirmed(0x7E0, null), confirmed(0x7E2, zeroReread(0x7E2))))
        r.startErase(intArrayOf(0x7E0, 0x7E2))
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(rowAddrs(saved).filter { it != 0x7E2 }, rowAddrs(r.last.value!!))
    }

    @Test fun `an erase start that gets no reply is a result unknown and marks the scan erased`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns null
        val r = repo(this)
        finishScan(r)
        r.startErase(intArrayOf(0x7E0, 0x7E2))
        runCurrent()
        assertEquals(DtcErasePhase.ENDED, r.erase.value?.phase)
        // The code's «Результат стирания неизвестен» is STATUS_LOST, not a plain failure.
        assertEquals(DtcScanError.STATUS_LOST, r.erase.value?.error)
        assertNotNull(r.erasedAt.value)
    }

    @Test fun `a refused or busy erase start stays a plain failure and does not mark erased`() = runTest {
        for (reply in listOf(-1, 1)) {
            coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns reply
            val r = repo(this)
            finishScan(r)
            r.startErase(intArrayOf(0x7E0, 0x7E2))
            runCurrent()
            assertEquals(DtcScanError.ABORTED, r.erase.value?.error)
            assertNull(r.erasedAt.value)
        }
    }

    // Blocks with distinct codes so no two merge into one group.
    private fun rowsOn(addr: Int, list: String) =
        DtcAddressResult(addr, DtcWire.OUTCOME_OK, DtcFixtures.hex("5901FF010001"), DtcFixtures.hex(list), null)

    private val lockScan = listOf(
        rowsOn(0x712, "5902FF D16687 08"),
        rowsOn(0x720, "5902FF D16787 08"),
        rowsOn(0x726, "5902FF D16887 08"),
        rowsOn(0x782, "5902FF D16987 08"),
    )

    private fun lockScanDone() = DtcScanStatus(DtcWire.STATE_DONE, 4, 4, "", lockScan)

    private fun unconfirmed(addr: Int) = DtcEraseResult(addr, DtcWire.CONFIRM_UNCONFIRMED, null, "", null)

    private fun notRun(addr: Int) = DtcEraseResult(addr, DtcWire.CONFIRM_NOT_RUN, null, "", null)

    private fun lockEraseDone(vararg erase: DtcEraseResult) =
        DtcScanStatus(DtcWire.STATE_DONE, erase.size, erase.size, "", lockScan, erase.toList(), DtcWire.MODE_ERASE)

    /** Scan, then erase all four: 0x712 confirmed without re-read, 0x726 unconfirmed, 0x720 and 0x782 not run. */
    private suspend fun TestScope.eraseWithUnknowns(r: DtcScanRepository) {
        statuses.addLast(lockScanDone())
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        wall += 60_000
        statuses.addLast(
            lockEraseDone(confirmed(0x712, null), notRun(0x720), unconfirmed(0x726), notRun(0x782)),
        )
        r.startErase(intArrayOf(0x712, 0x720, 0x726, 0x782))
        advanceTimeBy(5_000)
        runCurrent()
    }

    @Test fun `E5 a block of unknown result is locked and refused, an unlocked one still goes`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        eraseWithUnknowns(r)
        assertEquals(setOf(0x712, 0x726), r.last.value!!.eraseLocked)
        wall += 60_000
        r.startErase(intArrayOf(0x726))
        runCurrent()
        coVerify(exactly = 1) { helper.dtcEraseStart(any(), any(), any(), any(), any()) }
        assertEquals(DtcScanError.ERASE_NEEDS_SCAN, r.erase.value?.error)
        r.startErase(intArrayOf(0x782))
        runCurrent()
        coVerify(exactly = 1) {
            helper.dtcEraseStart(any(), any(), any(), any(), match { it.contentEquals(intArrayOf(0x782)) })
        }
    }

    @Test fun `E6 the locks survive a new repository on the same prefs`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        eraseWithUnknowns(repo(this))
        assertEquals(setOf(0x712, 0x726), repo(this).last.value!!.eraseLocked)
    }

    @Test fun `E7 a new scan clears the locks and the block can be erased again`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        eraseWithUnknowns(r)
        wall += 60_000
        statuses.addLast(lockScanDone())
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(r.last.value!!.eraseLocked.isEmpty())
        r.startErase(intArrayOf(0x726))
        runCurrent()
        coVerify(exactly = 1) {
            helper.dtcEraseStart(any(), any(), any(), any(), match { it.contentEquals(intArrayOf(0x726)) })
        }
    }

    @Test fun `E8 an erase where every block is erased leaves no lock`() = runTest {
        coEvery { helper.dtcEraseStart(any(), any(), any(), any(), any()) } returns 0
        val r = repo(this)
        statuses.addLast(lockScanDone())
        r.start()
        advanceTimeBy(5_000)
        runCurrent()
        wall += 60_000
        statuses.addLast(lockEraseDone(*lockScan.map { confirmed(it.addr, zeroReread(it.addr)) }.toTypedArray()))
        r.startErase(intArrayOf(0x712, 0x720, 0x726, 0x782))
        advanceTimeBy(5_000)
        runCurrent()
        assertTrue(r.last.value!!.eraseLocked.isEmpty())
    }
}
