package com.bydmate.app.data.push

import com.bydmate.app.data.nativestack.FidMap
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.diParsData
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FidPushApplierTest {

    /** tx 5 fields arrive as an int, tx 7 fields as a double — the other number is then unused. */
    private fun apply(field: String, intValue: Int = 0, doubleValue: Double = 0.0) =
        FidPushApplier.apply(diParsData(), field, intValue, doubleValue)

    @Test fun `the whole FidMap is subscribed`() {
        assertEquals(FidMap.all.size, FidPushApplier.PUSH_FIELDS.size)
        assertEquals(FidMap.all.map { it.field }, FidPushApplier.PUSH_FIELDS)
    }

    @Test fun `an enum field is patched with the raw value`() {
        val patched = FidPushApplier.apply(diParsData(turnSignal = 1), "turnSignal", 4, 0.0)
        assertEquals(4, patched?.turnSignal)
    }

    @Test fun `a percent field keeps the FidMap decoder`() {
        assertEquals(50, apply("windowFL", intValue = 50)?.windowFL)
        // INT_PERCENT rejects anything outside 0..100, exactly as on the poll path.
        assertNull(apply("windowFL", intValue = 255))
    }

    @Test fun `a sentinel value is not applied`() {
        assertNull(FidPushApplier.apply(diParsData(gear = 4), "gear", 65535, 0.0))
    }

    @Test fun `a scaled field uses the scale of the address in force`() {
        // mileage: INT_SCALED, tenths of a km on the compiled constant.
        assertEquals(1234.5, apply("mileage", intValue = 12345)?.mileage!!, 0.001)
    }

    @Test fun `a tx 7 field is decoded from the double the firmware sent`() {
        assertEquals(43, apply("soc", doubleValue = 43.0)?.soc)
        assertEquals(-12.5, apply("hvCurrent", doubleValue = -12.5)?.hvCurrent!!, 0.001)
        // -1.0f is the float "not initialized" sentinel on both paths.
        assertNull(apply("hvCurrent", doubleValue = -1.0))
    }

    @Test fun `a reading outside its physical envelope is dropped`() {
        assertEquals(37, apply("motorTempFront", intValue = 37)?.motorTempFront)
        // -40 is what the firmware reports for a motor the car does not have (#186).
        assertNull(apply("motorTempFront", intValue = -40))
        assertNull(apply("pedalAccel", intValue = 120))
        assertNull(apply("voltage12v", doubleValue = 0.0))
        assertNull(apply("minCellVoltage", intValue = 400))  // 0.4 V — BMS not reporting
    }

    @Test fun `patching a battery power factor recomputes the product`() {
        val withVoltage =
            FidPushApplier.apply(diParsData().copy(hvCurrent = 10.0), "hvVoltage", 500, 0.0)
        assertEquals(5000.0, withVoltage?.batteryPowerW!!, 0.001)
        val withCurrent =
            FidPushApplier.apply(diParsData().copy(hvVoltage = 500), "hvCurrent", 0, 10.0)
        assertEquals(5000.0, withCurrent?.batteryPowerW!!, 0.001)
    }

    @Test fun `fields the poll derives from are left to the poll`() {
        // chargingStatus, rain, avgBatTemp and the sticky drive mode are all computed from
        // several fids at once; patching one of them alone would contradict the snapshot.
        listOf("chargeGunState", "bmsState", "wiperRelay", "autoWipers", "maxBatTemp", "minBatTemp",
            "driveMode", "windowRRGen3").forEach {
            assertNull("$it must not be patched from push", apply(it, intValue = 1, doubleValue = 1.0))
        }
    }

    @Test fun `fields without a snapshot counterpart are not applied`() {
        assertNull(apply("bsdLeft", intValue = 2))
        assertNull(apply("soh", intValue = 90))
        assertNull(apply("seatCandLrseLevel1", intValue = 1))
        assertNull(apply("nosuchfield", intValue = 1))
    }

    @Test fun `a poll landing between the read and the write is not rolled back`() {
        val poll = diParsData(soc = 80, speed = 60)
        val flow = RacingStateFlow(MutableStateFlow(diParsData(soc = 79, speed = 0, turnSignal = 0)), poll)

        assertTrue(FidPushApplier.patch(flow, "turnSignal", 4, 0.0))

        // The patch is rebuilt on the snapshot the poll published, not on the one it started from.
        assertEquals(4, flow.value?.turnSignal)
        assertEquals(80, flow.value?.soc)
        assertEquals(60, flow.value?.speed)
    }

    @Test fun `without a snapshot nothing is patched`() {
        val flow = MutableStateFlow<DiParsData?>(null)
        assertFalse(FidPushApplier.patch(flow, "turnSignal", 4, 0.0))
        assertNull(flow.value)
    }

    @Test fun `a value the decoder rejects leaves the snapshot alone`() {
        val flow = MutableStateFlow<DiParsData?>(diParsData(windowFL = 10))
        assertFalse(FidPushApplier.patch(flow, "windowFL", 255, 0.0))
        assertEquals(10, flow.value?.windowFL)
    }

    /**
     * Publishes [poll] the first time the value is read, which is exactly the window the push had
     * to itself before: read snapshot, decode, write back.
     */
    private class RacingStateFlow(
        private val delegate: MutableStateFlow<DiParsData?>,
        private val poll: DiParsData,
    ) : MutableStateFlow<DiParsData?> by delegate {
        private var raced = false

        override var value: DiParsData?
            get() {
                val current = delegate.value
                if (!raced) {
                    raced = true
                    delegate.value = poll
                }
                return current
            }
            set(v) {
                delegate.value = v
            }
    }
}
