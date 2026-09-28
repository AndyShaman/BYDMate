package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramError
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.offreport.OffReportFid
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Sending a report, the outbox on disk and the power-off seam, against a fake bot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReporterTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override suspend fun set(setting: SettingEntity) { map[setting.key] = setting.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
        override fun getAll(): Flow<List<SettingEntity>> = flowOf(emptyList())
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val dao = FakeSettingsDao()
    private lateinit var settings: SettingsRepository
    private val sink = mockk<TelegramBackupSink>()
    private lateinit var reporter: TelegramReporter
    private val helper = mockk<HelperClient>()
    private val offState = PowerOffArmState(helper)
    private var now = 1_759_000_000_000L
    private val sent = mutableListOf<String>()

    private val chat = 555L

    @Before fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        settings = SettingsRepository(dao, LocalePreferences(ctx))
        coEvery { helper.offReportStatus(any()) } returns null
        reporter = TelegramReporter(ctx, sink, settings, mockk(relaxed = true), AppStrings(ctx), offState)
        reporter.inputs = { ReportInputs(diParsData(soc = 64), 312.0, null, null, null, null) }
        reporter.clock = { now }
        reporter.language = { "ru" }
    }

    private fun connect() = runBlocking { settings.saveTgBackup("tok", "bot", chat, "Андрей") }

    private fun answer(results: List<Result<Unit>> = emptyList()) {
        val queue = ArrayDeque(results)
        coEvery { sink.sendMessage("tok", any(), any(), "HTML", false) } answers {
            sent += thirdArg<String>()
            queue.removeFirstOrNull() ?: Result.success(Unit)
        }
    }

    private fun failure(error: TelegramError, code: Int? = null) = Result.failure<Unit>(TelegramSinkException(error, code))

    private fun entry(id: String, created: Long, chatId: Long = chat, late: Boolean = true) =
        OutboxEntry(id, chatId, created, "text $id", late)

    @Test fun `no bot connected sends nothing`() = runBlocking {
        answer()
        assertEquals(TelegramReporter.SendResult.NotConnected, reporter.sendRuleReport("Где машина", ReportField.DEFAULT, ""))
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any(), any(), any()) }
    }

    @Test fun `a sent report goes out as Telegram HTML without a link preview, with the rule name`() = runBlocking {
        connect()
        answer(listOf(Result.success(Unit)))
        assertEquals(TelegramReporter.SendResult.Sent, reporter.sendRuleReport("Где машина", setOf(ReportField.SOC), ""))
        assertEquals(listOf("<b>BYDMate: Где машина</b>\n\n🔋 Заряд <b>64%</b>"), sent)
        coVerify(exactly = 1) { sink.sendMessage("tok", chat, any(), "HTML", false) }
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `no network puts the report in the outbox`() = runBlocking {
        connect()
        answer(listOf(failure(TelegramError.NO_NETWORK)))
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(1, reporter.outboxSize())
    }

    @Test fun `rate limit and server errors are temporary too`() = runBlocking {
        connect()
        answer(listOf(failure(TelegramError.HTTP, 429), failure(TelegramError.HTTP, 502)))
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(2, reporter.outboxSize())
    }

    @Test fun `a gone bot fails the step and is not queued`() = runBlocking {
        connect()
        answer(listOf(failure(TelegramError.BAD_TOKEN, 401)))
        assertEquals(TelegramReporter.SendResult.Failed("BAD_TOKEN"), reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `the outbox keeps the ten newest reports`() = runBlocking {
        (1..12).forEach { reporter.enqueue(entry("r$it", now + it)) }
        assertEquals(TelegramReporter.OUTBOX_MAX, reporter.outboxSize())
        connect()
        answer()
        reporter.drainOutbox("test")
        assertEquals((3..12).map { "text r$it" }, sent)
    }

    @Test fun `a report already in the outbox is not queued a second time`() = runBlocking {
        reporter.enqueue(entry("a", now))
        reporter.enqueue(OutboxEntry("a", chat, now + 5, "other text"))
        assertEquals(1, reporter.outboxSize())
        connect()
        answer()
        reporter.drainOutbox("test")
        assertEquals(listOf("text a"), sent)
    }

    @Test fun `a report sent late gets the recorded time, a fresh one does not`() = runBlocking {
        connect()
        answer()
        reporter.enqueue(entry("old", now - 3 * 60_000L))
        reporter.enqueue(entry("fresh", now - 60_000L))
        reporter.drainOutbox("test")
        val stamp = TelegramReportBuilder.formatTime(now - 3 * 60_000L)
        assertEquals("text old\n\n<i>(записано в $stamp)</i>", sent[0])
        assertEquals("text fresh", sent[1])
    }

    @Test fun `a late report keeps the map link last, the mark goes right above it`() = runBlocking {
        connect()
        answer()
        val map = "📍 <a href=\"https://yandex.ru/maps/?pt=1&amp;z=16\">Открыть на карте</a>"
        reporter.enqueue(OutboxEntry("m", chat, now - 3 * 60_000L, "<b>h</b>\n\n🔋 Заряд <b>64%</b>\n\n$map"))
        reporter.drainOutbox("test")
        val stamp = TelegramReportBuilder.formatTime(now - 3 * 60_000L)
        assertEquals("<b>h</b>\n\n🔋 Заряд <b>64%</b>\n\n<i>(записано в $stamp)</i>\n\n$map", sent.single())
    }

    @Test fun `an outbox entry saved by 3_19_0 still goes out as Telegram HTML`() = runBlocking {
        settings.setTgReportOutbox("""[{"id":"old1","chat":$chat,"created":$now,"text":"<b>BYDMate: A</b>\nЗаряд 64%","late":true}]""")
        connect()
        answer()
        reporter.drainOutbox("test")
        assertEquals(listOf("<b>BYDMate: A</b>\nЗаряд 64%"), sent)
        coVerify(exactly = 1) { sink.sendMessage("tok", chat, any(), "HTML", false) }
    }

    @Test fun `a power-off report is never marked late, its header has the time`() = runBlocking {
        connect()
        answer()
        reporter.enqueue(entry("off", now - 60 * 60_000L, late = false))
        reporter.drainOutbox("test")
        assertEquals(listOf("text off"), sent)
    }

    @Test fun `a temporary failure stops the drain and keeps the rest`() = runBlocking {
        connect()
        answer(listOf(Result.success(Unit), failure(TelegramError.NO_NETWORK)))
        (1..3).forEach { reporter.enqueue(entry("r$it", now + it)) }
        reporter.drainOutbox("test")
        assertEquals(2, sent.size)
        assertEquals(2, reporter.outboxSize())
    }

    @Test fun `a permanent failure drops that report and goes on`() = runBlocking {
        connect()
        answer(listOf(failure(TelegramError.NO_CHAT, 400), Result.success(Unit)))
        (1..2).forEach { reporter.enqueue(entry("r$it", now + it)) }
        reporter.drainOutbox("test")
        assertEquals(0, reporter.outboxSize())
        assertEquals(2, sent.size)
    }

    @Test fun `reports built for another chat or with the bot gone are dropped unsent`() = runBlocking {
        answer()
        reporter.enqueue(entry("a", now))
        reporter.drainOutbox("test")
        assertEquals(0, reporter.outboxSize())
        connect()
        reporter.enqueue(entry("b", now, chatId = 777L))
        reporter.drainOutbox("test")
        assertEquals(0, reporter.outboxSize())
        assertTrue(sent.isEmpty())
    }

    @Test fun `a successful send also drains what waited`() = runBlocking {
        connect()
        answer()
        reporter.enqueue(entry("waiting", now))
        reporter.sendRuleReport("R", setOf(ReportField.SOC), "")
        assertEquals(2, sent.size)
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `the power-off report is armed only when switched on with a bot`() = runBlocking {
        assertNull(reporter.powerOffReport())
        settings.setTgReportOffEnabled(true)
        assertNull(reporter.powerOffReport())
        connect()
        settings.setTgReportOffFields(setOf(ReportField.SOC))
        val report = reporter.powerOffReport()!!
        assertEquals("tok", report.token)
        assertEquals(chat, report.chatId)
        assertEquals("<b>BYDMate: машина выключена в ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>\n\n🔋 Заряд <b>64%</b>", report.text)
        assertEquals("<i>(записано в ${TelegramReportBuilder.TIME_PLACEHOLDER})</i>", report.lateMark)
        assertFalse(report.toString().contains("tok"))
    }

    @Test fun `an unchanged power-off report comes back as the same one, a change makes a new id`() = runBlocking {
        settings.setTgReportOffEnabled(true)
        connect()
        settings.setTgReportOffFields(setOf(ReportField.SOC))
        val first = reporter.powerOffReport()!!
        assertTrue(first === reporter.powerOffReport(first))
        reporter.inputs = { ReportInputs(diParsData(soc = 63), 312.0, null, null, null, null) }
        val second = reporter.powerOffReport(first)!!
        assertFalse(second.id == first.id)
        assertTrue(second.text.endsWith("<b>63%</b>"))
    }

    @Test fun `the power-off report carries the odometer by the same rules`() = runBlocking {
        settings.setTgReportOffEnabled(true)
        connect()
        assertTrue("a fresh install has it on", "odometer" in settings.getTgReportOffFields().map { it.id })
        val header = "<b>BYDMate: машина выключена в ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>"
        reporter.inputs = { ReportInputs(diParsData(soc = 64, mileage = 23_456.7), 312.0, null, null, null, null) }
        settings.setTgReportOffFields(setOf(ReportField.SOC, ReportField.fromId("odometer")!!))
        assertEquals("$header\n\n🔋 Заряд <b>64%</b>\n🧭 Пробег 23\u00A0456 км", reporter.powerOffReport()!!.text)
        reporter.inputs = { ReportInputs(diParsData(soc = 64, mileage = null), 312.0, null, null, null, null) }
        assertEquals("$header\n\n🔋 Заряд <b>64%</b>", reporter.powerOffReport()!!.text)
    }

    @Test fun `a drain that runs while a send is in flight still catches the report once it fails`() = runBlocking {
        connect()
        val proceed = CompletableDeferred<Unit>()
        var callCount = 0
        coEvery { sink.sendMessage("tok", any(), any(), "HTML", false) } coAnswers {
            callCount++
            sent += thirdArg<String>()
            if (callCount == 1) {
                proceed.await()
                failure(TelegramError.NO_NETWORK)
            } else {
                Result.success(Unit)
            }
        }
        var result: TelegramReporter.SendResult? = null
        val job = launch(Dispatchers.Unconfined) {
            result = reporter.sendRuleReport("R", setOf(ReportField.SOC), "")
        }
        // The send is parked on `proceed`; a network-regain drain runs concurrently and finds
        // nothing queued yet, since the failing report is only enqueued once the send returns.
        reporter.drainOutbox("network")
        assertEquals(0, reporter.outboxSize())
        proceed.complete(Unit)
        job.join()
        assertEquals(TelegramReporter.SendResult.Queued, result)
        assertEquals(0, reporter.outboxSize())
        assertEquals(2, sent.size)
    }

    @Test fun `a slow failing send is marked late from when it was built, not when it failed`() = runBlocking {
        connect()
        val buildMs = now
        coEvery { sink.sendMessage("tok", any(), any(), "HTML", false) } coAnswers {
            sent += thirdArg<String>()
            now += 3 * 60_000L // the network attempt takes its time before failing
            failure(TelegramError.NO_NETWORK)
        }
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        answer(listOf(Result.success(Unit)))
        reporter.drainOutbox("test")
        val stamp = TelegramReportBuilder.formatTime(buildMs)
        assertEquals(2, sent.size)
        assertTrue(sent[1], sent[1].endsWith("<i>(записано в $stamp)</i>"))
    }

    @Test fun `a cancelled send propagates and leaves the outbox untouched`() = runBlocking {
        connect()
        reporter.enqueue(entry("existing", now))
        coEvery { sink.sendMessage("tok", any(), any(), "HTML", false) } throws CancellationException("job cancelled")
        var caught = false
        try {
            reporter.sendRuleReport("R", setOf(ReportField.SOC), "")
        } catch (expected: CancellationException) {
            caught = true
        }
        assertTrue(caught)
        assertEquals(1, reporter.outboxSize())
    }

    @Test fun `the dump line shows the switch, the items and the outbox`() = runBlocking {
        settings.setTgReportOffEnabled(true)
        reporter.enqueue(entry("a", now))
        assertEquals(
            "telegram report: off=on fields=[location,soc,range,odometer,trip] armed=- daemon=- pending=- outbox=1",
            reporter.diagnosticsLines().first(),
        )
    }

    @Test fun `the dump shows the armed age, the daemon and what the daemon says`() = runBlocking {
        offState.armedAtMs = now - 12_000L
        offState.daemon = PowerOffArmState.DAEMON_OK
        val last = OffReportOutcome("a1", OffReportState.SENT, now, now + 1_400L, 2, "200")
        val fids = listOf(OffReportFid(1001, 315621418, "OK"), OffReportFid(1023, 315621408, "pending"))
        coEvery { helper.offReportStatus("") } returns
            OffReportStatus(OffReportOutcome("", OffReportState.UNKNOWN), "a2", now, 1, last, fids, pending = 3)
        val lines = reporter.diagnosticsLines()
        assertEquals(
            "telegram report: off=off fields=[location,soc,range,odometer,trip] armed=12s daemon=ok pending=3 outbox=0",
            lines[0],
        )
        assertEquals(
            "telegram report last_off: sent id=a1 off=${TelegramReportBuilder.formatDateTime(now)} " +
                "attempts=2 rc=200 sent_after=1400ms",
            lines[1],
        )
        assertEquals("telegram report listener: 315621418=OK 315621408=pending", lines[2])
    }

    @Test fun `the dump says so when the daemon does not answer`() = runBlocking {
        val lines = reporter.diagnosticsLines()
        assertTrue(lines[0].endsWith("daemon=- pending=- outbox=0"))
        assertEquals("telegram report last_off: -", lines[1])
        assertEquals("telegram report listener: -", lines[2])
    }
}
