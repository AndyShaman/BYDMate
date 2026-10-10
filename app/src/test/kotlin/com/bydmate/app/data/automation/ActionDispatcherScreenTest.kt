package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.ScreenBacklightReply
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** «Экран: выключить / включить» (#275): each answer of the daemon is its own outcome and driver text. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherScreenTest {
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val helper = mockk<HelperClient>()
    private val dispatcher = ActionDispatcher(
        mockk<VehicleApi>(relaxed = true), helper, app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
        AppStrings(app), dagger.Lazy { mockk(relaxed = true) },
    )

    private fun lang(tag: String) = LocalePreferences(app).setLanguage(tag)

    @After fun restoreLanguage() = lang("ru")

    private fun screen(payload: String?) =
        ActionDef(command = "screen", displayName = "x", kind = "screen", payload = payload)

    private fun reply(outcome: Int, status: Int = -1, detail: String = "turnBacklightOff reads=1") =
        ScreenBacklightReply(outcome, status, detail)

    @Test fun `off asks the daemon for off and succeeds on a confirmed status`() = runTest {
        coEvery { helper.setScreenBacklight(false) } returns reply(HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED, 0)
        val result = dispatcher.dispatch(screen("0"), null)
        assertTrue(result.success)
        assertNull(result.reason)
        coVerify(exactly = 1) { helper.setScreenBacklight(false) }
    }

    @Test fun `on asks the daemon for on`() = runTest {
        coEvery { helper.setScreenBacklight(true) } returns reply(HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED, 1)
        assertTrue(dispatcher.dispatch(screen("1"), null).success)
        coVerify(exactly = 1) { helper.setScreenBacklight(true) }
    }

    @Test fun `a bad payload fails without a daemon call`() = runTest {
        for (payload in listOf("7", "on", null)) assertFalse(dispatcher.dispatch(screen(payload), null).success)
        coVerify(exactly = 0) { helper.setScreenBacklight(any()) }
    }

    private suspend fun reasons(answer: ScreenBacklightReply?, daemonAlive: Boolean = true): Pair<String?, String?> {
        coEvery { helper.setScreenBacklight(any()) } returns answer
        coEvery { helper.isAlive() } returns daemonAlive
        lang("ru")
        val ru = dispatcher.dispatch(screen("0"), null)
        lang("en")
        val en = dispatcher.dispatch(screen("0"), null)
        assertFalse(ru.success || en.success)
        return ru.reason to en.reason
    }

    @Test fun `a firmware without the call says the car cannot do it`() = runTest {
        assertEquals(
            "На этой машине управление экраном недоступно" to "Screen control is not available on this car",
            reasons(reply(HelperBinderProtocol.SCREEN_BACKLIGHT_ABSENT)),
        )
    }

    @Test fun `a status that did not follow is not confirmed`() = runTest {
        assertEquals(
            "Машина не подтвердила команду" to "The car did not confirm the command",
            reasons(reply(HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED, 1)),
        )
    }

    @Test fun `a call that threw is a failed command`() = runTest {
        assertEquals(
            "Не удалось выполнить команду" to "Could not run the command",
            reasons(reply(HelperBinderProtocol.SCREEN_BACKLIGHT_THREW, detail = "turnBacklightOff SecurityException: x")),
        )
    }

    @Test fun `an outcome this build does not know is a failed command`() = runTest {
        assertEquals("Не удалось выполнить команду" to "Could not run the command", reasons(reply(99)))
    }

    @Test fun `no answer means the command did not reach the car, daemon gone or outdated`() = runTest {
        val expected = "Команда не дошла до машины" to "The command did not reach the car"
        assertEquals(expected, reasons(null, daemonAlive = false))
        assertEquals(expected, reasons(null, daemonAlive = true))
    }

    @Test fun `the verdict word names every answer apart`() {
        val p = HelperBinderProtocol
        val verdicts = listOf(
            ActionDispatcher.screenVerdict(reply(p.SCREEN_BACKLIGHT_CONFIRMED), true),
            ActionDispatcher.screenVerdict(reply(p.SCREEN_BACKLIGHT_NOT_FOLLOWED), true),
            ActionDispatcher.screenVerdict(reply(p.SCREEN_BACKLIGHT_ABSENT), true),
            ActionDispatcher.screenVerdict(reply(p.SCREEN_BACKLIGHT_THREW), true),
            ActionDispatcher.screenVerdict(reply(99), true),
            ActionDispatcher.screenVerdict(null, true),
            ActionDispatcher.screenVerdict(null, false),
        )
        assertEquals(
            listOf("confirmed", "not_followed", "absent", "threw", "unknown_99", "daemon_outdated", "daemon_unreachable"),
            verdicts,
        )
    }
}
