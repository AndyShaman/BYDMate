package com.bydmate.app.data.automation

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.cluster.ClusterMode
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.R
import com.bydmate.app.data.vehicle.VehicleApi
import com.bydmate.app.util.appLocalizedContext
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "toggle" action: flips the target from the state the car reports, and never
 * guesses. Real Robolectric Application so the refusal reasons are the strings the
 * user actually reads. Each target is checked in both directions plus its refusals;
 * the locks case also proves the RESOLVED command hits the unlock speed gate, i.e.
 * the toggle is not a way around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ActionDispatcherToggleTest {
    private val vehicleApi = mockk<VehicleApi>(relaxed = true)
    private val cluster = mockk<ClusterVoiceControl>(relaxed = true)
    private val app: Application = ApplicationProvider.getApplicationContext()
    private val dispatcher = ActionDispatcher(vehicleApi, mockk<HelperClient>(relaxed = true), app,
        dagger.Lazy { mockk<com.bydmate.app.voice.VoiceAutomationActions>(relaxed = true) },
        cluster,
        mockk<com.bydmate.app.voice.AudioCapture>(relaxed = true),
        mockk<com.bydmate.app.split.SplitSessionManager>(relaxed = true))

    init {
        coEvery { vehicleApi.dispatch(any()) } returns Result.success(Unit)
    }

    /** The refusal text the user reads, in whatever language the app is set to. */
    private fun stateUnknown(targetRes: Int) = app.appLocalizedContext()
        .getString(R.string.toggle_state_unknown, app.appLocalizedContext().getString(targetRes))

    private fun toggle(target: String) =
        ActionDef(command = "", displayName = "Переключить", kind = "toggle", payload = target)

    private fun snapshot(
        trunk: Int? = null,
        frontTrunk: Int? = null,
        sunroof: Int? = null,
        lockFL: Int? = null,
        speed: Int? = 0,
    ) = DiParsData(
        soc = 50, speed = speed, mileage = 0.0, power = null, chargeGunState = null,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null, chargingStatus = null,
        batteryCapacityKwh = null, totalElecConsumption = null, voltage12v = null,
        maxCellVoltage = null, minCellVoltage = null, exteriorTemp = null, gear = 1,
        powerState = null, insideTemp = null, acStatus = null, acTemp = null, fanLevel = null,
        acCirc = null, doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null,
        sunroof = sunroof, trunk = trunk, hood = null, seatbeltFL = null, lockFL = lockFL,
        tirePressFL = null, tirePressFR = null, tirePressRL = null, tirePressRR = null,
        driveMode = null, workMode = null, autoPark = null, rain = null,
        lightLow = null, drl = null, frontTrunk = frontTrunk,
    )

    @After fun restoreLiveSnapshot() {
        dispatcher.liveSnapshot = defaultLiveSnapshot
    }

    /** The seat memory lives in prefs; each test starts from the untaught default. */
    @Before fun clearSeatMemory() {
        app.getSharedPreferences("seat_level_memory", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    private val defaultLiveSnapshot = dispatcher.liveSnapshot

    // ── snapshot freshness ──────────────────────────────────────────────────────

    @Test fun `current poll wins over the snapshot the rule started with`() = runTest {
        // A rule hands every step the snapshot taken before it started: after
        // "toggle -> delay -> toggle" the second step must see the trunk it just opened.
        dispatcher.liveSnapshot = { snapshot(trunk = 1) }
        assertTrue(dispatcher.dispatch(toggle("trunk"), snapshot(trunk = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("关后备箱") }
        coVerify(exactly = 0) { vehicleApi.dispatch("开后备箱") }
    }

    @Test fun `without a current poll the step snapshot is used`() = runTest {
        dispatcher.liveSnapshot = { null }
        assertTrue(dispatcher.dispatch(toggle("trunk"), snapshot(trunk = 1)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("关后备箱") }
    }

    // ── rear trunk ──────────────────────────────────────────────────────────────

    @Test fun `closed rear trunk opens`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("trunk"), snapshot(trunk = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("开后备箱") }
    }

    @Test fun `open rear trunk closes`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("trunk"), snapshot(trunk = 1)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("关后备箱") }
    }

    @Test fun `rear trunk in motion is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("trunk"), snapshot(trunk = 3))
        assertFalse(result.success)
        assertEquals(app.appLocalizedContext().getString(R.string.toggle_trunk_moving), result.reason)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `rear trunk without a reading is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("trunk"), snapshot(trunk = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_trunk), result.reason)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `no snapshot at all is refused`() = runTest {
        assertFalse(dispatcher.dispatch(toggle("trunk"), null).success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── front trunk (measured on-car 2026-09-15: 2=closed, 3=moving, 1=open) ────

    @Test fun `closed front trunk opens`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("front_trunk"), snapshot(frontTrunk = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("前备箱打开") }
    }

    @Test fun `raised front trunk closes`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("front_trunk"), snapshot(frontTrunk = 1)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("前备箱关闭") }
    }

    @Test fun `front trunk in motion is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("front_trunk"), snapshot(frontTrunk = 3))
        assertFalse(result.success)
        assertEquals(app.appLocalizedContext().getString(R.string.toggle_trunk_moving), result.reason)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    @Test fun `front trunk without a reading is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("front_trunk"), snapshot(frontTrunk = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_front_trunk), result.reason)
    }

    @Test fun `front trunk open keeps the standstill gate`() = runTest {
        val result = dispatcher.dispatch(toggle("front_trunk"), snapshot(frontTrunk = 2, speed = 40))
        assertFalse(result.success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── sunroof ─────────────────────────────────────────────────────────────────

    @Test fun `closed sunroof opens fully`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("sunroof"), snapshot(sunroof = 0)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("天窗打开100") }
    }

    @Test fun `open sunroof closes`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("sunroof"), snapshot(sunroof = 50)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("天窗打开0") }
    }

    @Test fun `sunroof without a reading is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("sunroof"), snapshot(sunroof = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_sunroof), result.reason)
    }

    @Test fun `sunroof open keeps its speed gate`() = runTest {
        val result = dispatcher.dispatch(toggle("sunroof"), snapshot(sunroof = 0, speed = 100))
        assertFalse(result.success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── door locks ──────────────────────────────────────────────────────────────

    @Test fun `locked doors unlock`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("locks"), snapshot(lockFL = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("车门解锁") }
    }

    @Test fun `unlocked doors lock`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("locks"), snapshot(lockFL = 1)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("车门上锁") }
    }

    @Test fun `locks without a reading are refused`() = runTest {
        val result = dispatcher.dispatch(toggle("locks"), snapshot(lockFL = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_locks), result.reason)
    }

    @Test fun `unlock resolved by a toggle still hits the speed gate`() = runTest {
        val result = dispatcher.dispatch(toggle("locks"), snapshot(lockFL = 2, speed = 60))
        assertFalse(result.success)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── cluster projection ──────────────────────────────────────────────────────

    @Test fun `projection off turns on`() = runTest {
        every { cluster.projectionMode() } returnsMany listOf(ClusterMode.OFF, ClusterMode.FULLSCREEN)
        assertTrue(dispatcher.dispatch(toggle("cluster"), snapshot()).success)
        io.mockk.verify(exactly = 1) { cluster.apply(true) }
    }

    @Test fun `projection on turns off`() = runTest {
        every { cluster.projectionMode() } returnsMany listOf(ClusterMode.FULLSCREEN, ClusterMode.OFF)
        assertTrue(dispatcher.dispatch(toggle("cluster"), snapshot()).success)
        io.mockk.verify(exactly = 1) { cluster.apply(false) }
    }

    // ── unknown target ──────────────────────────────────────────────────────────

    @Test fun `unknown target is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("hood"), snapshot())
        assertFalse(result.success)
        assertEquals(app.appLocalizedContext().getString(R.string.toggle_unknown_target, "hood"), result.reason)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── hazard lights ───────────────────────────────────────────────────────────

    @Test fun `running hazard lights are switched off`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("hazard"), snapshot().copy(turnSignal = 6)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("双闪关闭") }
    }

    /** Off, a turn signal or an unknown mask all mean «not blinking both sides» — turn them on. */
    @Test fun `anything but the hazard mask switches them on`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("hazard"), snapshot().copy(turnSignal = 1)).success)
        assertTrue(dispatcher.dispatch(toggle("hazard"), snapshot().copy(turnSignal = 4)).success)
        coVerify(exactly = 2) { vehicleApi.dispatch("双闪打开") }
    }

    @Test fun `hazard without a reading is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("hazard"), snapshot().copy(turnSignal = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_hazard), result.reason)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }

    // ── climate ─────────────────────────────────────────────────────────────────

    @Test fun `running climate is switched off and a stopped one goes to auto`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("climate"), snapshot().copy(acStatus = 1)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("关闭空调") }

        assertTrue(dispatcher.dispatch(toggle("climate"), snapshot().copy(acStatus = 0)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("自动空调") }
    }

    @Test fun `climate without a reading is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("climate"), snapshot().copy(acStatus = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_climate), result.reason)
    }

    // ── seats ───────────────────────────────────────────────────────────────────

    @Test fun `a heating driver seat is switched off`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("seat_heat_driver"), snapshot().copy(seatHeatDriver = 2)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热关闭") }
    }

    /** Without a step of their own the driver gets the middle one. */
    @Test fun `a cold driver seat comes back at the default step`() = runTest {
        assertTrue(dispatcher.dispatch(toggle("seat_heat_driver"), snapshot().copy(seatHeatDriver = 0)).success)
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅加热3档") }
    }

    /** The step the driver asked for last is the one the toggle brings back. */
    @Test fun `the remembered step is used when the seat comes back on`() = runTest {
        dispatcher.dispatch(
            ActionDef(command = "主驾座椅加热5档", displayName = "", kind = "param"),
            snapshot(),
        )
        assertTrue(dispatcher.dispatch(toggle("seat_heat_driver"), snapshot().copy(seatHeatDriver = 0)).success)
        // Twice: the command that taught the memory, then the one the toggle resolved to.
        coVerify(exactly = 2) { vehicleApi.dispatch("主驾座椅加热5档") }
    }

    /** Each seat and each function remembers its own step. */
    @Test fun `ventilation and the passenger seat keep their own memory`() = runTest {
        dispatcher.dispatch(
            ActionDef(command = "副驾座椅通风1档", displayName = "", kind = "param"),
            snapshot(),
        )
        assertTrue(
            dispatcher.dispatch(toggle("seat_vent_passenger"), snapshot().copy(seatVentPassenger = 0)).success
        )
        coVerify(exactly = 2) { vehicleApi.dispatch("副驾座椅通风1档") }

        assertTrue(
            dispatcher.dispatch(toggle("seat_vent_driver"), snapshot().copy(seatVentDriver = 0)).success
        )
        coVerify(exactly = 1) { vehicleApi.dispatch("主驾座椅通风3档") }
    }

    @Test fun `a blowing passenger seat is switched off`() = runTest {
        assertTrue(
            dispatcher.dispatch(toggle("seat_vent_passenger"), snapshot().copy(seatVentPassenger = 4)).success
        )
        coVerify(exactly = 1) { vehicleApi.dispatch("副驾座椅通风关闭") }
    }

    @Test fun `a seat without a reading is refused`() = runTest {
        val result = dispatcher.dispatch(toggle("seat_heat_passenger"), snapshot().copy(seatHeatPassenger = null))
        assertFalse(result.success)
        assertEquals(stateUnknown(R.string.toggle_target_seat_heat_passenger), result.reason)
        coVerify(exactly = 0) { vehicleApi.dispatch(any()) }
    }
}
