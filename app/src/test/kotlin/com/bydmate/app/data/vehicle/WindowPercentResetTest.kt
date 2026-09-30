package com.bydmate.app.data.vehicle

import com.bydmate.app.data.vehicle.WindowCarModel.Companion.DRIVER_CTRL
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.DRIVER_POS
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.PASSENGER_POS
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.RESET
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.REAR_LEFT_POS
import com.bydmate.app.data.vehicle.WindowCarModel.Companion.REAR_RIGHT_POS
import com.bydmate.app.data.vehicle.WindowCarModel.Rule
import io.mockk.coEvery
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * The window target-position fid is a held request, not a one-shot command: BYD's own apps
 * (autovoice CarWindowApiImpl.setLeftFrontWindowPercent, BydMyCar) write the percent, wait
 * 300 ms and write 255 ("no request") to the same fid. Every percent write of ours must end the
 * same way, whatever the command's outcome, so that no target is left on the bus.
 */
class WindowPercentResetTest {

    private fun TestScope.car(rule: Rule = Rule.ALWAYS) = WindowCarModel(rule) { testScheduler.currentTime }

    /** Each percent write on [fid] is followed by 255 on the same fid at least 300 ms later,
     *  and the next percent write on that fid comes after that reset. */
    private fun assertEveryPercentReset(car: WindowCarModel, fid: Int) {
        val onFid = car.writes.filter { it.fid == fid }
        assertTrue("no write on $fid", onFid.isNotEmpty())
        assertEquals("the last value on $fid: $onFid", RESET, onFid.last().value)
        onFid.forEachIndexed { i, w ->
            if (w.value == RESET) return@forEachIndexed
            val next = onFid.getOrNull(i + 1)
            assertEquals("percent ${w.value} on $fid not followed by its reset: $onFid", RESET, next?.value)
            assertTrue("reset ${next!!.atMs - w.atMs} ms after ${w.value}: $onFid", next.atMs - w.atMs >= 300)
        }
    }

    @Test fun `each window's percent write is followed by 255 on the same fid 300 ms later`() = runTest {
        val cases: List<Pair<Int, suspend (VehicleApi) -> Result<Unit>>> = listOf(
            DRIVER_POS to { api -> api.writeWindowDriver(10) },
            PASSENGER_POS to { api -> api.writeWindowPassenger(10) },
            REAR_LEFT_POS to { api -> api.writeWindowRearLeft(10) },
            REAR_RIGHT_POS to { api -> api.writeWindowRearRight(10) },
        )
        for ((fid, command) in cases) {
            val car = car()
            assertTrue(command(car.api()).isSuccess)
            assertEquals(listOf(10, RESET), car.writesTo(fid))
            assertEveryPercentReset(car, fid)
        }
    }

    @Test fun `each window of a composite command is reset`() = runTest {
        val car = car()

        assertTrue(car.api().dispatch("车窗通风").isSuccess)

        for (fid in listOf(DRIVER_POS, PASSENGER_POS, REAR_LEFT_POS, REAR_RIGHT_POS)) {
            assertEquals(listOf(10, RESET), car.writesTo(fid))
            assertEveryPercentReset(car, fid)
        }
    }

    @Test fun `open and close on the CTRL fid are not followed by a reset`() = runTest {
        val car = car()
        val api = car.api()

        assertTrue(api.dispatch("主驾打开100").isSuccess)
        assertTrue(api.dispatch("主驾打开0").isSuccess)

        assertEquals(listOf(1, 2), car.writesTo(DRIVER_CTRL))
        assertTrue(car.writes.toString(), car.writes.none { it.value == RESET })
    }

    /** Glass that never moves: the percent, the +1 nudge, the anchor and the target again, each
     *  one released before the next write to the fid. */
    @Test fun `the failure branch resets every write and ends on the reset`() = runTest {
        val car = car().apply { frozen = true }

        val result = car.api().writeWindowDriver(10)

        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
        assertEquals(listOf(10, RESET, 11, RESET, 0, RESET, 10, RESET), car.writesTo(DRIVER_POS))
        assertEveryPercentReset(car, DRIVER_POS)
    }

