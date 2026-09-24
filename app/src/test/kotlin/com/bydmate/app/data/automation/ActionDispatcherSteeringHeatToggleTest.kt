package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.data.vehicle.VehicleWriteError
import com.bydmate.app.util.appLocalizedContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Steering wheel heat as a toggle target: like sentry, its state is not in the snapshot
 * but read through the daemon (dev=1023 state fid). Real Application so the refusal and
 * the «no heater» reason are the strings the user reads.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherSteeringHeatToggleTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = ActionDispatcher(vehicleApi, helper, app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
            com.bydmate.app.util.AppStrings(app))

    private val defaultLiveSnapshot = dispatcher.liveSnapshot

    init {
        dispatcher.liveSnapshot = { null }
        coEvery { vehicleApi.dispatch(any()) } returns Result.success(Unit)
    }

    @After fun restoreLiveSnapshot() {
        dispatcher.liveSnapshot = defaultLiveSnapshot
    }

    private val toggleSteering =
        ActionDef(command = "", displayName = "Переключить", kind = "toggle", payload = "steering_heat")

    @Test fun `a heated wheel is switched off`() = runTest {
        coEvery { helper.read(1023, 1116733454, any()) } returns 2L
        assertTrue(dispatcher.dispatch(toggleSteering, null).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("关闭方向盘加热") }
    }

    @Test fun `a cold wheel is switched on`() = runTest {
        coEvery { helper.read(1023, 1116733454, any()) } returns 1L
        assertTrue(dispatcher.dispatch(toggleSteering, null).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("方向盘加热") }
    }

    @Test fun `no heater, no link or no reading is refused without writing`() = runTest {
        val lc = app.appLocalizedContext()
        val refusal = lc.getString(R.string.toggle_state_unknown, lc.getString(R.string.toggle_target_steering_heat))
        for (state in listOf(0L, 65535L, null)) {
            coEvery { helper.read(1023, 1116733454, any()) } returns state
            val result = dispatcher.dispatch(toggleSteering, null)
            assertFalse("state=$state", result.success)
            assertEquals(refusal, result.reason)
        }
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a car without the heater gets the readable reason`() = runTest {
        coEvery { vehicleApi.dispatch("方向盘加热") } returns
            Result.failure(VehicleWriteError.NotEquipped("steering_heat_on"))
        val result = dispatcher.dispatch(ActionDef(command = "方向盘加热", displayName = "", kind = "param"), null)
        assertFalse(result.success)
        assertEquals(app.appLocalizedContext().getString(R.string.steering_heat_not_equipped), result.reason)
    }

    @Test fun `toggling the wheel heat is not a dangerous action`() {
        assertFalse(ActionDispatcher.isDangerousAction(toggleSteering))
    }
}
