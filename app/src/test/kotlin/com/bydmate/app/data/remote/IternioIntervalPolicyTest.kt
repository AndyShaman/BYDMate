package com.bydmate.app.data.remote

import com.bydmate.app.data.remote.IternioIntervalPolicy.TelemetryState
import org.junit.Assert.assertEquals
import org.junit.Test

class IternioIntervalPolicyTest {

    // --- classification ---

    private class ClassifyCase(val label: String, val charging: Boolean, val parked: Boolean, val expected: TelemetryState)

    @Test
    fun `classify maps charging and parked flags to a telemetry state`() {
        val cases = listOf(
            ClassifyCase("not charging and not parked classifies as DRIVING", false, false, TelemetryState.DRIVING),
            ClassifyCase("parked without charge gun classifies as PARKED", false, true, TelemetryState.PARKED),
            ClassifyCase("charging beats parked - plug in P is CHARGING", true, true, TelemetryState.CHARGING),
            // Physically the car cannot be in D with the gun connected, but the
            // policy must not produce DRIVING in that edge state — charging is
            // always the safe call when the gun is connected.
            ClassifyCase("charging beats driving - degenerate but charging wins", true, false, TelemetryState.CHARGING),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected,
                IternioIntervalPolicy.classify(charging = c.charging, parked = c.parked))
        }
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

    private class DiParsCase(
        val label: String,
        val gear: Int?,
        val chargeGunState: Int?,
        val speed: Int?,
        val expected: TelemetryState,
    )

    @Test
    fun `classifyFromDiPars maps gear, gun state and speed to a telemetry state`() {
        val cases = listOf(
            DiParsCase("gear 1 (P) is PARKED", 1, null, 0, TelemetryState.PARKED),
            DiParsCase("gear 4 (D) is DRIVING", 4, null, 0, TelemetryState.DRIVING),
            DiParsCase("chargeGunState 2 beats driving gear", 4, 2, 0, TelemetryState.CHARGING),
            DiParsCase("chargeGunState 2 beats parked gear", 1, 2, 0, TelemetryState.CHARGING),
            // Reduced-payload silence with no movement signal → conservative
            // 30 s keep-alive instead of 1 Hz spam.
            DiParsCase("null gear and zero speed is PARKED", null, null, 0, TelemetryState.PARKED),
            // Both null = DiPars fully silent. Treat as PARKED.
            DiParsCase("null gear and null speed is PARKED", null, null, null, TelemetryState.PARKED),
            // Gear missing but speed > 0 means real driving with partial payload —
            // upgrade to 1 Hz cadence so ABRP doesn't lose movement samples.
            // This is the only place where speed influences cadence; we use it
            // only as a fallback when gear isn't available.
            DiParsCase("null gear but moving (speed greater than 0) is DRIVING", null, null, 5, TelemetryState.DRIVING),
            // DC fast charge — gunConnectState 3 from DiPars (rare but observed on
            // some firmwares). Same DCFC_GUN_STATES whitelist as IternioTelemetryClient.
            DiParsCase("chargeGunState 3 (DC) is CHARGING", 1, 3, 0, TelemetryState.CHARGING),
            DiParsCase("chargeGunState 4 (AC_DC combo) is CHARGING", 1, 4, 0, TelemetryState.CHARGING),
            DiParsCase("chargeGunState 5 (VTOL) is CHARGING", 1, 5, 0, TelemetryState.CHARGING),
        )
        for (c in cases) {
            assertEquals("case: ${c.label}", c.expected,
                IternioIntervalPolicy.classifyFromDiPars(di(gear = c.gear, chargeGunState = c.chargeGunState, speed = c.speed)))
        }
    }
}
