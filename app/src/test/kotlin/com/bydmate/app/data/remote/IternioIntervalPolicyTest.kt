package com.bydmate.app.data.remote

import com.bydmate.app.data.remote.IternioIntervalPolicy.TelemetryState
import org.junit.Assert.assertEquals
import org.junit.Test

class IternioIntervalPolicyTest {

    // --- classification ---

    @Test
    fun `not charging and not parked classifies as DRIVING`() {
        assertEquals(TelemetryState.DRIVING, IternioIntervalPolicy.classify(charging = false, parked = false))
    }

    @Test
    fun `parked without charge gun classifies as PARKED`() {
        assertEquals(TelemetryState.PARKED, IternioIntervalPolicy.classify(charging = false, parked = true))
    }

    @Test
    fun `charging beats parked - plug in P is CHARGING`() {
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classify(charging = true, parked = true))
    }

    @Test
    fun `charging beats driving - degenerate but charging wins`() {
        // Physically the car cannot be in D with the gun connected, but the
        // policy must not produce DRIVING in that edge state — charging is
        // always the safe call when the gun is connected.
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classify(charging = true, parked = false))
    }

    // --- intervals ---

    @Test
    fun `interval for DRIVING is 1 second - matches teslamate-abrp drive cadence`() {
        assertEquals(1, IternioIntervalPolicy.intervalSec(TelemetryState.DRIVING))
    }

    @Test
    fun `interval for CHARGING is 8 seconds - under Iternio 10-sec accuracy threshold`() {
        // Industry pattern (teslamate-abrp uses 6s); 8s is conservative for our
        // ADB-on-device read budget while still beating the 10-sec window
        // Iternio uses to rate sample density.
        assertEquals(8, IternioIntervalPolicy.intervalSec(TelemetryState.CHARGING))
    }

    @Test
    fun `interval for PARKED is 30 seconds - keep ABRP alive but stop spamming`() {
        assertEquals(30, IternioIntervalPolicy.intervalSec(TelemetryState.PARKED))
    }

    // --- classifyFromDiPars ---
    //
    // Classifier reads only DiPars signals so we can decide cadence without
    // an autoservice ADB read on every tick. Charging signal is
    // `chargeGunState == 2` (physical AC plug, same as existing
    // is_charging logic in IternioTelemetryClient).

    private fun di(gear: Int? = null, chargeGunState: Int? = null, speed: Int? = 0) = DiParsData(
        soc = 50, speed = speed, mileage = 0.0, power = null,
        chargeGunState = chargeGunState,
        maxBatTemp = null, avgBatTemp = null, minBatTemp = null,
        chargingStatus = null, batteryCapacityKwh = null,
        totalElecConsumption = null,
        voltage12v = null, maxCellVoltage = null, minCellVoltage = null,
        exteriorTemp = null, gear = gear, powerState = null, insideTemp = null,
        acStatus = null, acTemp = null, fanLevel = null, acCirc = null,
        doorFL = null, doorFR = null, doorRL = null, doorRR = null,
        windowFL = null, windowFR = null, windowRL = null, windowRR = null,
        sunroof = null, trunk = null, hood = null, seatbeltFL = null, lockFL = null,
        tirePressFL = null, tirePressFR = null, tirePressRL = null, tirePressRR = null,
        driveMode = null, workMode = null, autoPark = null, rain = null,
        lightLow = null, drl = null
    )

    @Test
    fun `classifyFromDiPars gear 1 (P) is PARKED`() {
        assertEquals(TelemetryState.PARKED, IternioIntervalPolicy.classifyFromDiPars(di(gear = 1)))
    }

    @Test
    fun `classifyFromDiPars gear 4 (D) is DRIVING`() {
        assertEquals(TelemetryState.DRIVING, IternioIntervalPolicy.classifyFromDiPars(di(gear = 4)))
    }

    @Test
    fun `classifyFromDiPars chargeGunState 2 beats driving gear`() {
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classifyFromDiPars(di(gear = 4, chargeGunState = 2)))
    }

    @Test
    fun `classifyFromDiPars chargeGunState 2 beats parked gear`() {
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classifyFromDiPars(di(gear = 1, chargeGunState = 2)))
    }

    @Test
    fun `classifyFromDiPars null gear and zero speed is PARKED`() {
        // Reduced-payload silence with no movement signal → conservative
        // 30 s keep-alive instead of 1 Hz spam.
        assertEquals(TelemetryState.PARKED, IternioIntervalPolicy.classifyFromDiPars(di(gear = null, speed = 0)))
    }

    @Test
    fun `classifyFromDiPars null gear and null speed is PARKED`() {
        // Both null = DiPars fully silent. Treat as PARKED.
        assertEquals(TelemetryState.PARKED, IternioIntervalPolicy.classifyFromDiPars(di(gear = null, speed = null)))
    }

    @Test
    fun `classifyFromDiPars null gear but moving (speed greater than 0) is DRIVING`() {
        // Gear missing but speed > 0 means real driving with partial payload —
        // upgrade to 1 Hz cadence so ABRP doesn't lose movement samples.
        // This is the only place where speed influences cadence; we use it
        // only as a fallback when gear isn't available.
        assertEquals(TelemetryState.DRIVING, IternioIntervalPolicy.classifyFromDiPars(di(gear = null, speed = 5)))
    }

    @Test
    fun `classifyFromDiPars chargeGunState 3 (DC) is CHARGING`() {
        // DC fast charge — gunConnectState 3 from DiPars (rare but observed on
        // some firmwares). Same DCFC_GUN_STATES whitelist as IternioTelemetryClient.
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classifyFromDiPars(di(gear = 1, chargeGunState = 3)))
    }

    @Test
    fun `classifyFromDiPars chargeGunState 4 (AC_DC combo) is CHARGING`() {
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classifyFromDiPars(di(gear = 1, chargeGunState = 4)))
    }

    @Test
    fun `classifyFromDiPars chargeGunState 5 (VTOL) is CHARGING`() {
        assertEquals(TelemetryState.CHARGING, IternioIntervalPolicy.classifyFromDiPars(di(gear = 1, chargeGunState = 5)))
    }
}
