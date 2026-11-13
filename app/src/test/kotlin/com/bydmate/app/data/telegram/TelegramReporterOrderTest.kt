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
import com.bydmate.app.data.trips.TripCounterResets
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.domain.cost.CostCalculator
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** A fresh rule report goes behind the reports already waiting in the outbox, never ahead of them. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReporterOrderTest {

    private class FakeSettingsDao : SettingsDao {
        val map = mutableMapOf<String, String>()
        override suspend fun get(key: String): String? = map[key]
        override fun observe(key: String): Flow<String?> = flowOf(map[key])
        override suspend fun getMany(keys: List<String>): List<SettingEntity> =
            keys.mapNotNull { k -> map[k]?.let { SettingEntity(k, it) } }
        override suspend fun set(setting: SettingEntity) { map[setting.key] = setting.value ?: "" }
        override suspend fun setAll(settings: List<SettingEntity>) { settings.forEach { set(it) } }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val dao = FakeSettingsDao()
    private lateinit var settings: SettingsRepository
    private val sink = mockk<TelegramBackupSink>()
    private lateinit var reporter: TelegramReporter
    private val helper = mockk<HelperClient>()
    private val offState = PowerOffArmState(helper)
    private val costCalculator = mockk<CostCalculator> { coEvery { tripRateAt(any()) } returns 0.27 }
    private var now = 1_759_000_000_000L
    private val sent = mutableListOf<String>()

    private val chat = 555L

    /** What the fresh report B looks like for the fake inputs below. */
    private val textB = "<b>BYDMate: R</b>\n\n🔋 Заряд <b>64%</b>"

    @Before fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        settings = SettingsRepository(dao, LocalePreferences(ctx))
        coEvery { helper.offReportStatus(any()) } returns null
        reporter = TelegramReporter(ctx, sink, settings, mockk(relaxed = true), TripCounterResets(settings), AppStrings(ctx), offState, costCalculator)
        reporter.inputs = { ReportInputs(diParsData(soc = 64), 312.0, null, null, null, null) }
        reporter.clock = { now }
        reporter.language = { "ru" }
        runBlocking { settings.saveTgBackup("tok", "bot", chat, "Андрей") }
    }

    private fun answer(results: List<Result<Unit>> = emptyList()) {
        val queue = ArrayDeque(results)
        coEvery { sink.sendMessage("tok", any(), any(), "HTML", false) } answers {
            sent += thirdArg<String>()
            queue.removeFirstOrNull() ?: Result.success(Unit)
        }
    }

    private fun failure(error: TelegramError, code: Int? = null) = Result.failure<Unit>(TelegramSinkException(error, code))

    private fun entry(id: String, created: Long, chatId: Long = chat) =
        OutboxEntry(id, chatId, created, "text $id", lateMark = false)

    /** The texts the outbox holds now, in its order. */
    private suspend fun outboxTexts(): List<String> {
        val raw = settings.getTgReportOutbox()
        if (raw.isBlank()) return emptyList()
        val array = JSONArray(raw)
        return (0 until array.length()).map { array.getJSONObject(it).getString("text") }
    }

    @Test fun `a fresh report goes out after the older one waiting in the outbox`() = runBlocking {
        reporter.enqueue(entry("a", now - 60_000L))
        answer()
        assertEquals(TelegramReporter.SendResult.Sent, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(listOf("text a", textB), sent)
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `no network keeps the fresh report behind the older one and does not send it`() = runBlocking {
        reporter.enqueue(entry("a", now - 60_000L))
        answer(List(5) { failure(TelegramError.NO_NETWORK) })
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(false, sent.contains(textB))
        assertEquals(listOf("text a", textB), outboxTexts())
    }

    @Test fun `the older one goes through but the fresh send fails, the fresh one is queued`() = runBlocking {
        reporter.enqueue(entry("a", now - 60_000L))
        // First call: A succeeds; second call: B fails; any further call keeps failing.
        answer(listOf(Result.success(Unit)) + List(5) { failure(TelegramError.NO_NETWORK) })
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals("text a", sent.first())
        assertEquals(listOf(textB), outboxTexts())
    }

    @Test fun `an empty outbox sends the fresh report directly and writes nothing`() = runBlocking {
        answer()
        assertEquals(TelegramReporter.SendResult.Sent, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(listOf(textB), sent)
        assertEquals("", settings.getTgReportOutbox())
    }

    @Test fun `an older report built for another chat is dropped and the fresh one is sent`() = runBlocking {
        reporter.enqueue(entry("a", now - 60_000L, chatId = 999L))
        answer()
        assertEquals(TelegramReporter.SendResult.Sent, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(listOf(textB), sent)
        assertEquals(0, reporter.outboxSize())
    }
}
