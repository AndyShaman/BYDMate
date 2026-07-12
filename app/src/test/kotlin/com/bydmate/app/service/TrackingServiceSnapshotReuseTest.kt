package com.bydmate.app.service

import com.bydmate.app.data.remote.DiParsData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pure JVM tests for the П4a/П4b snapshot-reuse seams: gun-state and ABRP engine
 * power are now sourced from the tick's already-fetched DiParsData snapshot
 * instead of a second dedicated autoservice read. Touching the companion does
 * NOT instantiate the Android Service — see TrackingServiceButtonBridgeTest /
 * WatchdogGateTest.
 */
class TrackingServiceSnapshotReuseTest {

    /** Minimal valid DiParsData — only chargeGunState/power vary per test. */
    private fun snapshot(chargeGunState: Int? = 1, power: Double? = 0.0): DiParsData = DiParsData(
        soc = 80, speed = 0, mileage = 12345.0, power = power,
        chargeGunState = chargeGunState,
        maxBatTemp = 28, avgBatTemp = 26, minBatTemp = 24,
        chargingStatus = null, batteryCapacityKwh = 72.9,
        totalElecConsumption = 1000.0,
        voltage12v = 12.6, maxCellVoltage = 3.31, minCellVoltage = 3.30,
        exteriorTemp = 18, gear = 1, powerState = 1, insideTemp = 22,
        acStatus = 0, acTemp = 22, fanLevel = 0, acCirc = 0,
        doorFL = 0, doorFR = 0, doorRL = 0, doorRR = 0,
        windowFL = 0, windowFR = 0, windowRL = 0, windowRR = 0,
        sunroof = 0, trunk = 0, hood = 0, seatbeltFL = 1, lockFL = 2,
        tirePressFL = 240, tirePressFR = 241, tirePressRL = 239, tirePressRR = 242,
        driveMode = 1, workMode = 1, autoPark = 0, rain = 0,
        lightLow = 0, drl = 1,
    )

    // П4a — gun-state sample for the edge detector ----------------------------

    @Test fun `gun state sample reuses the snapshot's chargeGunState verbatim`() {
        assertEquals(3, TrackingService.gunStateFromSnapshot(snapshot(chargeGunState = 3)))
    }

    @Test fun `gun state sample passes null straight through, not a synthesized value`() {
        // dr-agent concern: a transient sentinel/decode failure must surface as null so
        // GunStateEdgeDetector.onSample no-ops on it (its own null branch is already
        // covered by GunStateEdgeDetectorTest) instead of faking a value that could
        // trigger a spurious connected->disconnected edge.
        assertNull(TrackingService.gunStateFromSnapshot(snapshot(chargeGunState = null)))
    }

    // П4b — ABRP engine power sample -------------------------------------------

    @Test fun `engine power sample reuses the snapshot's power, converted to Int kW`() {
        assertEquals(42, TrackingService.enginePowerKwFromSnapshot(snapshot(power = 42.0)))
    }

    @Test fun `engine power sample passes null through so Iternio falls back to DiPars power`() {
        assertNull(TrackingService.enginePowerKwFromSnapshot(snapshot(power = null)))
    }
}
