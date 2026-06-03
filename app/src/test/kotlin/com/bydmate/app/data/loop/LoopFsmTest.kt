package com.bydmate.app.data.loop

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Test

// Per DiParsData.kt:21 — powerState: 0=OFF, 1=ON (ignition active, parked), 2=DRIVE.
// Per DiParsData.kt:20 — gear: 1=P, 2=R, 3=N, 4=D.
// Per DiParsData.kt:5  — speed: Int? in km/h.
// chargeGunState != 0 == connected.

class LoopFsmTest {

    @Test fun `charge gun connected always wins`() {
        assertEquals(LoopState.CHARGE, LoopFsm.classify(diParsData(chargeGunState = 1, powerState = 2)))
        assertEquals(LoopState.CHARGE, LoopFsm.classify(diParsData(chargeGunState = 1, powerState = 0)))
    }

    @Test fun `powerState DRIVE with speed = drive cadence`() {
        assertEquals(LoopState.DRIVE, LoopFsm.classify(diParsData(powerState = 2, speed = 25)))
    }

    @Test fun `powerState ON without movement = parked`() {
        assertEquals(LoopState.PARKED, LoopFsm.classify(diParsData(powerState = 1, speed = 0)))
        assertEquals(LoopState.PARKED, LoopFsm.classify(diParsData(powerState = 1, speed = null)))
    }

    @Test fun `powerState DRIVE without speed = parked`() {
        // DRIVE with speed=0 (foot on brake, ready) reads as parked cadence — battery
        // not draining like real motion.
        assertEquals(LoopState.PARKED, LoopFsm.classify(diParsData(powerState = 2, speed = 0)))
    }

    @Test fun `powerState OFF = idle`() {
        assertEquals(LoopState.IDLE, LoopFsm.classify(diParsData(powerState = 0)))
    }

    @Test fun `power null falls back to gear D + speed = drive`() {
        assertEquals(LoopState.DRIVE, LoopFsm.classify(diParsData(powerState = null, gear = 4, speed = 5)))
    }

    @Test fun `power null gear P = idle`() {
        assertEquals(LoopState.IDLE, LoopFsm.classify(diParsData(powerState = null, gear = 1)))
    }

    @Test fun `everything null = idle`() {
        assertEquals(LoopState.IDLE, LoopFsm.classify(diParsData()))
    }
}
