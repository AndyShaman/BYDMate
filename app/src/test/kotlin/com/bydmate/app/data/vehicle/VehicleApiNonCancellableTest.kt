package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies that VehicleApiImpl wraps composite and seat write sequences in
 * withContext(NonCancellable) so a hard stop that cancels the calling job after the
 * first write of a multi-write command has started cannot leave the car half-commanded.
 *
 * Test strategy: a CompletableDeferred is used to block the first write's mock so the
 * test can cancel the calling job while the sequence is mid-flight. The NonCancellable
 * context prevents the deferred.await() from being interrupted by the cancellation; all
 * writes in the sequence run to completion before the job is actually torn down.
 */
class VehicleApiNonCancellableTest {

    private val parsReader: ParsReader = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val helper: HelperClient = mockk()
    private val writeLogDao: VehicleWriteLogDao = mockk(relaxed = true)

    private val allowlist = WriteAllowlist(
        WriteAllowlist.LIVE_VALIDATED.associateBy { it.actionName.lowercase() }
    )

    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) {}
    }

    private val api: VehicleApi = VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao, seatStore)

    /**
     * Composite window command (后排车窗全开 = rear windows fully open) fans out to two
     * per-door % writes: window_rear_left_pos and window_rear_right_pos. Cancelling the
     * calling job after the first write has started but before it returns must NOT prevent
     * the second write from executing — the NonCancellable wrapper ensures both writes
     * complete before the cancellation is observed.
     */
    @Test fun `composite write completes both sub-writes even when calling job is cancelled after first write starts`() = runTest {
        val rl = allowlist.find("window_rear_left_pos")!!
        val rr = allowlist.find("window_rear_right_pos")!!
        val writeCount = AtomicInteger(0)

        // Gate: lets the test cancel the dispatch job while the first write is in progress.
        val firstWriteInProgress = CompletableDeferred<Unit>()
        val releaseFirstWrite = CompletableDeferred<Unit>()

        coEvery { helper.write(rl.dev, rl.writeFid, 100) } coAnswers {
            writeCount.incrementAndGet()
            firstWriteInProgress.complete(Unit) // signal: first write has started
            releaseFirstWrite.await()           // suspend here; NonCancellable keeps this alive
            true
        }
        coEvery { helper.write(rr.dev, rr.writeFid, 100) } coAnswers {
            writeCount.incrementAndGet()
            true
        }

        val job = launch { api.dispatch("后排车窗全开") }

        firstWriteInProgress.await()    // wait until first write is mid-flight
        job.cancel()                     // cancel while inside withContext(NonCancellable)
        releaseFirstWrite.complete(Unit) // unblock the first write mock
        job.join()                       // dispatch runs NonCancellable block to completion

        assertEquals(
            "both rear-window writes must complete inside the NonCancellable unit",
            2, writeCount.get()
        )
    }

    /**
     * Seat command (主驾座椅加热1档 = driver seat heat level 1) issues a switch write
     * then a level write through AdaptiveSeatChannel.primary(). Cancelling the calling
     * job between the two writes must not leave the seat with heat on but level unset.
     */
    @Test fun `seat write completes both switch and level writes even when calling job is cancelled between them`() = runTest {
        val sw = allowlist.find("driver_seat_heat_switch")!!
        val lv = allowlist.find("driver_seat_heat_level")!!
        val writeCount = AtomicInteger(0)

        val switchWriteInProgress = CompletableDeferred<Unit>()
        val releaseSwitchWrite = CompletableDeferred<Unit>()

        // Switch write: signal and gate
        coEvery { helper.writeStatus(sw.dev, sw.writeFid, 1) } coAnswers {
            writeCount.incrementAndGet()
            switchWriteInProgress.complete(Unit)
            releaseSwitchWrite.await() // suspend; NonCancellable keeps this alive
            1 // status = REAL
        }
        // Level write: simply record the call
        coEvery { helper.writeStatus(lv.dev, lv.writeFid, 1) } coAnswers {
            writeCount.incrementAndGet()
            1 // status = REAL
        }

        val job = launch { api.dispatch("主驾座椅加热1档") }

        switchWriteInProgress.await()       // wait until switch write is mid-flight
        job.cancel()                         // cancel while inside withContext(NonCancellable)
        releaseSwitchWrite.complete(Unit)    // unblock the switch write mock
        job.join()

        assertEquals(
            "seat switch+level writes must both complete inside the NonCancellable unit",
            2, writeCount.get()
        )
    }
}
