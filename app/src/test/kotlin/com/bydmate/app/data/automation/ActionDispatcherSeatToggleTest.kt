package com.bydmate.app.data.automation

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.BuildConfig
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.nativestack.FidCatalogCache
import com.bydmate.app.data.nativestack.FidCatalogManager
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.SeatChannel
import com.bydmate.app.data.vehicle.SeatChannelStorePrefs
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * #441 Case 10: front seat heat toggle on a FALLBACK car reads the dev=1023 state fid
 * (1 off, 2..6 on) from the cached FidCatalog and falls back to live.seatHeatDriver when
 * the read fails or the platform is unknown. PRIMARY cars keep using live.seatHeatDriver.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherSeatToggleTest {

    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val helper = mockk<HelperClient>(relaxed = true)
    private val cluster = mockk<ClusterVoiceControl>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = testDispatcher(
        vehicleApi = vehicleApi,
        helper = helper,
        context = app,
        clusterVoiceControl = cluster,
        appStrings = AppStrings(app),
    ).also { it.readSpeedNow = { 0 } }

    private val defaultLiveSnapshot = dispatcher.liveSnapshot

    @Before fun setUp() {
        coEvery { vehicleApi.dispatch(any()) } returns Result.success(Unit)
        app.getSharedPreferences("seat_level_memory", Context.MODE_PRIVATE).edit().clear().commit()
        app.getSharedPreferences("seat_channel", Context.MODE_PRIVATE).edit().clear().commit()
        cacheFile().delete()
    }

    @After fun tearDown() {
        dispatcher.liveSnapshot = defaultLiveSnapshot
        cacheFile().delete()
    }

    private fun cacheFile(): File = File(app.filesDir, FidCatalogManager.CACHE_FILE)

    private fun writeCanFdCatalogCache() {
        val fp = Build.FINGERPRINT ?: ""
        val header = FidCatalogCache.header(fp, BuildConfig.VERSION_CODE, 2)
        cacheFile().writeText(
            """
            $header
            Setting.SET_DRIVER_SEAT_HEATING_STATE=$DRIVER_STATE_CANFD
            Setting.SET_PASSENGER_SEAT_HEATING_STATE=$PASSENGER_STATE_CANFD
            """.trimIndent()
        )
    }

    private fun setSeatChannel(winner: SeatChannel) {
        SeatChannelStorePrefs(app.getSharedPreferences("seat_channel", Context.MODE_PRIVATE))
            .setWinner(winner)
    }

    private fun toggleDriverHeat() = ActionDef(
        command = "",
        displayName = "Toggle",
        kind = "toggle",
        payload = ActionDispatcher.TOGGLE_SEAT_HEAT_DRIVER,
    )

    private fun snapshot(seatHeatDriver: Int? = 0) = DiParsData(
        soc = 50, speed = 0, mileage = 0.0, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = null,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = null,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null, gear = 1,
        powerState = null, insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null,
        sunroof = null, trunk = null, hood = null, seatbeltFL = null, lockFL = null,
        tirePressFL = null, tirePressFR = null, tirePressRL = null, tirePressRR = null,
        driveMode = null, workMode = null, autoPark = null, rain = null,
        lightLow = null, drl = null, seatHeatDriver = seatHeatDriver,
    )

    @Test fun `FALLBACK CanFD state 2 turns driver seat heat off even when snapshot says 0`() = runTest {
        setSeatChannel(SeatChannel.FALLBACK)
        writeCanFdCatalogCache()
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns 2L

        assertTrue(dispatcher.dispatch(toggleDriverHeat(), snapshot(seatHeatDriver = 0)).success)

        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热关闭") }
        coVerify(exactly = 0) { vehicleApi.dispatch("主驾座椅加热3档") }
    }

    @Test fun `FALLBACK CanFD state 1 turns driver seat heat on`() = runTest {
        setSeatChannel(SeatChannel.FALLBACK)
        writeCanFdCatalogCache()
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns 1L

        assertTrue(dispatcher.dispatch(toggleDriverHeat(), snapshot(seatHeatDriver = 0)).success)

        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热3档") }
        coVerify(exactly = 0) { vehicleApi.dispatch("主驾座椅加热关闭") }
    }

    @Test fun `FALLBACK CanFD falls back to snapshot field when state read fails`() = runTest {
        setSeatChannel(SeatChannel.FALLBACK)
        writeCanFdCatalogCache()
        coEvery { helper.read(DEV_SETTING, DRIVER_STATE_CANFD, any()) } returns null

        assertTrue(dispatcher.dispatch(toggleDriverHeat(), snapshot(seatHeatDriver = 0)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热3档") }

        assertTrue(dispatcher.dispatch(toggleDriverHeat(), snapshot(seatHeatDriver = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热关闭") }
    }

    @Test fun `PRIMARY car decides driver seat heat toggle by live seatHeatDriver without dev 1023 read`() = runTest {
        setSeatChannel(SeatChannel.PRIMARY)
        writeCanFdCatalogCache()

        assertTrue(dispatcher.dispatch(toggleDriverHeat(), snapshot(seatHeatDriver = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热关闭") }

        assertTrue(dispatcher.dispatch(toggleDriverHeat(), snapshot(seatHeatDriver = 0)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热3档") }

        coVerify(exactly = 0) { helper.read(DEV_SETTING, any(), any()) }
    }

    private companion object {
        const val DEV_SETTING = 1023
        const val DRIVER_STATE_CANFD = 1335885835
        const val PASSENGER_STATE_CANFD = 1335885843
    }
}
