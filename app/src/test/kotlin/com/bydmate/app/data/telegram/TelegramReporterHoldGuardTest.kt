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

/**
 * Cases where the hold for the daemon's power-off reports (#413) must never apply: the behaviour is
 * the same before and after it. Compiles on code without the hold.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class TelegramReporterHoldGuardTest {

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

    /** Marks the helper bootstrap as attempted; reflection keeps this file compiling before the flag exists. */
    private fun bootstrapAttempted() {
        runCatching {
            PowerOffArmState::class.java.getDeclaredField("bootstrapAttempted").apply { isAccessible = true }.setBoolean(offState, true)
        }
    }

    @Test fun `H5 report off, no hold and the daemon is never asked`() = runBlocking {
        connect()
        powerOffReport(false)
        coEvery { helper.offReportStatus(any()) } returns status(pending = 1)
        reporter.enqueue(entry("a"))
        reporter.drainOutbox("service_start")
        assertEquals(listOf("text a"), sent)
        coVerify(exactly = 0) { helper.offReportStatus(any()) }
    }

    @Test fun `H6 bootstrap attempted and the daemon silent, no hold`() = runBlocking {
        connect()
        powerOffReport(true)
        bootstrapAttempted()
        coEvery { helper.offReportStatus(any()) } returns null
        reporter.enqueue(entry("a"))
        reporter.drainOutbox("service_start")
        assertEquals(listOf("text a"), sent)
    }

    @Test fun `H9 no bot connected, NotConnected and the daemon is never asked`() = runBlocking {
        powerOffReport(true)
        coEvery { helper.offReportStatus(any()) } returns status(pending = 1)
        assertEquals(TelegramReporter.SendResult.NotConnected, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        coVerify(exactly = 0) { helper.offReportStatus(any()) }
    }

    @Test fun `H10 empty outbox and nothing pending, a fresh report goes out directly`() = runBlocking {
        connect()
        powerOffReport(true)
        bootstrapAttempted()
        coEvery { helper.offReportStatus(any()) } returns status(pending = 0)
        assertEquals(TelegramReporter.SendResult.Sent, reporter.sendRuleReport("R", setOf(ReportField.SOC), ""))
        assertEquals(1, sent.size)
        assertEquals("", settings.getTgReportOutbox())
    }
}
