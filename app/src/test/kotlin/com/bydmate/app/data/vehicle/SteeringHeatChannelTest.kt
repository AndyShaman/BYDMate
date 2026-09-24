package com.bydmate.app.data.vehicle

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SteeringHeatChannelTest {
    /**
     * A car: [state] is what the state fid reads; a write on an action listed in [effective]
     * moves it to the written value. Every write answers [status] regardless — status=1 alone
     * proves nothing, which is the point of the readback. [reads] override the next reads in
     * order (before, readbacks, pre-fallback check, …); once it runs out, [state] is read.
     */
    private class FakeCar(
        var state: Int?,
        private val effective: Set<String> = emptySet(),
        private val status: WriteOutcome = WriteOutcome.REAL,
        reads: List<Int?> = emptyList(),
        private val readFails: Boolean = false,
    ) {
        private val script = ArrayDeque(reads)
        val writes = mutableListOf<Pair<String, Int>>()
        val writer = SeatWriter { name, value ->
            writes += name to value
            if (name in effective) state = value
            status
        }
        val readback = SteeringHeatReadback {
            if (readFails) error("binder died")
            if (script.isNotEmpty()) script.removeFirst() else state
        }
    }

    private fun channel(car: FakeCar) = SteeringHeatChannel(car.writer, car.readback)

    @Test fun `on succeeds on dev 1023 without touching the fallback`() = runTest {
        val car = FakeCar(state = 1, effective = setOf("steering_heat_on"))
        val outcome = channel(car).actuate(on = true)
        assertEquals(SteeringHeatChannel.Result.OK, outcome.result)
        assertEquals("OK", outcome.verdict)
        assertEquals(2, outcome.state)
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `off succeeds on dev 1023`() = runTest {
        val car = FakeCar(state = 2, effective = setOf("steering_heat_off"))
        assertEquals(SteeringHeatChannel.Result.OK, channel(car).actuate(on = false).result)
        assertEquals(listOf("steering_heat_off" to 1), car.writes)
    }

    @Test fun `state 0 before the write means no heater and nothing is written`() = runTest {
        val car = FakeCar(state = 0)
        val outcome = channel(car).actuate(on = true)
        assertEquals(SteeringHeatChannel.Result.NOT_EQUIPPED, outcome.result)
        assertEquals(emptyList<Pair<String, Int>>(), car.writes)
    }

    @Test fun `state 0 after the write means no heater and skips the fallback`() = runTest {
        val car = FakeCar(state = 0, reads = listOf(1))
        assertEquals(SteeringHeatChannel.Result.NOT_EQUIPPED, channel(car).actuate(on = true).result)
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `65535 is a lost CAN link, not a missing heater, and skips the fallback`() = runTest {
        val car = FakeCar(state = 65535, reads = listOf(2))
        val outcome = channel(car).actuate(on = false)
        assertEquals(SteeringHeatChannel.Result.UNCONFIRMED, outcome.result)
        assertEquals("can link lost", outcome.verdict)
        assertEquals(listOf("steering_heat_off" to 1), car.writes)
    }

    @Test fun `unchanged state on dev 1023 falls back to dev 1000 and succeeds there`() = runTest {
        val car = FakeCar(state = 1, effective = setOf("wheel_heat_on"))
        val outcome = channel(car).actuate(on = true)
        assertEquals(SteeringHeatChannel.Result.OK, outcome.result)
        assertEquals("fallback dev=1000 OK", outcome.verdict)
        assertEquals(1000, outcome.dev)
        assertEquals(listOf("steering_heat_on" to 2, "wheel_heat_on" to 2), car.writes)
    }

    @Test fun `no effect on both channels is reported as no effect`() = runTest {
        val car = FakeCar(state = 2)
        val outcome = channel(car).actuate(on = false)
        assertEquals(SteeringHeatChannel.Result.NO_EFFECT, outcome.result)
        assertEquals("no effect", outcome.verdict)
        assertEquals(listOf("steering_heat_off" to 1, "wheel_heat_off" to 1), car.writes)
    }

    @Test fun `an unreadable state is unconfirmed and never triggers the fallback`() = runTest {
        val car = FakeCar(state = null)
        val outcome = channel(car).actuate(on = true)
        assertEquals(SteeringHeatChannel.Result.UNCONFIRMED, outcome.result)
        assertEquals("unconfirmed", outcome.verdict)
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `a throwing read is unconfirmed and never triggers the fallback`() = runTest {
        val car = FakeCar(state = 1, readFails = true)
        assertEquals(SteeringHeatChannel.Result.UNCONFIRMED, channel(car).actuate(on = true).result)
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `sentinels outside the state set are unconfirmed`() = runTest {
        for (sentinel in listOf(-1, -10011, 3)) {
            val car = FakeCar(state = sentinel, reads = listOf(1))
            val outcome = channel(car).actuate(on = true)
            assertEquals("read=$sentinel", SteeringHeatChannel.Result.UNCONFIRMED, outcome.result)
            assertEquals(listOf("steering_heat_on" to 2), car.writes)
        }
    }

    @Test fun `a state that arrives after the readback window is OK late without a fallback`() = runTest {
        // before, two readbacks still off, then the pre-fallback check sees the heater on.
        val car = FakeCar(state = 2, reads = listOf(1, 1, 1))
        val outcome = channel(car).actuate(on = true)
        assertEquals(SteeringHeatChannel.Result.OK, outcome.result)
        assertEquals("OK late", outcome.verdict)
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `the pre-fallback check stops the fallback on 0 and on an unknown read`() = runTest {
        val absent = FakeCar(state = 0, reads = listOf(1, 1, 1))
        assertEquals(SteeringHeatChannel.Result.NOT_EQUIPPED, channel(absent).actuate(on = true).result)
        assertEquals(listOf("steering_heat_on" to 2), absent.writes)

        val unknown = FakeCar(state = null, reads = listOf(1, 1, 1))
        assertEquals(SteeringHeatChannel.Result.UNCONFIRMED, channel(unknown).actuate(on = true).result)
        assertEquals(listOf("steering_heat_on" to 2), unknown.writes)
    }

    @Test fun `a daemon that does not answer stops at the first write`() = runTest {
        val car = FakeCar(state = 1, status = WriteOutcome.TRANSIENT)
        assertEquals(SteeringHeatChannel.Result.UNREACHABLE, channel(car).actuate(on = true).result)
        assertEquals(listOf("steering_heat_on" to 2), car.writes)
    }

    @Test fun `an OFF issued while ON waits for its fallback supersedes it, no late ON write`() = runTest {
        // dev 1023 does nothing, dev 1000 works: the ON would reach its fallback write.
        val car = FakeCar(state = 1, effective = setOf("wheel_heat_on", "wheel_heat_off"))
        val ch = channel(car)
        val on = async { ch.actuate(on = true) }
        delay(100)
        val off = async { ch.actuate(on = false) }

        assertEquals("superseded", on.await().verdict)
        assertEquals(SteeringHeatChannel.Result.UNCONFIRMED, on.await().result)
        assertEquals(SteeringHeatChannel.Result.OK, off.await().result)
        assertEquals(1, car.state)
        assertEquals(listOf("steering_heat_on" to 2, "steering_heat_off" to 1), car.writes)
    }

    @Test fun `commands run one at a time, the second sees the state the first left`() = runTest {
        val car = FakeCar(state = 1, effective = setOf("steering_heat_on", "steering_heat_off"))
        val ch = channel(car)
        val on = async { ch.actuate(on = true) }
        delay(100)
        val off = async { ch.actuate(on = false) }

        assertEquals("OK", on.await().verdict)
        assertEquals("OK", off.await().verdict)
        assertEquals(1, car.state)
        assertEquals(listOf("steering_heat_on" to 2, "steering_heat_off" to 1), car.writes)
    }
}
