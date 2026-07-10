package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.voice.VoiceAutomationActions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherVoiceActionsTest {

    // Factory: mirrors ActionDispatcherSentryTest pattern, passing dagger.Lazy { voiceActions }
    // for the new 4th constructor parameter; relaxed mocks for the rest.
    private fun makeDispatcher(voiceActions: VoiceAutomationActions): ActionDispatcher {
        val vehicleApi = mockk<VehicleApi>(relaxed = true)
        val helper = mockk<HelperClient>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val notificationManager = mockk<NotificationManager>(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        return ActionDispatcher(vehicleApi, helper, context, dagger.Lazy { voiceActions })
    }

    @Test fun `speak action routes payload text to the coordinator`() = runTest {
        val voice = mockk<VoiceAutomationActions>()
        coEvery { voice.speak("Позвони жене") } returns DispatchResult(true)
        val d = makeDispatcher(voice)
        val r = d.dispatch(ActionDef(command = "", displayName = "Озвучить текст",
            kind = "speak", payload = """{"text":"Позвони жене"}"""), data = null)
        assertTrue(r.success)
        coVerify(exactly = 1) { voice.speak("Позвони жене") }
    }

    @Test fun `agent_query action routes payload prompt to the coordinator`() = runTest {
        val voice = mockk<VoiceAutomationActions>()
        coEvery { voice.agentQuery("расскажи сводку погоды") } returns DispatchResult(true)
        val d = makeDispatcher(voice)
        val r = d.dispatch(ActionDef(command = "", displayName = "Запрос агенту",
            kind = "agent_query", payload = """{"prompt":"расскажи сводку погоды"}"""), data = null)
        assertTrue(r.success)
    }

    @Test fun `empty payload text fails without reaching the coordinator`() = runTest {
        val voice = mockk<VoiceAutomationActions>(relaxed = true)
        val d = makeDispatcher(voice)
        assertFalse(d.dispatch(ActionDef("", "x", kind = "speak", payload = """{"text":""}"""), null).success)
        assertFalse(d.dispatch(ActionDef("", "x", kind = "agent_query", payload = null), null).success)
        coVerify(exactly = 0) { voice.speak(any()) }
        coVerify(exactly = 0) { voice.agentQuery(any()) }
    }

    @Test fun `coordinator failure reason propagates to the rule journal result`() = runTest {
        val voice = mockk<VoiceAutomationActions>()
        coEvery { voice.speak(any()) } returns DispatchResult(false, "озвучка выключена в настройках")
        val d = makeDispatcher(voice)
        val r = d.dispatch(ActionDef("", "x", kind = "speak", payload = """{"text":"т"}"""), null)
        assertFalse(r.success)
        assertEquals("озвучка выключена в настройках", r.reason)
    }
}
