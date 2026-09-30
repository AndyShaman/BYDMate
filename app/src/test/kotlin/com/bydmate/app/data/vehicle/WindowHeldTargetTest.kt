package com.bydmate.app.data.vehicle

import com.bydmate.app.data.vehicle.WindowCarModel.Companion.DRIVER_POS
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.DRIVER_READ
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.RESET
import com.bydmate.app.data.vehicle.WindowCarModel.Rule
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression from the Song L user log of 2026-09-29 (fixture
 * native-stack-fixtures/window-songl-held-target-20260929.txt): BYDMate never released the
 * percent fid, so the last target stayed on the bus. The unit then ignored a repeated
 * "проветрить" (10) or "половина" (50) — and the +1 nudge with it — and drove the glass to the
 * held target again when the car woke up.
 */
class WindowHeldTargetTest {

    private fun TestScope.car(rule: Rule) = WindowCarModel(rule) { testScheduler.currentTime }

    private class LoggedWrite(val fid: Int, val value: Int)

    /** Our writes to the driver window, in log order. */
    private val loggedWrites: List<LoggedWrite> by lazy {
        fixture().mapNotNull { WRITE.find(it) }
            .map { LoggedWrite(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }
    }

    /** Did each percent write move the glass, in log order: a nudge line says its source value
     *  did not, a readback line gives the verdict of the value it names. */
    private val loggedPercentVerdicts: List<Pair<Int, Boolean>> by lazy {
        fixture().mapNotNull { line ->
            NUDGE.find(line)?.let { it.groupValues[1].toInt() to false }
                ?: READBACK.find(line)?.let { it.groupValues[1].toInt() to (it.groupValues[2] == "moved") }
        }
    }

    private fun fixture(): List<String> =
        requireNotNull(javaClass.classLoader?.getResource(FIXTURE)).readText().lines()
            .filterNot { it.startsWith("#") || it.isBlank() }

    /** The old write sequence (no reset) on the modelled Song L reproduces every verdict of the
     *  log, including the failed «проветрить → закрыть → проветрить» and «половина → закрыть →
     *  половина», and leaves the target held on the bus for the next wake-up. */
    @Test fun `the logged write sequence without a reset reproduces the user's failures`() = runTest {
        val car = car(Rule.SONG_L)
        // The successful "проветрить" of 17:48:36 was the last percent before the excerpt.
        car.hold(DRIVER_POS, 10)
        val verdicts = mutableListOf<Pair<Int, Boolean>>()
        for (w in loggedWrites) {
            val before = car.position(DRIVER_READ)
            car.write(w.fid, w.value)
            if (w.fid == DRIVER_POS) verdicts += w.value to (car.position(DRIVER_READ) != before)
        }

        assertEquals(loggedPercentVerdicts, verdicts)
        assertEquals(10, loggedPercentVerdicts.size)
        // After the final close the target 10 is still held: the next wake-up lowers the glass.
        assertEquals(0, car.position(DRIVER_READ))
        car.wake()
        assertEquals(10, car.position(DRIVER_READ))
    }

    @Test fun `with the reset a repeated vent works first time and nothing is replayed at wake-up`() = runTest {
        val car = car(Rule.SONG_L)
        val api = car.api()

        assertTrue(api.dispatch("主驾通风").isSuccess)
        assertTrue(api.dispatch("主驾打开0").isSuccess)
        assertTrue(api.dispatch("主驾通风").isSuccess)
        assertTrue(api.dispatch("主驾打开0").isSuccess)

        // No nudge, no anchor: every command moved the glass on its own write.
        assertEquals(listOf(10, RESET, 10, RESET), car.writesTo(DRIVER_POS))
        assertNull(car.held(DRIVER_POS))
        car.wake()
        assertEquals(0, car.position(DRIVER_READ))
    }

    @Test fun `with the reset a repeated half works first time and nothing is replayed at wake-up`() = runTest {
        val car = car(Rule.SONG_L)
        val api = car.api()

        assertTrue(api.dispatch("主驾半开").isSuccess)
        assertTrue(api.dispatch("主驾打开0").isSuccess)
        assertTrue(api.dispatch("主驾半开").isSuccess)
        assertTrue(api.dispatch("主驾打开0").isSuccess)

        assertEquals(listOf(50, RESET, 50, RESET), car.writesTo(DRIVER_POS))
        assertNull(car.held(DRIVER_POS))
        car.wake()
        assertEquals(0, car.position(DRIVER_READ))
    }

    /** The first command after the update meets the target the old version left: the reset of
     *  that first write clears it, the existing nudge then moves the glass. */
    @Test fun `the first command after the update clears the target the old version left`() = runTest {
        val car = car(Rule.SONG_L)
        car.hold(DRIVER_POS, 10)

        assertTrue(car.api().dispatch("主驾通风").isSuccess)

        assertEquals(listOf(10, RESET, 11, RESET), car.writesTo(DRIVER_POS))
        assertNull(car.held(DRIVER_POS))
    }

    /** Leopard 3 and any car that moves on every command: the same writes as before, each
     *  followed by its reset, same outcomes. */
    @Test fun `a car that moves on every command gets the old writes plus a reset each`() = runTest {
        for (rule in listOf(Rule.ALWAYS, Rule.LEOPARD_3)) {
            val car = car(rule)
            val api = car.api()
            val commands = listOf("主驾通风", "主驾打开0", "主驾通风", "主驾半开", "主驾打开0", "主驾半开")

            for (c in commands) assertTrue("$rule $c", api.dispatch(c).isSuccess)

            val percents = car.writesTo(DRIVER_POS)
            assertEquals("$rule", listOf(10, 10, 50, 50), percents.filter { it != RESET })
            assertEquals("$rule", listOf(10, RESET, 10, RESET, 50, RESET, 50, RESET), percents)
        }
    }

    private companion object {
        const val FIXTURE = "native-stack-fixtures/window-songl-held-target-20260929.txt"
        val WRITE = Regex("""HelperClient\(\d+\): write dev=1001 fid=(\d+) value=(\d+) """)
        val NUDGE = Regex("""window nudge: window_driver_pos (\d+) -> """)
        val READBACK = Regex("""window readback action=window_driver_pos .* value=(\d+) before=.* verdict=(\S+)""")
    }
}
