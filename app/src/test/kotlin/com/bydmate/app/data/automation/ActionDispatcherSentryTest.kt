package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Plain JUnit + mockk: dispatchSentry only reads action.payload and calls
// helper.putGlobalSetting — Context is stored but never dereferenced for this kind.
class ActionDispatcherSentryTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>()
    private val context = mockk<Context>(relaxed = true)
    private val notificationManager = mockk<NotificationManager>(relaxed = true)
    private val dispatcher: ActionDispatcher

    init {
        every { context.getSystemService(Context.NOTIFICATION_SERVICE) } returns notificationManager
        dispatcher = ActionDispatcher(vehicleApi, helper, context,
            dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
            mockk<ClusterVoiceControl>(relaxed = true),
            mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true))
    }

    private fun sentry(payload: String) =
        ActionDef(command = "sentry", displayName = "Охранный режим", kind = "sentry", payload = payload)

    @Test fun `sentry enable writes global switch 1`() = runBlocking {
        coEvery { helper.putGlobalSetting("sentrymode_enabled_switch", 1) } returns true
        assertTrue(dispatcher.dispatch(sentry("1"), null).success)
        coVerify(exactly = 1) { helper.putGlobalSetting("sentrymode_enabled_switch", 1) }
    }

    @Test fun `sentry disable writes global switch 0`() = runBlocking {
        coEvery { helper.putGlobalSetting("sentrymode_enabled_switch", 0) } returns true
        assertTrue(dispatcher.dispatch(sentry("0"), null).success)
        coVerify(exactly = 1) { helper.putGlobalSetting("sentrymode_enabled_switch", 0) }
    }

    @Test fun `sentry with bad payload fails without daemon call`() = runBlocking {
        assertFalse(dispatcher.dispatch(sentry("x"), null).success)
        coVerify(exactly = 0) { helper.putGlobalSetting(any(), any()) }
    }
}
