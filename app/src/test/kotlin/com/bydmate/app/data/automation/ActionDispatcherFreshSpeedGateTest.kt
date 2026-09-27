package com.bydmate.app.data.automation

import android.app.NotificationManager
import android.content.Context
import com.bydmate.app.R
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.VehicleApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A speed-gated opening is decided on the speed read through the daemon right before the step,
 * never on the snapshot the caller hands in (a rule's is from the moment it fired), never on a
 * poll and never through ADB: a failed read is refused as speed unknown. Runs the real gate.
 */
class ActionDispatcherFreshSpeedGateTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true) {
        coEvery { dispatch(any()) } returns Result.success(Unit)
    }
    private val helper = mockk<HelperClient>(relaxed = true)
    private val context = mockk<Context>(relaxed = true) {
        every { getSystemService(Context.NOTIFICATION_SERVICE) } returns mockk<NotificationManager>(relaxed = true)
        every { createConfigurationContext(any()) } answers { self as Context }
        every { getString(R.string.gate_frunk_speed_unknown) } returns FRUNK_SPEED_UNKNOWN
    }
    private val dispatcher = ActionDispatcher(vehicleApi, helper, context,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        mockk<ClusterVoiceControl>(relaxed = true),
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true),
        mockk<com.bydmate.app.util.AppStrings>(relaxed = true), dagger.Lazy { mockk(relaxed = true) },
    )

    private fun param(command: String) = ActionDef(command = command, displayName = command, kind = "param")

    /** The daemon answers the speed fid with [kmh] as tx=7 float bits. */
    private fun daemonSpeed(kmh: Float) {
        coEvery { helper.readBatch(any()) } returns listOf(0 to java.lang.Float.floatToRawIntBits(kmh))
    }

    @Test fun `a stale zero snapshot does not open a window on a moving car`() = runBlocking {
        dispatcher.readSpeedNow = { 130 }

        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a stale zero snapshot does not open the frunk or unlock on a moving car`() = runBlocking {
        dispatcher.readSpeedNow = { 5 }
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)

        dispatcher.readSpeedNow = { 40 }
        assertFalse(dispatcher.dispatch(param("车门解锁"), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `a fresh zero opens even when the step came with no data`() = runBlocking {
        dispatcher.readSpeedNow = { 0 }

        assertTrue(dispatcher.dispatch(param(WINDOW_OPEN), null).success)
        assertTrue(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 60)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch(WINDOW_OPEN) }
    }

    @Test fun `a failed direct read is refused even with a zero poll`() = runBlocking {
        dispatcher.liveSnapshot = { diParsData(speed = 0) }

        dispatcher.readSpeedNow = { null }
        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        dispatcher.readSpeedNow = { error("binder died") }
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `the speed is read through the daemon, never through ADB`() = runBlocking {
        daemonSpeed(0f)

        assertTrue(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 60)).success)
        val speed = FidAddresses.of("speed")
        coVerify(exactly = 1) { helper.readBatch(listOf(BatchReadItem(tx = 7, dev = speed.device, fid = speed.fid))) }
        coVerify(exactly = 0) { vehicleApi.readSpeed() }
    }

    @Test fun `the daemon read is rounded up`() = runBlocking {
        daemonSpeed(120.2f)
        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)

        daemonSpeed(120.0f)
        assertTrue(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
    }

    @Test fun `0,8 km per h is moving for the frunk and 120,8 is over the window limit`() = runBlocking {
        daemonSpeed(0.8f)
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)

        daemonSpeed(120.8f)
        assertFalse(dispatcher.dispatch(param(WINDOW_OPEN), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `no daemon, a failed read or a sentinel is refused as speed unknown`() = runBlocking {
        coEvery { helper.readBatch(any()) } returns null
        val noDaemon = dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0))
        assertFalse(noDaemon.success)
        assertEquals(FRUNK_SPEED_UNKNOWN, noDaemon.reason)

        coEvery { helper.readBatch(any()) } returns listOf(-1 to 0)
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)

        coEvery { helper.readBatch(any()) } returns listOf(0 to java.lang.Float.floatToRawIntBits(-1.0f))
        assertFalse(dispatcher.dispatch(param("前备箱打开"), diParsData(speed = 0)).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
        coVerify(exactly = 0) { vehicleApi.readSpeed() }
    }

    @Test fun `a closing step reads no speed`() = runBlocking {
        dispatcher.readSpeedNow = { error("must not read") }

        assertTrue(dispatcher.dispatch(param("主驾打开0"), null).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾打开0") }
    }

    private companion object {
        const val WINDOW_OPEN = "主驾打开100"
        const val FRUNK_SPEED_UNKNOWN = "Скорость неизвестна"
    }
}
