package com.bydmate.app.data.vehicle

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #408: steering wheel heat level 1..3 (or 1..5).
 * Covers steeringHeatLevels(config), SteeringHeatChannel.setLevel(level),
 * existing actuate(on) invariance, and mutex serialization during the 300 ms wait.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SteeringHeatLevelTest {

    private val allowlist = WriteAllowlist.loadProduction { "{}" }

    @Suppress("LongParameterList") // one knob per scripted car input
    private class FakeCar(
        private val scope: TestScope,
        var config: Int? = 2,
        var state: Int? = 1,
        var gear: Int? = 3,
        private val effective: Set<String> = emptySet(),
        private val levelEffective: Boolean = true,
        private val status: WriteOutcome = WriteOutcome.REAL,
        reads: List<Int?> = emptyList(),
        gearReads: List<Int?> = emptyList(),
    ) {
        private val stateScript = ArrayDeque(reads)
        private val gearScript = ArrayDeque(gearReads)
        val writes = mutableListOf<Triple<String, Int, Long>>()

        val writer = SeatWriter { name, value ->
            writes += Triple(name, value, scope.testScheduler.currentTime)
            if (name in effective) state = value
            if (levelEffective && name !in ON_OFF_ACTIONS) gear = value
            status
        }

        val readback = SteeringHeatReadback {
            if (stateScript.isNotEmpty()) stateScript.removeFirst() else state
        }

        fun readFid(fid: Int): Int? = when (fid) {
            CONFIG_FID -> config
            STATE_FID -> if (stateScript.isNotEmpty()) stateScript.removeFirst() else state
            GEAR_FID -> if (gearScript.isNotEmpty()) gearScript.removeFirst() else gear
            else -> error("unexpected steering fid $fid")
        }
    }

    private fun channel(car: FakeCar) =
        SteeringHeatChannel(car.writer, car.readback) { fid: Int -> car.readFid(fid) }

    private fun assertIsLevelWrite(actionName: String, expectedValue: Int, actualValue: Int) {
        assertTrue("expected level write, got on/off action=$actionName", actionName !in ON_OFF_ACTIONS)
        assertEquals(expectedValue, actualValue)
        val entry = allowlist.find(actionName)
        assertNotNull("action=$actionName must exist in WriteAllowlist", entry)
        assertEquals(1023, entry!!.dev)
        assertEquals(LEVEL_WRITE_FID, entry.writeFid)
    }

    // Case 1: steeringHeatLevels(config)
    @Test fun `steeringHeatLevels maps config values to level counts`() {
        val expected = listOf(
            1 to 0,
            4 to 0,
            2 to 3,
            5 to 3,
            3 to 5,
            6 to 5,
            0 to 0,
            null to 0,
        )
        for ((config, levels) in expected) {
            assertEquals("config=$config", levels, steeringHeatLevels(config))
        }
    }

    // Case 2: setLevel(2), config 2, state 1, on succeeds -> on write, >= 300 ms later 1276256274=2, readback 2 -> OK
    @Test fun `setLevel turns heat on first, waits at least 300 ms, writes level and confirms`() = runTest {
        val car = FakeCar(this, config = 2, state = 1, effective = setOf("steering_heat_on"))
        val outcome = channel(car).setLevel(2)

        assertEquals(SteeringHeatChannel.Result.OK, outcome.result)
        assertEquals(2, car.writes.size)
        assertEquals("steering_heat_on" to 2, car.writes[0].first to car.writes[0].second)
        assertIsLevelWrite(car.writes[1].first, 2, car.writes[1].second)
        val gapMs = car.writes[1].third - car.writes[0].third
        assertTrue("level write gap=${gapMs}ms must be >= 300 ms", gapMs >= 300L)
    }

    // Case 3: setLevel(1), state already 2 -> only 1276256274 = 1
    @Test fun `setLevel skips on write when state is already 2`() = runTest {
        val car = FakeCar(this, config = 2, state = 2)
        val outcome = channel(car).setLevel(1)

        assertEquals(SteeringHeatChannel.Result.OK, outcome.result)
        assertEquals(1, car.writes.size)
        assertIsLevelWrite(car.writes[0].first, 1, car.writes[0].second)
    }

    // Case 4: config 1 -> NOT_EQUIPPED, no write; config null or -10011 -> UNREACHABLE, no write
    @Test fun `setLevel returns NOT_EQUIPPED on on-off config and UNREACHABLE on unreadable config`() = runTest {
        for (cfg in listOf(1, 4)) {
            val car = FakeCar(this, config = cfg, state = 1)
            val outcome = channel(car).setLevel(2)
            assertEquals("config=$cfg", SteeringHeatChannel.Result.NOT_EQUIPPED, outcome.result)
            assertTrue("config=$cfg must write nothing", car.writes.isEmpty())
        }
        for (cfg in listOf(null, -10011, -10013, 1048575)) {
            val car = FakeCar(this, config = cfg, state = 1)
            val outcome = channel(car).setLevel(2)
            assertEquals("config=$cfg", SteeringHeatChannel.Result.UNREACHABLE, outcome.result)
            assertTrue("config=$cfg must write nothing", car.writes.isEmpty())
        }
    }

    // Case 5: setLevel(2), state 1, on fails (state stays 1, or reads 0 / 65535 / null) -> no level write, not OK
    @Test fun `setLevel writes no level and is not OK when switching on fails`() = runTest {
        val failureCases = listOf(
            "stays 1" to emptyList(),
            "reads 0" to listOf(1, 0),
            "reads 65535" to listOf(1, 65535),
            "reads null" to listOf(1, null),
        )
        for ((label, reads) in failureCases) {
            val car = FakeCar(this, config = 2, state = 1, reads = reads)
            val outcome = channel(car).setLevel(2)
            assertNotEquals("case=$label must not be OK", SteeringHeatChannel.Result.OK, outcome.result)
            assertTrue(
                "case=$label must make no level write, got ${car.writes}",
                car.writes.none { it.first !in ON_OFF_ACTIONS },
            )
        }
    }

    // Case 6: setLevel(5), config 2 -> writes 3
    @Test fun `setLevel clamps requested level 5 to 3 on a three-level car`() = runTest {
        val car = FakeCar(this, config = 2, state = 2)
        val outcome = channel(car).setLevel(5)

        assertEquals(SteeringHeatChannel.Result.OK, outcome.result)
        assertEquals(1, car.writes.size)
        assertIsLevelWrite(car.writes[0].first, 3, car.writes[0].second)
    }

    // Case 7: Level readback stays 3 after writing 2 -> UNCONFIRMED
    @Test fun `setLevel returns UNCONFIRMED when gear readback stays 3 after writing 2`() = runTest {
        val car = FakeCar(this, config = 2, state = 2, gear = 3, levelEffective = false)
        val outcome = channel(car).setLevel(2)

        assertEquals(SteeringHeatChannel.Result.UNCONFIRMED, outcome.result)
        assertEquals(1, car.writes.size)
        assertIsLevelWrite(car.writes[0].first, 2, car.writes[0].second)
    }

    // Case 9: Existing actuate(true/false) keeps the same writes and results
    @Test fun `existing actuate on and off keep todays writes and results`() = runTest {
        val onCar = FakeCar(this, config = 1, state = 1, effective = setOf("steering_heat_on"))
        val onOutcome = channel(onCar).actuate(on = true)
        assertEquals(SteeringHeatChannel.Result.OK, onOutcome.result)
        assertEquals("OK", onOutcome.verdict)
        assertEquals(listOf("steering_heat_on" to 2), onCar.writes.map { it.first to it.second })

        val offCar = FakeCar(this, config = null, state = 2, effective = setOf("steering_heat_off"))
        val offOutcome = channel(offCar).actuate(on = false)
        assertEquals(SteeringHeatChannel.Result.OK, offOutcome.result)
        assertEquals("OK", offOutcome.verdict)
        assertEquals(listOf("steering_heat_off" to 1), offCar.writes.map { it.first to it.second })
    }

    // Case 10: A concurrent actuate(false) issued during setLevel's 300 ms wait does not interleave
    @Test fun `concurrent actuate false during setLevel 300 ms wait does not interleave`() = runTest {
        val car = FakeCar(this, config = 2, state = 2, effective = setOf("steering_heat_off"))
        val ch = channel(car)
        val lvl = async<SteeringHeatChannel.Outcome> { ch.setLevel(2) }
        delay(100)
        val off = async { ch.actuate(on = false) }

        assertEquals(SteeringHeatChannel.Result.OK, lvl.await().result)
        assertEquals(SteeringHeatChannel.Result.OK, off.await().result)
        assertEquals(1, car.state)
        assertEquals(2, car.writes.size)
        assertIsLevelWrite(car.writes[0].first, 2, car.writes[0].second)
        assertEquals("steering_heat_off" to 1, car.writes[1].first to car.writes[1].second)
    }

    private companion object {
        const val STATE_FID = 1116733454
        const val CONFIG_FID = 1116733496
        const val GEAR_FID = 1116733465
        const val LEVEL_WRITE_FID = 1276256274
        val ON_OFF_ACTIONS = setOf("steering_heat_on", "steering_heat_off", "wheel_heat_on", "wheel_heat_off")
    }
}
