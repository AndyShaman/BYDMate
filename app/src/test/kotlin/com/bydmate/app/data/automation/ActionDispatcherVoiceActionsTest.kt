package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterMode
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
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            mockk<com.bydmate.app.util.AppStrings>(relaxed = true), dagger.Lazy { io.mockk.mockk(relaxed = true) })
    }

    // Factory for the cluster_projection tests below: same shape, but takes the
    // ClusterVoiceControl mock directly so tests can verify apply(on). The failure reasons
    // come from AppStrings, stubbed with their Russian texts.
    private fun makeDispatcherWithCluster(clusterVoiceControl: ClusterVoiceControl): ActionDispatcher {
        val vehicleApi = mockk<VehicleApi>(relaxed = true)
        val helper = mockk<HelperClient>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val notificationManager = mockk<NotificationManager>(relaxed = true)
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        val voiceActions = mockk<VoiceAutomationActions>(relaxed = true)
        val strings = mockk<com.bydmate.app.util.AppStrings>(relaxed = true)
        every { strings.get(R.string.dispatch_cluster_not_on) } returns "проекция на приборку не включилась"
        every { strings.get(R.string.dispatch_cluster_daemon_restarting) } returns "служебный процесс перезапускается"
        return ActionDispatcher(vehicleApi, helper, context, dagger.Lazy { voiceActions }, clusterVoiceControl,
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
            mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            strings, dagger.Lazy { io.mockk.mockk(relaxed = true) })
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
        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("1"), data = null)
        assertTrue(r.success)
        verify(exactly = 1) { cluster.apply(true) }
    }

    @Test fun `cluster_projection with payload 0 turns projection off`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.OFF
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("0"), data = null)
        assertTrue(r.success)
        verify(exactly = 1) { cluster.apply(false) }
    }

    // Wave 3: apply() is fire-and-forget, so a projection that never comes up must be a failed
    // step in the rule journal, not a silent success.
    @Test fun `cluster_projection that never comes up is reported as a failure`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.OFF
        every { cluster.lastFailure() } returns null
        val d = makeDispatcherWithCluster(cluster).also { it.clusterPollIntervalMs = 1L }
        val r = d.dispatch(clusterAction("1"), data = null)
        assertFalse(r.success)
        assertEquals("проекция на приборку не включилась", r.reason)
        assertFalse(r.daemonRestarting)
    }

    @Test fun `cluster_projection blames the restarting daemon when it is the known cause`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.OFF
        every { cluster.lastFailure() } returns "daemon"
        val d = makeDispatcherWithCluster(cluster).also { it.clusterPollIntervalMs = 1L }
        val r = d.dispatch(clusterAction("1"), data = null)
        assertFalse(r.success)
        assertEquals("служебный процесс перезапускается", r.reason)
        assertTrue(r.daemonRestarting)
    }

    // --- cluster_projection with an app picked in the action (#279) ---

    private val radioOn = """{"state":"1","packageName":"com.example.radio","appLabel":"Радио"}"""

    @Test fun `cluster_projection without an app makes the same calls as before`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        val r = makeDispatcherWithCluster(cluster).dispatch(clusterAction("1"), data = null)
        assertTrue(r.success)
        verify(exactly = 1) { cluster.apply(true, null) }
        verify(exactly = 0) { cluster.projectedPackage() }
    }

    @Test fun `cluster_projection with an app projects that app`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        every { cluster.projectedPackage() } returns "com.example.radio"
        val r = makeDispatcherWithCluster(cluster).dispatch(clusterAction(radioOn), data = null)
        assertTrue(r.success)
        verify(exactly = 1) { cluster.apply(true, "com.example.radio") }
    }

    @Test fun `cluster_projection with an app waits for that app, not any projection`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.FULLSCREEN
        every { cluster.projectedPackage() } returnsMany listOf("ru.yandex.yandexnavi", "ru.yandex.yandexnavi", "com.example.radio")
        val d = makeDispatcherWithCluster(cluster).also { it.clusterPollIntervalMs = 1L }
        assertTrue(d.dispatch(clusterAction(radioOn), data = null).success)

        every { cluster.projectedPackage() } returns "ru.yandex.yandexnavi"
        every { cluster.lastFailure() } returns null
        assertFalse(d.dispatch(clusterAction(radioOn), data = null).success)
    }

    @Test fun `cluster_projection off ignores the app`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.OFF
        val off = """{"state":"0","packageName":"com.example.radio","appLabel":"Радио"}"""
        assertTrue(makeDispatcherWithCluster(cluster).dispatch(clusterAction(off), data = null).success)
        verify(exactly = 1) { cluster.apply(false, null) }
    }

    // The projection coming up a beat later is still a success: we poll, not sample once.
    @Test fun `cluster_projection that comes up on a later poll is a success`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returnsMany
            listOf(ClusterMode.OFF, ClusterMode.OFF, ClusterMode.FULLSCREEN)
        val d = makeDispatcherWithCluster(cluster).also { it.clusterPollIntervalMs = 1L }
        assertTrue(d.dispatch(clusterAction("1"), data = null).success)
    }

    // Issue #263: direct projection on a Sea Lion 07 took 5-6 s while the wait was 5 s, so the
    // rule journal said "did not come up" for a projection that did. These run on the production
    // defaults (virtual time), so shortening the wait again breaks them.
    @Test fun `cluster_projection that comes up at the 6th second is a success on production defaults`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } answers {
            if (testScheduler.currentTime >= 6_000L) ClusterMode.FULLSCREEN else ClusterMode.OFF
        }
        every { cluster.lastFailure() } returns null
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("1"), data = null)
        assertTrue(r.success)
        assertEquals(6_000L, testScheduler.currentTime)
    }

    @Test fun `cluster_projection that never comes up fails only after the full 15 s wait`() = runTest {
        val cluster = mockk<ClusterVoiceControl>(relaxUnitFun = true)
        every { cluster.projectionMode() } returns ClusterMode.OFF
        every { cluster.lastFailure() } returns null
        val d = makeDispatcherWithCluster(cluster)
        val r = d.dispatch(clusterAction("1"), data = null)
        assertFalse(r.success)
        assertEquals("проекция на приборку не включилась", r.reason)
        assertEquals(15_000L, testScheduler.currentTime)
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
