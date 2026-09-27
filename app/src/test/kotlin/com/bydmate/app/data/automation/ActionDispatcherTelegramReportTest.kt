package com.bydmate.app.data.automation

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.telegram.ReportField
import com.bydmate.app.data.telegram.TELEGRAM_REPORT_KIND
import com.bydmate.app.data.telegram.TelegramReporter
import com.bydmate.app.data.telegram.withReportRuleName
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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

/** The `telegram_report` step: payload in, the reporter's result mapped to the rule log. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherTelegramReportTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private val reporter = mockk<TelegramReporter>()
    private val dispatcher = ActionDispatcher(
        mockk<VehicleApi>(relaxed = true), mockk<HelperClient>(relaxed = true), ctx,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
        AppStrings(ctx), dagger.Lazy { reporter },
    )

    private val action = ActionDef(
        command = TELEGRAM_REPORT_KIND, displayName = "Отчёт в Telegram", kind = TELEGRAM_REPORT_KIND,
        payload = """{"fields":["soc","location"],"text":"ключ у Лены"}""",
    ).withReportRuleName("Где машина")

    @Before fun setUp() { LocalePreferences(ctx).setLanguage("ru") }

    private fun answer(result: TelegramReporter.SendResult) {
        coEvery { reporter.sendRuleReport(any(), any(), any()) } returns result
    }

    @Test fun `payload items, own text and the rule name reach the reporter`() = runBlocking {
        answer(TelegramReporter.SendResult.Sent)
        val result = dispatcher.dispatch(action, null)
        assertTrue(result.success)
        assertNull(result.reason)
        coVerify { reporter.sendRuleReport("Где машина", setOf(ReportField.SOC, ReportField.LOCATION), "ключ у Лены") }
    }

    @Test fun `no bot fails the step with a hint where to connect it`() = runBlocking {
        answer(TelegramReporter.SendResult.NotConnected)
        val result = dispatcher.dispatch(action, null)
        assertFalse(result.success)
        assertEquals("Подключите бота в Настройках", result.reason)
    }

    @Test fun `a report waiting for the network is a success with a note`() = runBlocking {
        answer(TelegramReporter.SendResult.Queued)
        val result = dispatcher.dispatch(action, null)
        assertTrue(result.success)
        assertEquals(AppStrings(ctx).get(com.bydmate.app.R.string.dispatch_tg_report_queued), result.reason)
    }

    @Test fun `a permanent Telegram error fails the step`() = runBlocking {
        answer(TelegramReporter.SendResult.Failed("BAD_TOKEN"))
        assertFalse(dispatcher.dispatch(action, null).success)
    }
}
