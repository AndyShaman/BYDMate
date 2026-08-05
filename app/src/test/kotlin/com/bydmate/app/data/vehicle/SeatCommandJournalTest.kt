package com.bydmate.app.data.vehicle

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The journal is the only place where a seat attempt survives long enough to reach a field
 * report, so it must keep the write address, the raw status and the readback verdict, and it
 * must not grow without bound on a car where the user keeps toggling the seat.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SeatCommandJournalTest {

    private fun journal(): SeatCommandJournal {
        val prefs = ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("seat_journal_test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        return SeatCommandJournal(prefs)
    }

    @Test fun `a write line keeps the address status and classification`() {
        val j = journal()
        j.appendWrite("driver_seat_vent_switch", 1000, 1276248080, 1, 1, WriteOutcome.REAL)

        val line = j.lines().single()
        assertTrue(line, line.contains("W driver_seat_vent_switch ch=primary"))
        assertTrue(line, line.contains("dev=1000 fid=1276248080 val=1 status=1 outcome=REAL"))
    }

    @Test fun `a fallback action is labelled as the fallback channel`() {
        val j = journal()
        j.appendWrite("driver_seat_vent_fallback", 1001, 1125122064, 2, 0, WriteOutcome.NOOP)

        assertTrue(j.lines().single(), j.lines().single().contains("ch=fallback"))
    }

    @Test fun `an unreachable daemon prints a null status rather than a fake number`() {
        val j = journal()
        j.appendWrite("driver_seat_heat_switch", 1000, 1276248084, 1, null, WriteOutcome.TRANSIENT)

        assertTrue(j.lines().single(), j.lines().single().contains("status=null outcome=TRANSIENT"))
    }

    @Test fun `an actuation line carries group level channel and readback`() {
        val j = journal()
        j.appendActuation(SeatGroup.DRIVER_HEAT, 3, SeatChannel.PRIMARY, WriteOutcome.NOOP, "expect=1 got=0,0")

        val line = j.lines().single()
        assertTrue(line, line.contains("A DRIVER_HEAT lvl=3 ch=primary outcome=NOOP rb=expect=1 got=0,0"))
    }

    @Test fun `the ring keeps the newest entries only`() {
        val j = journal()
        repeat(SeatCommandJournal.MAX_ENTRIES + 5) {
            j.appendWrite("driver_seat_vent_switch", 1000, 1276248080, it, 1, WriteOutcome.REAL)
        }

        val lines = j.lines()
        assertEquals(SeatCommandJournal.MAX_ENTRIES, lines.size)
        assertTrue("oldest entries must be dropped", lines.first().endsWith("val=5 status=1 outcome=REAL"))
        assertTrue(
            "newest entry must survive",
            lines.last().contains("val=${SeatCommandJournal.MAX_ENTRIES + 4} "),
        )
    }

    @Test fun `an untouched journal reads empty`() {
        assertEquals(emptyList<String>(), journal().lines())
    }
}
