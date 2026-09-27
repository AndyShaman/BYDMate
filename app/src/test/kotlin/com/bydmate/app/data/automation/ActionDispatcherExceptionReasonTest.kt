package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import com.bydmate.app.split.SplitSessionManager
import com.bydmate.app.split.SplitSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * An exception message spells out the whole intent (the link with its token, the coordinates),
 * so a step reason carries the exception class only, and a log line only what LinkRedaction
 * leaves of a link, a number or coordinates.
 */
// Robolectric for a real Intent: the android.jar stub hands back null from addFlags.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherExceptionReasonTest {
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
    }
    private val strings = mockk<AppStrings>(relaxed = true) {
        every { get(R.string.dispatch_no_handler_app, *anyVararg()) } answers { "no app: " + secondArg<Array<Any>>().joinToString() }
        every { get(R.string.dispatch_no_permission, *anyVararg()) } answers { "no permission: " + secondArg<Array<Any>>().joinToString() }
    }
    private val split = mockk<SplitSessionManager>(relaxed = true) {
        every { state } returns MutableStateFlow(SplitSessionState.Idle)
    }
    private val dispatcher = ActionDispatcher(mockk(relaxed = true), mockk(relaxed = true), context,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk(relaxed = true), mockk(relaxed = true), split,
        strings, dagger.Lazy { mockk(relaxed = true) },
    )

    private val url = ActionDef(command = "url", displayName = "u", kind = "url",
        payload = """{"url":"https://api.example.com/hook?token=SECRET"}""")

    @Test fun `no handler app gives the class name, not the intent`() = runBlocking {
        every { context.startActivity(any()) } throws
            ActivityNotFoundException("No Activity found to handle Intent { dat=https://api.example.com/hook?token=SECRET }")

        assertEquals("no app: ActivityNotFoundException", dispatcher.dispatch(url, null).reason)
    }

    @Test fun `a refused permission gives the class name`() = runBlocking {
        every { context.startActivity(any()) } throws SecurityException("Permission Denial: geo:53.9,27.5")

        assertEquals("no permission: SecurityException", dispatcher.dispatch(url, null).reason)
    }

    @Test fun `any other failure gives the class name`() = runBlocking {
        every { context.startActivity(any()) } throws IllegalStateException("yandexnavi://route?lat=53.9&token=SECRET")

        assertEquals("IllegalStateException", dispatcher.dispatch(url, null).reason)
    }

    // --- log lines: the label and the uri keep only the scheme and host of a link ---

    private fun dispatcherLog(): String {
        return ShadowLog.getLogsForTag("ActionDispatcher").joinToString("\n") { it.msg }
    }

    private fun failToStart() {
        ShadowLog.clear()
        every { context.startActivity(any()) } throws ActivityNotFoundException("no")
    }

    @Test fun `a url step logs the host of its link, not the token`() = runBlocking {
        failToStart()
        dispatcher.dispatch(url, null)

        val log = dispatcherLog()
        assertTrue(log, log.contains("url:https://api.example.com/hook?<redacted>: ActivityNotFoundException"))
        assertFalse(log, log.contains("SECRET"))
    }

    @Test fun `a call step logs no number`() = runBlocking {
        failToStart()
        dispatcher.dispatch(ActionDef("call", "c", "call", """{"phone":"+375291234567"}"""), null)

        val log = dispatcherLog()
        assertTrue(log, log.contains("dial:<phone>: ActivityNotFoundException"))
        assertFalse(log, log.contains("291234567"))
    }

    @Test fun `a navigate step logs its deep link and label without coordinates`() = runBlocking {
        failToStart()
        dispatcher.dispatch(ActionDef("navigate", "n", "navigate", """{"lat":53.9045,"lon":27.5615}"""), null)

        val log = dispatcherLog()
        assertTrue(log, log.contains("uri=yandexnavi://build_route_on_map?<redacted>"))
        assertTrue(log, log.contains("label=navigate:<coords>"))
        assertFalse(log, log.contains("53.9045") || log.contains("27.5615"))
    }

    @Test fun `a Maps navigate step logs its deep link and label without coordinates`() = runBlocking {
        failToStart()
        dispatcher.dispatch(
            ActionDef("navigate", "n", "navigate", """{"lat":53.9045,"lon":27.5615,"app":"maps"}"""), null,
        )

        val log = dispatcherLog()
        assertTrue(log, log.contains("uri=yandexmaps://maps.yandex.ru/?<redacted>"))
        assertTrue(log, log.contains("label=navigate_maps:<coords>"))
        assertFalse(log, log.contains("53.9045") || log.contains("27.5615"))
    }
}
