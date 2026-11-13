package com.bydmate.app.data.trips

import com.bydmate.app.data.remote.diParsData
import org.junit.Assert.assertEquals
import org.junit.Test

/** The electric counter of the trip's end edge outlives a tick where only it went silent. */
class ReadableOnTickElecTest {

    @Test fun `a later ON tick without the electric counter keeps the last one read`() {
        val edge = ReadableOnTick()
        edge.update(
            diParsData(powerState = 2, mileage = 1040.0, soc = 60, totalElecConsumption = 101.5)
                .copy(fuelTotalL = 512.0),
            nowMs = 10_000, isOnTick = true,
        )
        // Power-off takes the modules down one by one: fuel still answers, the BMS counter no longer.
        edge.update(
            diParsData(powerState = 2, mileage = 1040.0, soc = 60, totalElecConsumption = null)
                .copy(fuelTotalL = 512.0),
            nowMs = 11_000, isOnTick = true,
        )
        assertEquals(101.5, edge.totalElecConsumption!!, 1e-9)
    }
}
