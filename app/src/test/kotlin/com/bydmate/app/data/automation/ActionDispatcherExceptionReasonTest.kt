package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.util.AppStrings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An exception message spells out the whole intent (the link with its token, the coordinates),
 * so a step reason carries the exception class only.
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
    private val dispatcher = ActionDispatcher(mockk(relaxed = true), mockk(relaxed = true), context,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk(relaxed = true), mockk(relaxed = true), mockk(relaxed = true),
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
}
