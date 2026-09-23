package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.appLocalizedContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sentry as a toggle target: unlike the other targets its state is not in the
 * snapshot but in Settings.Global, read back through the daemon. Real Application
 * so the refusal is the string the user reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherSentryToggleTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = ActionDispatcher(vehicleApi, helper, app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            com.bydmate.app.util.AppStrings(app))

    private val toggleSentry =
        ActionDef(command = "", displayName = "Переключить", kind = "toggle", payload = "sentry")

    @Test fun `armed sentry is switched off`() = runTest {
        coEvery { helper.getGlobalSetting("sentrymode_enabled_switch") } returns 1
        coEvery { helper.putGlobalSetting("sentrymode_enabled_switch", 0) } returns true
        assertTrue(dispatcher.dispatch(toggleSentry, null).success)
        coVerify(exactly = 1) { helper.putGlobalSetting("sentrymode_enabled_switch", 0) }
    }

    @Test fun `disarmed sentry is switched on`() = runTest {
        coEvery { helper.getGlobalSetting("sentrymode_enabled_switch") } returns 0
        coEvery { helper.putGlobalSetting("sentrymode_enabled_switch", 1) } returns true
        assertTrue(dispatcher.dispatch(toggleSentry, null).success)
        coVerify(exactly = 1) { helper.putGlobalSetting("sentrymode_enabled_switch", 1) }
    }

    @Test fun `an unexpected value is treated as off`() = runTest {
        coEvery { helper.getGlobalSetting("sentrymode_enabled_switch") } returns 2
        coEvery { helper.putGlobalSetting("sentrymode_enabled_switch", 1) } returns true
        assertTrue(dispatcher.dispatch(toggleSentry, null).success)
        coVerify(exactly = 1) { helper.putGlobalSetting("sentrymode_enabled_switch", 1) }
    }

    @Test fun `an unreadable switch is refused without writing`() = runTest {
        coEvery { helper.getGlobalSetting("sentrymode_enabled_switch") } returns null
        val result = dispatcher.dispatch(toggleSentry, null)
        assertFalse(result.success)
        val lc = app.appLocalizedContext()
        assertEquals(
            lc.getString(R.string.toggle_state_unknown, lc.getString(R.string.toggle_target_sentry)),
            result.reason,
        )
        coVerify(exactly = 0) { helper.putGlobalSetting(any(), any()) }
    }

    @Test fun `toggling sentry is a dangerous action`() {
        // It may be a disable, so it sits in the same tier as the explicit "sentry off".
        assertTrue(ActionDispatcher.isDangerousAction(toggleSentry))
    }
}
