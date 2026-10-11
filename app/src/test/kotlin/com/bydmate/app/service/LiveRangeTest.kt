package com.bydmate.app.service

import com.bydmate.app.data.nativestack.VehicleKind
import com.bydmate.app.data.remote.diParsData
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveRangeTest {

    @Test fun `a hybrid takes the car's own range and never runs the estimate`() = runTest {
        var estimated = 0
        val data = diParsData(soc = 60).copy(evRangeKm = 46)
        assertEquals(46.0, liveRangeKm(VehicleKind.HYBRID, data) { estimated++; 300.0 }!!, 0.0)
        assertNull(liveRangeKm(VehicleKind.HYBRID, data.copy(evRangeKm = null)) { estimated++; 300.0 })
        assertEquals(0, estimated)
    }

    @Test fun `an EV or an unconfirmed car keeps the estimate`() = runTest {
        val data = diParsData(soc = 60).copy(evRangeKm = 46)
        assertEquals(300.0, liveRangeKm(VehicleKind.EV, data) { 300.0 }!!, 0.0)
        assertEquals(300.0, liveRangeKm(VehicleKind.UNKNOWN, data) { 300.0 }!!, 0.0)
        assertNull(liveRangeKm(VehicleKind.EV, data) { null })
    }
}
