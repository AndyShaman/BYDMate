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
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
    private var now = 1_759_000_000_000L
    private val sent = mutableListOf<String>()

    private val chat = 555L

    @Before fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        settings = SettingsRepository(dao, LocalePreferences(ctx))
        reporter = TelegramReporter(ctx, sink, settings, mockk(relaxed = true), AppStrings(ctx))
        reporter.inputs = { ReportInputs(diParsData(soc = 64), 312.0, null, null, null, null) }
        reporter.clock = { now }
        reporter.language = { "ru" }
    }

    private fun connect() = runBlocking { settings.saveTgBackup("tok", "bot", chat, "Андрей") }

    private fun answer(results: List<Result<Unit>> = emptyList()) {
        val queue = ArrayDeque(results)
        coEvery { sink.sendMessage("tok", any(), any(), "HTML") } answers {
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
        coVerify(exactly = 0) { sink.sendMessage(any(), any(), any(), any()) }
    }

    @Test fun `a sent report goes out as Telegram HTML with the rule name`() = runBlocking {
        connect()
        answer(listOf(Result.success(Unit)))
        assertEquals(TelegramReporter.SendResult.Sent, reporter.sendRuleReport("Где машина", setOf(ReportField.SOC), ""))
        assertEquals(listOf("<b>BYDMate: Где машина</b>\nЗаряд 64%"), sent)
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

    @Test fun `a report sent late gets the recorded time, a fresh one does not`() = runBlocking {
        connect()
        answer()
        reporter.enqueue(entry("old", now - 3 * 60_000L))
        reporter.enqueue(entry("fresh", now - 60_000L))
        reporter.drainOutbox("test")
        val stamp = TelegramReportBuilder.formatTime(now - 3 * 60_000L)
        assertEquals("text old\n<i>(записано в $stamp)</i>", sent[0])
        assertEquals("text fresh", sent[1])
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
        assertEquals("<b>BYDMate: машина выключена в ${TelegramReportBuilder.TIME_PLACEHOLDER}</b>\nЗаряд 64%", report.text)
        assertFalse(report.toString().contains("tok"))
    }

    @Test fun `the dump line shows the switch, the items and the outbox`() = runBlocking {
        settings.setTgReportOffEnabled(true)
        reporter.enqueue(entry("a", now))
        assertEquals(
            "telegram report: off=on fields=[location,soc,range,trip] armed=- daemon=- outbox=1",
            reporter.diagnosticsLines().first(),
        )
    }
}
