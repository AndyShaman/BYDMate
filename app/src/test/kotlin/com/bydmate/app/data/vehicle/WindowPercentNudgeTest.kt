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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Leopard 3, DiLink 5.0 (2026-09-24): a percent write whose value equals the previous percent
 * write to the same window fid is accepted and the motor never starts. The log shape:
 *
 *   write dev=1001 fid=1276219408 value=10 status=1 accepted=true
 *   window readback action=window_driver_pos ... value=10 before=0 after=0,0 verdict=не сдвинулось
 *
 * On the car "10" stayed at 0 while "11" moved to 11, so a stuck percent write is re-sent once
 * with the value nudged by one percent (down for 100, never for a close of 0). A pane that
 * moves is never re-sent.
 */
class WindowPercentNudgeTest {

    private companion object {
        const val DEV = 1001
        const val DRIVER_OPEN_FID = 1125122104
        const val DRIVER_POS_FID = 1276219408
        const val PASSENGER_POS_FID = 1276219424
        const val DRIVER_READ_FID = 947912728
        const val PASSENGER_READ_FID = 1267728400
    }

    private val parsReader: ParsReader = mockk(relaxed = true)
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val writeLogDao: VehicleWriteLogDao = mockk(relaxed = true)

    /** Every value written to the driver percent fid, in order. */
    private val posWrites = mutableListOf<Int>()
    private val helper: HelperClient = mockk<HelperClient>().also {
        coEvery { it.write(any(), any(), any()) } coAnswers {
            if (secondArg<Int>() == DRIVER_POS_FID) posWrites += thirdArg<Int>()
            true
        }
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
    private fun api(channel: WindowChannel = WindowChannel.PERCENT): VehicleApi = VehicleApiImpl(
        parsReader, autoservice, helper, allowlist, writeLogDao, seatStore, fixedStore(channel),
    ).also { it.readbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }

    /** Window positions the readback sees, in order; the last one repeats once used up. */
    private fun positions(vararg samples: Int?) {
        val queue = ArrayDeque(samples.toList())
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers {
            if (queue.size > 1) queue.removeFirst() else queue.first()
        }
    }

    @Test fun `a stuck percent write is re-sent once with the value nudged up`() = runTest {
        // before=0, two verification reads still 0 -> stuck; the nudged write then moves it.
        positions(0, 0, 0, 0, 11)

        val result = api().writeWindowDriver(10)

        assertTrue(result.isSuccess)
        assertEquals(listOf(10, 11), posWrites)
    }

    @Test fun `a percent write that moves the pane is never nudged`() = runTest {
        positions(0, 4)

        assertTrue(api().writeWindowDriver(10).isSuccess)
        assertEquals(listOf(10), posWrites)
    }

    @Test fun `a stuck write of 100 is nudged down to 99`() = runTest {
        positions(0, 0, 0, 0, 30)

        assertTrue(api().writeWindowDriver(100).isSuccess)
        assertEquals(listOf(100, 99), posWrites)
    }

    /** The anchor retry after the nudge did not move the glass either: failure as before, and
     *  nothing more is written after the requested value. */
    @Test fun `a nudge that also does not move is reported with the existing error`() = runTest {
        positions(0)

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as VehicleWriteError.ReadbackMismatch
        assertTrue(err.message!!, err.message!!.contains("окно водителя не сдвинулось с места, команда не сработала"))
        assertEquals(listOf(10, 11, 0, 10), posWrites)
    }

    /**
     * Song L (user log 2026-09-29): "10" stuck, the nudged "11" stuck too. Leopard 3 on the same
     * day: writing the current position (0) is accepted without moving the glass and replaces the
     * stored target, after which "10" moves it. The glass here moves only after that pair.
     */
    @Test fun `a nudge that does not move is followed by the current position, then the target`() = runTest {
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers {
            if (posWrites.takeLast(2) == listOf(0, 10)) 9 else 0
        }
        val sentAt = mutableListOf<Long>()
        val clock = testScheduler
        coEvery { helper.write(any(), any(), any()) } coAnswers {
            if (secondArg<Int>() == DRIVER_POS_FID) {
                posWrites += thirdArg<Int>()
                sentAt += clock.currentTime
            }
            true
        }

        val result = api().writeWindowDriver(10)

        assertTrue(result.isSuccess)
        assertEquals(listOf(10, 11, 0, 10), posWrites)
        assertTrue(sentAt.toString(), sentAt[3] - sentAt[2] >= 150)
    }

    /** Working cars: when the nudge moves the glass, no extra read and no extra write. */
    @Test fun `a nudge that moves the pane costs no extra read or write`() = runTest {
        positions(0, 0, 0, 0, 11)

        assertTrue(api().writeWindowDriver(10).isSuccess)
        assertEquals(listOf(10, 11), posWrites)
        coVerify(exactly = 5) { autoservice.getIntRaw(any(), any()) }
    }

    @Test fun `a stuck 100 whose nudge also does not move is anchored, then re-sent as 100`() = runTest {
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers {
            if (posWrites.takeLast(2) == listOf(0, 100)) 30 else 0
        }

        assertTrue(api().writeWindowDriver(100).isSuccess)
        assertEquals(listOf(100, 99, 0, 100), posWrites)
    }

    /** Reads 1-3 judge the first write, 4-6 the nudge, the 7th is the anchor's fresh position. */
    @Test fun `a sentinel position at the anchor step writes nothing more`() = runTest {
        positions(0, 0, 0, 0, 0, 0, 255)

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
        assertEquals(listOf(10, 11), posWrites)
    }

    @Test fun `a failed position read at the anchor step writes nothing more`() = runTest {
        positions(0, 0, 0, 0, 0, 0, null)

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
        assertEquals(listOf(10, 11), posWrites)
    }

    /** The glass moved after the "did not move" verdict (late start, door button): writing
     *  that stale position would be a move command, so the pane stays a failure. */
    @Test fun `a position that changed since the stuck verdict is not anchored`() = runTest {
        positions(0, 0, 0, 0, 0, 0, 5)

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
        assertEquals(listOf(10, 11), posWrites)
    }

    /** Two panes stuck after the nudge: each pane's position is read after the pacing delay,
     *  right before its own anchor write, never 150 ms earlier. */
    @Test fun `each pane's anchor position is read right before its anchor write`() = runTest {
        val clock = testScheduler
        val events = mutableListOf<Triple<String, Int, Long>>()
        val writes = mutableMapOf<Int, MutableList<Int>>()
        coEvery { autoservice.getIntRaw(any(), any()) } coAnswers {
            events += Triple("read", secondArg<Int>(), clock.currentTime)
            0
        }
        coEvery { helper.write(any(), any(), any()) } coAnswers {
            events += Triple("write", secondArg<Int>(), clock.currentTime)
            writes.getOrPut(secondArg()) { mutableListOf() } += thirdArg<Int>()
            true
        }

        assertTrue(api().dispatch("前排车窗半开").isFailure)

        assertEquals(listOf(50, 51, 0, 50), writes[DRIVER_POS_FID])
        assertEquals(listOf(50, 51, 0, 50), writes[PASSENGER_POS_FID])
        for ((posFid, readFid) in listOf(DRIVER_POS_FID to DRIVER_READ_FID, PASSENGER_POS_FID to PASSENGER_READ_FID)) {
            // The fresh position read, then the anchor write's own "before" sample, then the write.
            val anchorAt = events.indices.filter { events[it].first == "write" && events[it].second == posFid }[2]
            val reads = events.subList(anchorAt - 2, anchorAt)
            assertTrue(reads.toString(), reads.all { it.first == "read" && it.second == readFid })
            assertTrue(reads.toString(), reads.all { it.third == events[anchorAt].third })
        }
    }

    @Test fun `a refused anchor write is not followed by the requested value`() = runTest {
        positions(0)
        coEvery { helper.write(any(), any(), any()) } coAnswers {
            if (secondArg<Int>() == DRIVER_POS_FID) posWrites += thirdArg<Int>()
            posWrites.size != 3
        }

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
        assertEquals(listOf(10, 11, 0), posWrites)
    }

    @Test fun `a refused requested value after the anchor is a failure with no more writes`() = runTest {
        positions(0)
        coEvery { helper.write(any(), any(), any()) } coAnswers {
            if (secondArg<Int>() == DRIVER_POS_FID) posWrites += thirdArg<Int>()
            posWrites.size != 4
        }

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is VehicleWriteError.ReadbackMismatch)
        assertEquals(listOf(10, 11, 0, 10), posWrites)
    }