    @Test fun `a stuck composite command ends every window on the reset`() = runTest {
        val car = car().apply { frozen = true }

        assertTrue(car.api().dispatch("前排车窗半开").isFailure)

        for (fid in listOf(DRIVER_POS, PASSENGER_POS)) {
            assertEquals(listOf(50, RESET, 51, RESET, 0, RESET, 50, RESET), car.writesTo(fid))
            assertEveryPercentReset(car, fid)
        }
    }

    @Test fun `a refused percent write is still reset`() = runTest {
        val car = car()
        car.refuse = { fid, value -> fid == DRIVER_POS && value == 10 }

        val result = car.api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertEquals(listOf(10, RESET), car.writesTo(DRIVER_POS))
        assertEveryPercentReset(car, DRIVER_POS)
    }

    @Test fun `a refused anchor write inside the failure branch is still reset`() = runTest {
        val car = car().apply { frozen = true }
        // The anchor is the third percent write on the fid.
        var percents = 0
        car.refuse = { fid, value -> fid == DRIVER_POS && value != RESET && ++percents == 3 }

        val result = car.api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertEquals(listOf(10, RESET, 11, RESET, 0, RESET), car.writesTo(DRIVER_POS))
        assertEveryPercentReset(car, DRIVER_POS)
    }

    @Test fun `a percent write that throws is still reset`() = runTest {
        val car = car()
        val api = car.api()
        coEvery { car.helper.write(1001, DRIVER_POS, 10) } coAnswers {
            car.write(DRIVER_POS, 10)
            throw IOException("binder died")
        }

        assertTrue(api.writeWindowDriver(10).isFailure)
        assertEquals(listOf(10, RESET), car.writesTo(DRIVER_POS))
        assertEveryPercentReset(car, DRIVER_POS)
    }

    @Test fun `a command cancelled during the percent write is still reset`() = runTest {
        val car = car()
        val api = car.api()
        val inWrite = CompletableDeferred<Unit>()
        coEvery { car.helper.write(1001, DRIVER_POS, 10) } coAnswers {
            car.write(DRIVER_POS, 10)
            inWrite.complete(Unit)
            awaitCancellation()
        }

        val job = launch { api.writeWindowDriver(10) }
        inWrite.await()
        job.cancel()
        job.join()

        assertEquals(listOf(10, RESET), car.writesTo(DRIVER_POS))
        assertEveryPercentReset(car, DRIVER_POS)
    }

    @Test fun `a command cancelled while the reset is pending still resets`() = runTest {
        val car = car()
        val api = car.api()

        val job = launch { api.writeWindowDriver(10) }
        runCurrent()
        advanceTimeBy(100)
        assertEquals(listOf(10), car.writesTo(DRIVER_POS))
        job.cancel()
        job.join()

        assertEquals(listOf(10, RESET), car.writesTo(DRIVER_POS))
        assertEveryPercentReset(car, DRIVER_POS)
    }

    @Test fun `a refused reset is retried once`() = runTest {
        val car = car()
        var resets = 0
        car.refuse = { fid, value -> fid == DRIVER_POS && value == RESET && ++resets == 1 }

        assertTrue(car.api().writeWindowDriver(10).isSuccess)
        assertEquals(listOf(10, RESET, RESET), car.writesTo(DRIVER_POS))
    }

    @Test fun `a reset refused twice is not retried again and the command outcome stands`() = runTest {
        val car = car()
        car.refuse = { fid, value -> fid == DRIVER_POS && value == RESET }

        assertTrue(car.api().writeWindowDriver(10).isSuccess)
        assertEquals(listOf(10, RESET, RESET), car.writesTo(DRIVER_POS))
    }

    @Test fun `255 is not a user target on a percent fid`() = runTest {
        val car = car()

        val result = car.api().writeWindowDriver(RESET)

        assertTrue(result.exceptionOrNull() is VehicleWriteError.OutOfRange)
        assertTrue(car.writes.toString(), car.writes.isEmpty())
    }
}
