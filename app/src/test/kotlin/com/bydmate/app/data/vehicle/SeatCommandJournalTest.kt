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
}