    @Test fun `a position equal to the requested value is not anchored`() = runTest {
        positions(0, 0, 0, 0, 0, 0, 10)

        val result = api().writeWindowDriver(10)

        assertTrue(result.isFailure)
        assertEquals(listOf(10, 11), posWrites)
    }

    /** Nudging a close to 1 would leave the window open: report the failure instead. */
    @Test fun `a stuck percent close of 0 is not nudged`() = runTest {
        positions(100)

        val result = api().writeWindowDriver(0)

        assertTrue(result.isFailure)
        val err = result.exceptionOrNull() as VehicleWriteError.ReadbackMismatch
        assertTrue(err.message!!, err.message!!.contains("окно водителя не сдвинулось с места, команда не сработала"))
        assertEquals(listOf(0), posWrites)
    }

    @Test fun `a stuck CTRL write on a unit not decided on PERCENT is not nudged`() = runTest {
        positions(0)

        val result = api(WindowChannel.CTRL).dispatch("主驾打开100")

        assertTrue(result.isFailure)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_OPEN_FID, 1) }
        coVerify(exactly = 1) { helper.write(any(), any(), any()) }
    }

    /** The #197 fallback's own percent write is already the second chance: never nudged. */
    @Test fun `the percent fallback write of a stuck CTRL write is not nudged`() = runTest {
        positions(0)

        assertTrue(api(WindowChannel.PERCENT).dispatch("主驾打开100").isFailure)
        coVerify(exactly = 1) { helper.write(DEV, DRIVER_OPEN_FID, 1) }
        assertEquals(listOf(100), posWrites)
    }

    /** A pane whose position cannot be read gives no evidence, so nothing is re-sent. */
    @Test fun `a blind percent write is not nudged`() = runTest {
        positions(null)

        assertTrue(api().writeWindowDriver(10).isSuccess)
        assertEquals(listOf(10), posWrites)
    }
}
