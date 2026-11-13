package com.bydmate.app.data.telegram

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.SettingsDao
import com.bydmate.app.data.local.entity.SettingEntity
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.trips.TripCounterResets
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.domain.cost.CostCalculator
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import com.bydmate.app.helper.offreport.OffReport
import org.junit.Assert.assertTrue

/**
 * The app waits for the daemon's undelivered power-off reports before it sends anything (#413),
 * at most [TelegramReporter.POWER_OFF_FIRST_CAP_MS] per episode; an episode starts at process
 * start and at every `drainOutbox("network")`. New API: `PowerOffArmState.bootstrapAttempted`
 * and `TelegramReporter.POWER_OFF_FIRST_CAP_MS`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReporterHoldTest {

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
    private var now = System.currentTimeMillis()
    private val sent = mutableListOf<String>()
    private val chat = 555L

    private fun status(pending: Int) =
        OffReportStatus(OffReportOutcome("", OffReportState.UNKNOWN), "a", now, 1, null, pending = pending)

    @Before fun setUp() {
        LocalePreferences(ctx).setLanguage("ru")
        settings = SettingsRepository(dao, LocalePreferences(ctx))
        reporter = TelegramReporter(ctx, sink, settings, mockk(relaxed = true), TripCounterResets(settings), AppStrings(ctx), offState, costCalculator)
        reporter.inputs = { ReportInputs(diParsData(soc = 64), 312.0, null, null, null, null) }
        reporter.clock = { now }
        reporter.language = { "ru" }
        coEvery { sink.sendMessage("tok", any(), any(), "HTML", false) } answers {
            sent += thirdArg<String>()
            Result.success(Unit)
        }
    }

    private fun connect() = runBlocking { settings.saveTgBackup("tok", "bot", chat, "Андрей") }

    private fun powerOffReport(on: Boolean) {
        dao.map[SettingsRepository.KEY_TG_REPORT_OFF_ENABLED] = on.toString()
    }

    private fun entry(id: String) = OutboxEntry(id, chat, now - 60_000L, "text $id", lateMark = false)

    private val cap = TelegramReporter.POWER_OFF_FIRST_CAP_MS
    private var pending = 1

    private fun daemon(p: Int) {
        pending = p
        coEvery { helper.offReportStatus(any()) } answers { status(pending) }
    }

    private suspend fun connectedWithOutbox() {
        connect()
        powerOffReport(true)
        reporter.enqueue(entry("a"))
    }

    @Test fun `H1 pending reports on the daemon, the drain sends nothing`() = runBlocking {
        connectedWithOutbox()
        offState.bootstrapAttempted = true
        daemon(1)
        reporter.drainOutbox("service_start")
        assertEquals(emptyList<String>(), sent)
        assertEquals(1, reporter.outboxSize())
    }

    @Test fun `H2 the daemon finishes, the next drain sends the held report`() = runBlocking {
        connectedWithOutbox()
        offState.bootstrapAttempted = true
        daemon(1)
        reporter.drainOutbox("service_start")
        daemon(0)
        reporter.drainOutbox("armer_tick")
        assertEquals(listOf("text a"), sent)
        assertEquals(0, reporter.outboxSize())
    }

    @Test fun `H3 after the cap the report goes out and the hold does not return in the episode`() = runBlocking {
        connectedWithOutbox()
        offState.bootstrapAttempted = true
        daemon(1)
        reporter.drainOutbox("service_start")
        now += cap + 1_000L
        reporter.drainOutbox("armer_tick")
        assertEquals(listOf("text a"), sent)
        reporter.enqueue(entry("b"))
        reporter.drainOutbox("armer_tick")
        assertEquals(listOf("text a", "text b"), sent)
    }

    @Test fun `H4 a fresh report during the hold is queued behind the older one and goes second`() = runBlocking {
        connectedWithOutbox()
        offState.bootstrapAttempted = true
        daemon(1)
        assertEquals(TelegramReporter.SendResult.Queued, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(emptyList<String>(), sent)
        assertEquals(2, reporter.outboxSize())
        daemon(0)
        reporter.drainOutbox("armer_tick")
        assertEquals(2, sent.size)
        assertEquals("text a", sent[0])
        assertTrue(sent[1].contains("64%"))
    }

    @Test fun `H7 bootstrap not attempted yet, held while the daemon is unknown`() = runBlocking {
        connectedWithOutbox()
        daemon(0)
        reporter.drainOutbox("service_start")
        assertEquals(emptyList<String>(), sent)
        offState.bootstrapAttempted = true
        reporter.drainOutbox("armer_tick")
        assertEquals(listOf("text a"), sent)
    }

    @Test fun `H7 bootstrap never attempted, the cap releases the hold`() = runBlocking {
        connectedWithOutbox()
        daemon(0)
        reporter.drainOutbox("service_start")
        now += cap + 1_000L
        reporter.drainOutbox("service_start")
        assertEquals(listOf("text a"), sent)
    }

    @Test fun `H8 a network drain after the cap starts a new episode and holds again`() = runBlocking {
        connectedWithOutbox()
        offState.bootstrapAttempted = true
        daemon(1)
        reporter.drainOutbox("service_start")
        now += cap + 1_000L
        reporter.drainOutbox("armer_tick")
        assertEquals(listOf("text a"), sent)

        reporter.enqueue(entry("b"))
        reporter.drainOutbox("network")
        assertEquals(listOf("text a"), sent)
        now += cap + 1_000L
        reporter.drainOutbox("armer_tick")
        assertEquals(listOf("text a", "text b"), sent)
    }

    @Test fun `I1 the hold cap outlasts the daemon's longest backoff plus one armer tick`() {
        assertEquals(5 * 60_000L, TelegramReporter.POWER_OFF_FIRST_CAP_MS)
        assertTrue(TelegramReporter.POWER_OFF_FIRST_CAP_MS > OffReport.PENDING_BACKOFF_MAX_MS + PowerOffArmer.REFRESH_MS)
    }

    @Test fun `the bootstrap flag is false at start`() {
        assertEquals(false, PowerOffArmState(helper).bootstrapAttempted)
    }
}
