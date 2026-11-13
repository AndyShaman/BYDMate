package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.nativestack.FidCatalog
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Window "half / vent" on a firmware that reads positions but has no percent write (#417): the
 * percent write answers -10011 and the *_WINDOW_TARGET_POSITION_SET symbol is not in the car's
 * catalog, while the CTRL fid works. A refusal with BOTH signs falls back to CTRL (ctrlValue
 * mapping) and remembers it; a refusal with the symbol in the catalog, or with no catalog, is left
 * exactly as it was.
 *
 * Seam: the catalog is the existing `rearCatalog` constructor parameter of [VehicleApiImpl]
 * (a `() -> FidCatalog?`). If the implementer renames it or adds another, these tests need the
 * new name; they use the existing one on purpose.
 */
class VehicleApiWindowNoPercentWriteTest {

    private companion object {
        const val DEV = 1001
        const val PASSENGER_POS = 1276219424
        const val PASSENGER_CTRL = 1125122107
        const val RF_SYMBOL = "Bodywork.BODYWORK_RF_WINDOW_TARGET_POSITION_SET"
        const val REFUSED = -10011

        // Unrelated symbols so the catalog is "loaded" in every case.
        val BASE_SYMBOLS = mapOf(
            "Bodywork.BODYWORK_LF_WINDOW_CTRL_SET" to 1125122104,
            "Bodywork.BODYWORK_RF_WINDOW_CTRL_SET" to PASSENGER_CTRL,
        )
    }

    private class RecordingStore(private var value: WindowChannel) : WindowChannelStore {
        val set = mutableListOf<WindowChannel>()
        override fun winner() = value
        override fun setWinner(channel: WindowChannel) { value = channel; set += channel }
        override fun ctrlCandidateAtMs() = 0L
        override fun setCtrlCandidateAtMs(ts: Long) = Unit
    }

    private data class W(val fid: Int, val value: Int)

    private val writes = mutableListOf<W>()
    private val helper: HelperClient = mockk()
    private val autoservice: AutoserviceClient = mockk(relaxed = true)
    private val store = RecordingStore(WindowChannel.PERCENT)
    private val allowlist = WriteAllowlist(
        (WriteAllowlist.LIVE_VALIDATED + WriteAllowlist.CANDIDATE_UNVALIDATED).associateBy { it.actionName.lowercase() }
    )
    private val seatStore = object : SeatChannelStore {
        override fun winner() = SeatChannel.UNKNOWN
        override fun setWinner(channel: SeatChannel) = Unit
        override fun reprobeExhausted() = false
        override fun claimReprobe() = true
    }

    /** The percent fid answers -10011 to everything (value and the 255 reset); other fids accept. */
    @Before fun unit() {
        fun status(fid: Int, value: Int): Int {
            writes += W(fid, value)
            return if (fid == PASSENGER_POS) REFUSED else 1
        }
        coEvery { helper.write(any(), any(), any()) } coAnswers { status(secondArg(), thirdArg()) >= 0 }
        coEvery { helper.writeStatus(any(), any(), any(), any()) } coAnswers { status(secondArg(), thirdArg()) }
        coEvery { helper.read(any(), any()) } returns null
        coEvery { autoservice.getIntRaw(any(), any()) } returns null
    }

    private fun api(catalog: FidCatalog?) = VehicleApiImpl(
        autoservice, helper, allowlist, mockk<VehicleWriteLogDao>(relaxed = true), seatStore, store,
        rearCatalog = { catalog },
    ).also { it.readbackScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined) }

    private fun absent() = FidCatalog(BASE_SYMBOLS, emptyMap())
    private fun present() = FidCatalog(BASE_SYMBOLS + (RF_SYMBOL to PASSENGER_POS), emptyMap())

    private fun percentAttempts() = writes.count { it.fid == PASSENGER_POS && it.value != 255 }
    private fun ctrlWrites() = writes.filter { it.fid == PASSENGER_CTRL }.map { it.value }

    @Test fun `B1 refused percent with the symbol absent goes through CTRL and is remembered`() = runTest {
        val api = api(absent())

        val first = api.doWrite("window_passenger_pos", 50)

        assertTrue("first command: $first", first.isSuccess)
        assertEquals(listOf(4), ctrlWrites()) // 50% -> half
        assertEquals(WindowChannel.CTRL, store.winner())

        val attemptsBefore = percentAttempts()
        val second = api.doWrite("window_passenger_pos", 10)

        assertTrue("second command: $second", second.isSuccess)
        assertEquals(listOf(4, 5), ctrlWrites()) // 10% -> vent
        assertEquals("the next command must not try percent again", attemptsBefore, percentAttempts())
    }

    @Test fun `B2 refused percent with the symbol in the catalog is not rerouted`() = runTest {
        val result = api(present()).doWrite("window_passenger_pos", 50)

        assertTrue(result.isFailure)
        assertTrue("got ${result.exceptionOrNull()}", result.exceptionOrNull() is VehicleWriteError.HelperUnreachable)
        assertEquals(emptyList<Int>(), ctrlWrites())
        assertEquals(WindowChannel.PERCENT, store.winner())
        assertEquals(emptyList<WindowChannel>(), store.set)
    }

    @Test fun `B3 accepted percent never touches CTRL`() = runTest {
        coEvery { helper.write(DEV, PASSENGER_POS, any()) } coAnswers { writes += W(PASSENGER_POS, thirdArg()); true }
        coEvery { helper.writeStatus(DEV, PASSENGER_POS, any(), any()) } coAnswers {
            writes += W(PASSENGER_POS, thirdArg()); 1
        }

        val result = api(absent()).doWrite("window_passenger_pos", 50)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Int>(), ctrlWrites())
        assertEquals(WindowChannel.PERCENT, store.winner())
        assertEquals(emptyList<WindowChannel>(), store.set)
    }

    @Test fun `B4 refused percent with no catalog is not rerouted`() = runTest {
        for (catalog in listOf<FidCatalog?>(null, FidCatalog.EMPTY)) {
            val result = api(catalog).doWrite("window_passenger_pos", 50)

            assertTrue(result.isFailure)
            assertTrue("got ${result.exceptionOrNull()}", result.exceptionOrNull() is VehicleWriteError.HelperUnreachable)
            assertEquals(emptyList<Int>(), ctrlWrites())
            assertEquals(WindowChannel.PERCENT, store.winner())
        }
    }
}
