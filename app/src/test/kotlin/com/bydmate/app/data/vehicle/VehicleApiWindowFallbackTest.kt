package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Issue #197 (alibek44, DiLink 3.0 trinket, 3.15.5): the dedicated open/close fid is accepted
 * and moves nothing, while the percent fid on the same door works. The log shape:
 *
 *   doWrite OK: action=window_driver_open dev=1001 fid=1125122104 value=1 ...
 *   window readback action=window_driver_open ... before=0 after=0,0 verdict=не сдвинулось
 *   window readback action=window_driver_pos ... before=0 after=10 verdict=moved
 *   window channel: PERCENT
 *
 * The retry must fire only where the percent channel is already the decided one, and must
 * leave percent-capable DiLink 5.0 cars (where the dedicated fids work) untouched.
 */
class VehicleApiWindowFallbackTest {

    private companion object {
        const val DEV = 1001
        const val DRIVER_OPEN_FID = 1125122104
        const val DRIVER_POS_FID = 1276219408
        const val PASSENGER_POS_FID = 1276219424
        const val REAR_LEFT_POS_FID = 1276219416
        const val REAR_RIGHT_POS_FID = 1276219432
        const val DRIVER_READ_FID = 947912728
        const val PASSENGER_READ_FID = 1267728400
        const val REAR_LEFT_READ_FID = 947912736
        const val REAR_RIGHT_READ_FID = 947912752
    }

    private val parsReader: ParsReader = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val writeLogDao: VehicleWriteLogDao = mockk(relaxed = true)
    private val helper: HelperClient = mockk<HelperClient>().also {
        coEvery { it.write(any(), any(), any()) } returns true
    }

    private val allowlist = WriteAllowlist(
        (WriteAllowlist.LIVE_VALIDATED + WriteAllowlist.CANDIDATE_UNVALIDATED)
            .associateBy { it.actionName.lowercase() }
    )

    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) = Unit
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }

    private fun fixedStore(channel: WindowChannel) = object : WindowChannelStore {
        override fun winner() = channel
        override fun setWinner(channel: WindowChannel) = Unit
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) = Unit
    }

    /** Unconfined readback scope so every "before" sample is taken before its write. */
    private fun api(channel: WindowChannel): VehicleApi = VehicleApiImpl(
        parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, fixedStore(channel),
    ).also { it.readbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }

    /** Window positions the readback sees, in order; the last one repeats once used up. */
    private fun positions(vararg samples: Int?) {
        val queue = ArrayDeque(samples.toList())
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers {
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    @Test fun `stuck open on a percent car is re-sent as a percent write`() = runTest {
        // before=0, two verification reads still 0 → stuck; the percent write then moves it.
        positions(0, 0, 0, 0, 60)

        val result = api(WindowChannel.PERCENT).dispatch("主驾打开100")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_OPEN_FID, 1) }
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 100) }
    }

    @Test fun `stuck close on a percent car is re-sent as a percent zero`() = runTest {
        positions(100, 100, 100, 100, 40)

        val result = api(WindowChannel.PERCENT).dispatch("主驾打开0")

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 0) }
    }

    @Test fun `a percent write that also fails to move is reported as a failure`() = runTest {
        positions(0)

        val result = api(WindowChannel.PERCENT).dispatch("主驾打开100")

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as VehicleWriteError.ReadbackMismatch
        assertTrue(err.message!!, err.message!!.contains("не сдвинулось"))
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 100) }
    }

    @Test fun `a pane that moved on the dedicated fid is never re-sent`() = runTest {
        positions(0, 70)

        assertTrue(api(WindowChannel.PERCENT).dispatch("主驾打开100").isSuccess)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_OPEN_FID, 1) }
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_POS_FID, any()) }
    }

    /** raw 255 on the passenger/rear doors of that firmware: their verdict is blind, so they
     *  ride along with the door that WAS seen stuck. A blind second readback claims nothing,
     *  so the command still reports success. */
    @Test fun `blind doors ride along with a door that was seen stuck`() = runTest {
        coEvery { autoservice.getIntRaw(DEV, DRIVER_READ_FID) } returns 0
        coEvery { autoservice.getIntRaw(DEV, PASSENGER_READ_FID) } returns null
        coEvery { autoservice.getIntRaw(DEV, REAR_LEFT_READ_FID) } returns null
        coEvery { autoservice.getIntRaw(DEV, REAR_RIGHT_READ_FID) } returns null

        assertTrue(api(WindowChannel.PERCENT).dispatch("车窗全开").isFailure)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, PASSENGER_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, REAR_LEFT_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, REAR_RIGHT_POS_FID, 100) }
    }

    /** Fleet safety (#79): with nothing observed to be stuck, a percent-capable car makes
     *  exactly the writes it made before, even when its position reads all fail. */
    @Test fun `blind doors alone are never re-sent`() = runTest {
        positions(null)

        assertTrue(api(WindowChannel.PERCENT).dispatch("车窗全开").isSuccess)
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_POS_FID, any()) }
        coVerify(exactly = 0) { helper.write(DEV, PASSENGER_POS_FID, any()) }
    }

    @Test fun `a CTRL car never gets the percent retry`() = runTest {
        positions(0)

        val result = api(WindowChannel.CTRL).dispatch("主驾打开100")

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_OPEN_FID, 1) }
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_POS_FID, any()) }
    }

    @Test fun `an undecided car never gets the percent retry`() = runTest {
        positions(0)

        val result = api(WindowChannel.UNKNOWN).dispatch("主驾打开100")

        assertTrue(result.isFailure)
        coVerify(exactly = 0) { helper.write(DEV, DRIVER_POS_FID, any()) }
    }

    @Test fun `all four doors stuck on a percent car fall back per door`() = runTest {
        positions(0)

        api(WindowChannel.PERCENT).dispatch("车窗全开")

        coVerify(exactly = 1) { helper.write(DEV, DRIVER_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, PASSENGER_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, REAR_LEFT_POS_FID, 100) }
        coVerify(exactly = 1) { helper.write(DEV, REAR_RIGHT_POS_FID, 100) }
    }

    /** Song L (#97) again: the fallback burst is a burst of window writes like any other, so the
     *  re-sends keep the same 150 ms spacing the composite dispatch uses — back-to-back writes
     *  are all accepted and the first panes never move. */
    @Test fun `the fallback writes keep the composite stagger`() = runTest {
        positions(0)
        val sentAt = mutableListOf<Long>()
        val clock = testScheduler
        val posFids = setOf(DRIVER_POS_FID, PASSENGER_POS_FID, REAR_LEFT_POS_FID, REAR_RIGHT_POS_FID)
        coEvery { helper.write(DEV, any(), any()) } coAnswers {
            if (secondArg<Int>() in posFids) sentAt += clock.currentTime
            true
        }

        api(WindowChannel.PERCENT).dispatch("车窗全开")

        assertEquals(4, sentAt.size)
        val gaps = sentAt.zipWithNext { a, b -> b - a }
        assertTrue(gaps.toString(), gaps.all { it >= 150 })
    }
}
