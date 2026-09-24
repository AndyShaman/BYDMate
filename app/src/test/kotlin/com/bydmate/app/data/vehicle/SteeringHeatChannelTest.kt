package com.bydmate.app.data.vehicle

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringHeatChannelTest {
    /**
     * A car: [state] is what the state fid reads; a write on an action listed in [effective]
     * moves it to the written value. Every write answers [status] regardless — status=1 alone
     * proves nothing, which is the point of the readback.
     */
    private class FakeCar(
        var state: Int?,
        private val effective: Set<String> = emptySet(),
        private val status: WriteOutcome = WriteOutcome.REAL,
    ) {
        val writes = mutableListOf<Pair<String, Int>>()
        val writer = SeatWriter { name, value ->
            writes += name to value
            if (name in effective) state = value
            status
        }
        val readback = SteeringHeatReadback { state }
    }

    private fun channel(car: FakeCar) = SteeringHeatChannel(car.writer, car.readback)

    @Test fun `on succeeds on dev 1023 without touching the fallback`() = runTest {
        val car = FakeCar(state = 1, effective = setOf("steering_heat_on"))
        assertEquals(SteeringHeatChannel.Result.OK, channel(car).actuate(on = true))
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `off succeeds on dev 1023`() = runTest {
        val car = FakeCar(state = 2, effective = setOf("steering_heat_off"))
        assertEquals(SteeringHeatChannel.Result.OK, channel(car).actuate(on = false))
        assertEquals(listOf("steering_heat_off" to 1), car.writes)
    }

    @Test fun `state 0 means no heater and skips the fallback`() = runTest {
        val car = FakeCar(state = 0)
        assertEquals(SteeringHeatChannel.Result.NOT_EQUIPPED, channel(car).actuate(on = true))
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `state 65535 means no heater either`() = runTest {
        val car = FakeCar(state = 65535)
        assertEquals(SteeringHeatChannel.Result.NOT_EQUIPPED, channel(car).actuate(on = false))
        assertEquals(listOf("steering_heat_off" to 1), car.writes)
    }

    @Test fun `unchanged state on dev 1023 falls back to dev 1000 and succeeds there`() = runTest {
        val car = FakeCar(state = 1, effective = setOf("wheel_heat_on"))
        assertEquals(SteeringHeatChannel.Result.OK, channel(car).actuate(on = true))
        assertEquals(listOf("steering_heat_on" to 2, "wheel_heat_on" to 2), car.writes)
    }

    @Test fun `no effect on both channels is reported as no effect`() = runTest {
        val car = FakeCar(state = 2)
        assertEquals(SteeringHeatChannel.Result.NO_EFFECT, channel(car).actuate(on = false))
        assertEquals(listOf("steering_heat_off" to 1, "wheel_heat_off" to 1), car.writes)
    }

    @Test fun `an unreadable state is no proof and goes through the fallback`() = runTest {
        val car = FakeCar(state = null)
        assertEquals(SteeringHeatChannel.Result.NO_EFFECT, channel(car).actuate(on = true))
        assertEquals(listOf("steering_heat_on" to 2, "wheel_heat_on" to 2), car.writes)
    }

    @Test fun `a daemon that does not answer stops at the first write`() = runTest {
        val car = FakeCar(state = 1, status = WriteOutcome.TRANSIENT)
        assertEquals(SteeringHeatChannel.Result.UNREACHABLE, channel(car).actuate(on = true))
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }
}
