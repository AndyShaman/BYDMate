package com.bydmate.app.data.vehicle

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.dao.VehicleWriteLogDao
import com.bydmate.app.data.local.entity.VehicleWriteLogEntity
import com.bydmate.app.data.nativestack.ParsReader
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleApiSeatOutcomeTest {
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
    private val impl = VehicleApiImpl(parsReader, autoservice, helper, allowlist, writeLogDao, seatStore)

    @Test fun `doWriteOutcome maps status 1 to REAL`() = runTest {
        val e = allowlist.find("driver_seat_vent_switch")!!
        coEvery { helper.writeStatus(e.dev, e.writeFid, 1) } returns 1
        assertEquals(WriteOutcome.REAL, impl.doWriteOutcome("driver_seat_vent_switch", 1))
    }

    @Test fun `doWriteOutcome maps status -10011 to PERMANENT_DENIED`() = runTest {
        val e = allowlist.find("driver_seat_vent_switch")!!
        coEvery { helper.writeStatus(e.dev, e.writeFid, 1) } returns -10011
        assertEquals(WriteOutcome.PERMANENT_DENIED, impl.doWriteOutcome("driver_seat_vent_switch", 1))
    }

    // Characterization test, NOT RED-first (task 7 / P8 audit item): doWriteOutcome's
    // NOOP-honesty already existed before this task (introduced in f636adca for the
    // seat adaptive channel) — this pins the existing contract so a future refactor
    // cannot silently regress it. A validated entry with status=0 must classify as
    // NOOP (never REAL) AND the audit row must record ok=false (persisted status=-1),
    // so no doWriteOutcome caller can ever observe a no-op write as a hard success.
    @Test fun `doWriteOutcome maps status 0 to NOOP for a validated entry and logs ok=false`() = runTest {
        val e = allowlist.find("driver_seat_vent_switch")!!
        assertTrue("this guard is only meaningful for a validated entry", e.validated)
        coEvery { helper.writeStatus(e.dev, e.writeFid, 1) } returns 0
        val insertions = mutableListOf<VehicleWriteLogEntity>()
        coEvery { writeLogDao.insert(capture(insertions)) } returns Unit

        assertEquals(WriteOutcome.NOOP, impl.doWriteOutcome("driver_seat_vent_switch", 1))

        val outcomeRow = insertions.first { it.error == "outcome_NOOP" }
        assertEquals("ok=false must persist as status=-1 (see logWrite)", -1, outcomeRow.status)
        assertTrue(outcomeRow.validated)
    }

    @Test fun `doWriteOutcome on allowlist miss is TRANSIENT and skips helper`() = runTest {
        assertEquals(WriteOutcome.TRANSIENT, impl.doWriteOutcome("no_such_action", 1))
    }

    @Test fun `doWriteOutcome on out-of-range is TRANSIENT and skips helper`() = runTest {
        // driver_seat_vent_level range is 1..5; 9 is out of range
        assertEquals(WriteOutcome.TRANSIENT, impl.doWriteOutcome("driver_seat_vent_level", 9))
    }
}
