package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.dao.FakeSettingsDao
import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.nativestack.kindMonitor
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import com.bydmate.app.data.repository.SettingsRepository
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** recordParkedAnchor on a DM-i hybrid: the engine charging the pack is no charge to re-arm for. */
class AutoserviceChargingDetectorHybridAnchorTest {

    private class Setup(val detector: AutoserviceChargingDetector, val stateStore: ChargingStateStore)

    private fun build(kind: VehicleKind, anchorSoc: Int = 30, anchorMileage: Float = 1000f): Setup {
        val settings = SettingsRepository(
            FakeSettingsDao(
                mapOf(
                    SettingsRepository.KEY_CHARGING_BASELINE_SOC to anchorSoc.toString(),
                    SettingsRepository.KEY_LAST_MILEAGE_KM to anchorMileage.toString(),
                )
            ),
            mockk<LocalePreferences>(relaxed = true),
        )
        val stateStore = ChargingStateStore(settings)
        val detector = AutoserviceChargingDetector(
            client = mockk<AutoserviceClient>(relaxed = true),
            chargeRepo = mockk(relaxed = true),
            batteryHealthRepo = mockk(relaxed = true),
            stateStore = stateStore,
            classifier = ChargingTypeClassifier(),
            settings = settings,
            costCalculator = mockk(relaxed = true),
            parsReader = mockk(relaxed = true),
            journal = CatchUpJournal(settings),
            vehicleKind = kindMonitor(kind),
        )
        return Setup(detector, stateStore)
    }

    @Suppress("LongParameterList")
    private fun tick(
        soc: Int, speed: Int?, rpm: Int?, mileage: Double, power: Double? = 0.0, gun: Int? = 1,
    ): DiParsData = diParsData(soc = soc, speed = speed, mileage = mileage, power = power, chargeGunState = gun)
        .copy(engineRpm = rpm)

    private suspend fun assertAnchorMoved(s: Setup, soc: Int, mileage: Float) {
        val state = s.stateStore.load()
        assertEquals(soc, state.socPercent)
        assertEquals(mileage, state.mileageKm!!, 0.01f)
    }

    private suspend fun assertAnchorKept(s: Setup) {
        val state = s.stateStore.load()
        assertEquals(30, state.socPercent)
        assertEquals(1000f, state.mileageKm!!, 0.01f)
    }

    @Test fun `a hybrid driving with the engine charging moves the anchor and does not re-arm`() = runTest {
        val s = build(VehicleKind.HYBRID)
        val rearm = s.detector.recordParkedAnchor(
            tick(soc = 34, speed = 40, rpm = 1500, mileage = 1003.0, power = -5.0), now = 2000L
        )
        assertFalse(rearm)
        assertAnchorMoved(s, 34, 1003f)
    }

    @Test fun `a hybrid standing with the engine running moves the anchor and does not re-arm`() = runTest {
        val s = build(VehicleKind.HYBRID)
        val rearm = s.detector.recordParkedAnchor(
            tick(soc = 34, speed = 0, rpm = 1200, mileage = 1000.0), now = 2000L
        )
        assertFalse(rearm)
        assertAnchorMoved(s, 34, 1000f)
    }

    @Test fun `a hybrid with the engine off but the odometer well past the anchor moves it and does not re-arm`() = runTest {
        for (rpm in listOf(0, null)) {
            val s = build(VehicleKind.HYBRID)
            val rearm = s.detector.recordParkedAnchor(
                tick(soc = 34, speed = 0, rpm = rpm, mileage = 1005.0), now = 2000L
            )
            assertFalse("rpm=$rpm", rearm)
            assertAnchorMoved(s, 34, 1005f)
        }
    }

    @Test fun `a hybrid parked with the engine off and the odometer at the anchor re-arms and keeps the anchor`() = runTest {
        for (rpm in listOf(0, null)) {
            val s = build(VehicleKind.HYBRID)
            val rearm = s.detector.recordParkedAnchor(
                tick(soc = 34, speed = 0, rpm = rpm, mileage = 1000.0, power = 0.0), now = 2000L
            )
            assertTrue("rpm=$rpm", rearm)
            assertAnchorKept(s)
        }
    }

    @Test fun `a hybrid parked with the odometer under one km past the anchor still re-arms`() = runTest {
        for (rpm in listOf(0, null)) {
            val s = build(VehicleKind.HYBRID)
            val rearm = s.detector.recordParkedAnchor(
                tick(soc = 34, speed = 0, rpm = rpm, mileage = 1000.5, power = 0.0), now = 2000L
            )
            assertTrue("rpm=$rpm", rearm)
            assertAnchorKept(s)
        }
    }

    @Test fun `an EV or an unknown car with a SOC rise while driving re-arms exactly as before`() = runTest {
        for (kind in listOf(VehicleKind.EV, VehicleKind.UNKNOWN)) {
            val s = build(kind)
            val rearm = s.detector.recordParkedAnchor(
                tick(soc = 34, speed = 40, rpm = null, mileage = 1003.0, power = 0.0), now = 2000L
            )
            assertTrue("kind=$kind", rearm)
            assertAnchorKept(s)
        }
    }

    @Test fun `a hybrid with the gun connected and the SOC rising keeps the anchor and does not re-arm`() = runTest {
        val s = build(VehicleKind.HYBRID)
        val rearm = s.detector.recordParkedAnchor(
            tick(soc = 34, speed = 40, rpm = null, mileage = 1003.0, gun = 2), now = 2000L
        )
        assertFalse(rearm)
        assertAnchorKept(s)
    }
}
