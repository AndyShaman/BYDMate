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
import com.bydmate.app.helper.dtc.DtcFixtures.hex
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.helper.dtc.FirmwareAddressResult
import com.bydmate.app.helper.dtc.FirmwareStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class EcuFirmwareRepositoryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val prefs = context.getSharedPreferences("ecu_firmware", Context.MODE_PRIVATE)

    private val helper = mockk<HelperClient>()
    private val settings = mockk<SettingsRepository>(relaxed = true)
    private val sink = mockk<TelegramBackupSink>()

    private var startReply: Int? = 0
    private var statuses = ArrayDeque<FirmwareStatus?>()
    private var gear: Int? = 1
    private var sampleAgeMs = 1_000L
    private var wall = 1_000_000L
    private var build = "BUILD-X"

    private val f720 = hex("62F1944845001201F00056E0")
    private val f754 = hex("62F19448490011AD0C000004")
    private val old720 = hex("62F1959C4A1A040901")
    private val new720 = hex("62F1959C4C1A090A01")
    private val fw754 = hex("62F1959C4118011401")

    @Before fun setUp() {
        prefs.edit().clear().commit()
        coEvery { helper.firmwareStart(any(), any()) } answers { startReply }
        coEvery { helper.firmwareStatus() } answers {
            statuses.removeFirstOrNull() ?: FirmwareStatus(DtcWire.STATE_RUNNING, 5, 38, "", emptyList())
        }
        coEvery { sink.sendMessage(any(), any(), any(), any(), any()) } returns Result.success(Unit)
        coEvery { settings.getTgBackupConfig() } returns TgBackupConfig("tok", 5L, "bot", "chat")
    }

    private fun done(f720Fw: ByteArray?) = FirmwareStatus(
        DtcWire.STATE_DONE, 2, 2, "",
        listOf(
            FirmwareAddressResult(0x720, DtcWire.OUTCOME_OK, f720, f720Fw),
            FirmwareAddressResult(0x754, DtcWire.OUTCOME_OK, f754, fw754),
        ),
    )

    private fun repo(scope: TestScope) = EcuFirmwareRepository(
        helper = helper,
        prefs = prefs,
        settings = settings,
        sink = sink,
        scope = scope.backgroundScope,
        liveSample = { gear?.let { TimedSnapshot(diParsData().copy(gear = it), 100_000L - sampleAgeMs) } },
        serviceRunning = { true },
        elapsedNow = { 100_000L },
        wallNow = { wall },
        huBuild = { build },
    )

    private suspend fun TestScope.finishCheck(r: EcuFirmwareRepository, status: FirmwareStatus = done(old720)) {
        statuses.addLast(status)
        r.check()
        advanceTimeBy(5_000)
        runCurrent()
    }

    // Start gates

    @Test fun `a check on a fresh P starts the job once with the gear address`() = runTest {
        val r = repo(this)
        r.check()
        runCurrent()
        val gearAddress = FidAddresses.of("gear")
        coVerify(exactly = 1) { helper.firmwareStart(gearAddress.device, gearAddress.fid) }
        assertEquals(FirmwarePhase.CHECKING, r.state.value.phase)
        assertNull(r.state.value.error)
    }

    @Test fun `a check outside P or on an old sample never calls the daemon`() = runTest {
        val r = repo(this)
        gear = 4
        r.check()
        runCurrent()
        assertEquals(DtcScanError.NOT_PARK, r.state.value.error)
        assertEquals(FirmwarePhase.IDLE, r.state.value.phase)

        gear = 1
        sampleAgeMs = 11_000
        r.check()
        runCurrent()
        assertEquals(DtcScanError.NOT_PARK, r.state.value.error)
        coVerify(exactly = 0) { helper.firmwareStart(any(), any()) }
    }

    // Saving

    @Test fun `a finished check is saved with its time and the head unit build`() = runTest {
        val r = repo(this)
        finishCheck(r)
        assertEquals(FirmwarePhase.IDLE, r.state.value.phase)
        assertNull(r.state.value.error)
        val saved = r.state.value.history.single()
        assertEquals(wall, saved.at)
        assertEquals("BUILD-X", saved.huBuild)
        assertEquals(listOf(0x720, 0x754), saved.blocks.map { it.addr })
    }

    @Test fun `the history survives a new repository`() = runTest {
        val r = repo(this)
        finishCheck(r)
        val again = repo(this)
        again.refreshHuState()
        runCurrent()
        val h = again.state.value.history
        assertEquals(1, h.size)
        assertEquals(wall, h.single().at)
        assertEquals("BUILD-X", h.single().huBuild)
        assertEquals(listOf(0x720, 0x754), h.single().blocks.map { it.addr })
    }

    @Test fun `the rows are the newest check against the one before it`() = runTest {
        val r = repo(this)
        finishCheck(r, done(old720))
        assertTrue(r.state.value.rows.all { it.change == FirmwareChange.FIRST })
        wall += 60_000
        finishCheck(r, done(new720))
        assertEquals(2, r.state.value.history.size)
        val rows = r.state.value.rows
        assertEquals(FirmwareChange.UPDATED, rows.first { it.addr == 0x720 }.change)
        assertEquals(FirmwareChange.UNCHANGED, rows.first { it.addr == 0x754 }.change)
    }

    // The app never reads the blocks by itself

    @Test fun `constructing the repository and refreshing the head unit state never touch the daemon`() = runTest {
        val r = repo(this)
        r.refreshHuState()
        build = "BUILD-Y"
        r.refreshHuState()
        runCurrent()
        advanceTimeBy(60_000)
        runCurrent()
        coVerify(exactly = 0) { helper.firmwareStart(any(), any()) }
        coVerify(exactly = 0) { helper.firmwareStatus() }
    }

    // D.8 60-62

    @Test fun `60 - the first refresh stores the build as the baseline and highlights nothing`() = runTest {
        val r = repo(this)
        r.refreshHuState()
        runCurrent()
        assertFalse(r.state.value.huChanged)
        build = "BUILD-Y"
        r.refreshHuState()
        runCurrent()
        assertTrue(r.state.value.huChanged)
    }

    @Test fun `61 - a new build highlights until the next check saves it`() = runTest {
        val r = repo(this)
        finishCheck(r)
        r.refreshHuState()
        runCurrent()
        assertFalse(r.state.value.huChanged)
        build = "BUILD-Y"
        r.refreshHuState()
        runCurrent()
        assertTrue(r.state.value.huChanged)
        wall += 60_000
        finishCheck(r, done(new720))
        r.refreshHuState()
        runCurrent()
        assertFalse(r.state.value.huChanged)
    }

    @Test fun `62 - a blank build never highlights`() = runTest {
        val r = repo(this)
        r.refreshHuState()
        build = ""
        r.refreshHuState()
        runCurrent()
        assertFalse(r.state.value.huChanged)
        finishCheck(r)
        r.refreshHuState()
        runCurrent()
        assertFalse(r.state.value.huChanged)
    }

    // Telegram

    @Test fun `sending without a check fails without touching the network`() = runTest {
        val r = repo(this)
        assertEquals(DtcSendOutcome.FAILED, r.sendLast().outcome)
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `without a bot nothing goes out`() = runTest {
        coEvery { settings.getTgBackupConfig() } returns TgBackupConfig("", null, "", "")
        val r = repo(this)
        finishCheck(r)
        assertEquals(DtcSendOutcome.NO_BOT, r.sendLast().outcome)
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `a failed send is one attempt`() = runTest {
        coEvery { sink.sendMessage(any(), any(), any(), any(), any()) } returns
            Result.failure(TelegramSinkException(TelegramError.NO_NETWORK))
        val r = repo(this)
        finishCheck(r)
        assertEquals(DtcSendResult(DtcSendOutcome.FAILED, TelegramError.NO_NETWORK), r.sendLast())
        coVerify(exactly = 1) { sink.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `the send is one message with the updated block as was to became and an unknown block as its address`() = runTest {
        val r = repo(this)
        finishCheck(r, done(old720))
        wall += 60_000
        finishCheck(r, done(new720))
        val text = slot<String>()
        coEvery { sink.sendMessage("tok", 5L, capture(text), null, any()) } returns Result.success(Unit)
        assertEquals(DtcSendResult(DtcSendOutcome.SENT), r.sendLast())
        coVerify(exactly = 1) { sink.sendMessage(any(), any(), any(), any(), any()) }
        val msg = text.captured
        assertTrue("len=${msg.length}", msg.length <= 4096)
        assertTrue(msg, Regex("4\\.00\\.10.*→.*4\\.00\\.12").containsMatchIn(msg))
        assertTrue(msg, msg.contains("754"))
        assertFalse(msg, msg.contains("unknown", ignoreCase = true))
        assertFalse(msg, msg.contains("не найден", ignoreCase = true))
    }

    // C13: a run that read no firmware at all is not a check

    /** DONE, but no address gave a parseable F195: silent, or a negative answer. */
    private fun noFirmware() = FirmwareStatus(
        DtcWire.STATE_DONE, 2, 2, "",
        listOf(
            FirmwareAddressResult(0x720, DtcWire.OUTCOME_NRC, hex("7F2231"), hex("7F2231")),
            FirmwareAddressResult(0x754, DtcWire.OUTCOME_NO_ANSWER, null, null),
        ),
    )

    @Test fun `C13 a run with no parseable F195 is not saved as a check and shows an error`() = runTest {
        val r = repo(this)
        finishCheck(r, noFirmware())
        assertTrue(r.state.value.history.isEmpty())
        assertEquals(FirmwarePhase.IDLE, r.state.value.phase)
        assertNotNull(r.state.value.error)
        assertTrue(repo(this).state.value.history.isEmpty())
    }

    @Test fun `C13 a run with no parseable F195 keeps the earlier checks and the head unit highlight`() = runTest {
        val r = repo(this)
        finishCheck(r, done(old720))
        build = "BUILD-Y"
        r.refreshHuState()
        runCurrent()
        assertTrue(r.state.value.huChanged)
        wall += 60_000
        finishCheck(r, noFirmware())
        assertEquals(1, r.state.value.history.size)
        assertEquals(1_000_000L, r.state.value.history.single().at)
        assertNotNull(r.state.value.error)
        assertTrue(r.state.value.huChanged)
    }

    @Test fun `C13 one parseable F195 among silent blocks is still a check`() = runTest {
        val r = repo(this)
        finishCheck(
            r,
            FirmwareStatus(
                DtcWire.STATE_DONE, 2, 2, "",
                listOf(
                    FirmwareAddressResult(0x720, DtcWire.OUTCOME_OK, f720, old720),
                    FirmwareAddressResult(0x754, DtcWire.OUTCOME_NO_ANSWER, null, null),
                ),
            ),
        )
        assertEquals(1, r.state.value.history.size)
        assertNull(r.state.value.error)
    }

    // Review round 2

    private val bareF195 = hex("62F195")

    @Test fun `D7 a bare 62 F1 95 gives no version and the run is not saved as a check`() = runTest {
        val r = repo(this)
        finishCheck(
            r,
            FirmwareStatus(
                DtcWire.STATE_DONE, 2, 2, "",
                listOf(
                    FirmwareAddressResult(0x720, DtcWire.OUTCOME_OK, f720, bareF195),
                    FirmwareAddressResult(0x754, DtcWire.OUTCOME_NO_ANSWER, null, null),
                ),
            ),
        )
        assertTrue(r.state.value.history.isEmpty())
        assertNotNull(r.state.value.error)
    }

    @Test fun `D7 a bare 62 F1 95 keeps the earlier checks and the head unit highlight`() = runTest {
        val r = repo(this)
        finishCheck(r, done(old720))
        build = "BUILD-Y"
        r.refreshHuState()
        runCurrent()
        assertTrue(r.state.value.huChanged)
        wall += 60_000
        finishCheck(
            r,
            FirmwareStatus(DtcWire.STATE_DONE, 2, 2, "", listOf(FirmwareAddressResult(0x720, DtcWire.OUTCOME_OK, f720, bareF195))),
        )
        assertEquals(1, r.state.value.history.size)
        assertTrue(r.state.value.huChanged)
    }

    @Test fun `D7 a bare 62 F1 95 next to one real version is still a check`() = runTest {
        val r = repo(this)
        finishCheck(
            r,
            FirmwareStatus(
                DtcWire.STATE_DONE, 2, 2, "",
                listOf(
                    FirmwareAddressResult(0x720, DtcWire.OUTCOME_OK, f720, bareF195),
                    FirmwareAddressResult(0x754, DtcWire.OUTCOME_OK, f754, fw754),
                ),
            ),
        )
        assertEquals(1, r.state.value.history.size)
        assertNull(r.state.value.error)
    }

    @Test fun `D9 the dump prints version and date of every address for the newest two checks only`() = runTest {
        val r = repo(this)
        build = "BUILD-1"
        finishCheck(r, done(hex("62F1959C481A010101")))
        wall += 60_000
        build = "BUILD-2"
        finishCheck(r, done(old720))
        wall += 60_000
        build = "BUILD-3"
        finishCheck(r, done(new720))
        val dump = EcuFirmwareRepository.dumpLines(prefs).joinToString("\n")
        // check 3 and check 2 of 720
        for (needle in listOf("4.00.12", "2026-09-10", "4.00.10", "2026-04-09", "BUILD-3", "BUILD-2")) {
            assertTrue("$needle in:\n$dump", dump.contains(needle))
        }
        // 754 answered the same in all three checks: version and date appear for two of them
        assertEquals(dump, 2, Regex("4\\.00\\.01").findAll(dump).count())
        assertEquals(dump, 2, Regex("2024-01-20").findAll(dump).count())
        // the oldest check keeps only its summary line
        assertFalse(dump, dump.contains("4.00.08"))
        assertFalse(dump, dump.contains("2026-01-01"))
    }

    @Test fun `guard_readonly maps to READ_ONLY_FIRMWARE and guard_absent stays NO_DIAG_SERVICE`() = runTest {
        val r = repo(this)
        statuses.addLast(FirmwareStatus(DtcWire.STATE_UNAVAILABLE, 0, 0, "guard_readonly", emptyList()))
        r.check()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(FirmwarePhase.IDLE, r.state.value.phase)
        assertEquals(DtcScanError.READ_ONLY_FIRMWARE, r.state.value.error)

        statuses.addLast(FirmwareStatus(DtcWire.STATE_UNAVAILABLE, 0, 0, "guard_absent", emptyList()))
        r.check()
        advanceTimeBy(5_000)
        runCurrent()
        assertEquals(DtcScanError.NO_DIAG_SERVICE, r.state.value.error)
    }
}
