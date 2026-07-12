package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.voice.VoiceAutomationActions
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionDispatcherVoiceActionsTest {

    // Factory: mirrors ActionDispatcherSentryTest pattern, passing dagger.Lazy { voiceActions }
    // for the 4th constructor parameter; relaxed mocks for the rest, including the 5th
    // (ClusterVoiceControl) which the speak/agent_query tests below never touch.
    private fun makeDispatcher(voiceActions: VoiceAutomationActions): ActionDispatcher {
        val vehicleApi = mockk<VehicleApi>(relaxed = true)
        val helper = mockk<HelperClient>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val notificationManager = mockk<NotificationManager>(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        return ActionDispatcher(vehicleApi, helper, context, dagger.Lazy { voiceActions },
            mockk<ClusterVoiceControl>(relaxed = true))
    }

    // Factory for the cluster_projection tests below: same shape, but takes the
    // ClusterVoiceControl mock directly so tests can verify apply(on).
    private fun makeDispatcherWithCluster(clusterVoiceControl: ClusterVoiceControl): ActionDispatcher {
        val vehicleApi = mockk<VehicleApi>(relaxed = true)
        val helper = mockk<HelperClient>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val notificationManager = mockk<NotificationManager>(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        val voiceActions = mockk<VoiceAutomationActions>(relaxed = true)
        return ActionDispatcher(vehicleApi, helper, context, dagger.Lazy { voiceActions }, clusterVoiceControl)
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

    // --- cluster_projection (mirrors ActionDispatcherSentryTest: payload "1"/"0" -> on/off) ---

    private fun clusterAction(payload: String?) = ActionDef(
        command = "cluster_projection", displayName = "Проекция на приборку",
        kind = "cluster_projection", payload = payload)

    @Test fun `cluster_projection with payload 1 turns projection on`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("1"), data = null)
        assertTrue(r.success)
        verify(exactly = 1) { cluster.apply(true) }
    }

    @Test fun `cluster_projection with payload 0 turns projection off`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("0"), data = null)
        assertTrue(r.success)
        verify(exactly = 1) { cluster.apply(false) }
    }

    @Test fun `cluster_projection with bad payload fails without calling apply`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("x"), data = null)
        assertFalse(r.success)
        verify(exactly = 0) { cluster.apply(any()) }
    }

    @Test fun `cluster_projection with null payload fails soft, not throws`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction(null), data = null)
        assertFalse(r.success)
        verify(exactly = 0) { cluster.apply(any()) }
    }
}
