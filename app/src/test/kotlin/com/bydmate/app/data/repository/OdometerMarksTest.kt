package com.bydmate.app.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.data.repository.OdometerMarks.Mark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class OdometerMarksTest {

    private fun ctx(): Context = ApplicationProvider.getApplicationContext()

    // ---- store ----

    @Test fun `one entry per process survives a restart`() {
        val first = OdometerMarks(ctx())
        first.onReading(11042.4, 1_000L)
        first.onReading(11050.0, 60_000L)

        // New instance, same prefs: the next process.
        val m = OdometerMarks(ctx()).snapshot().single()
        assertEquals(1_000L, m.firstTs)
        assertEquals(11042.4, m.firstKm, 1e-9)
        assertEquals(60_000L, m.lastTs)
        assertEquals(11050.0, m.lastKm, 1e-9)
    }

    @Test fun `startup zero and null readings are ignored`() {
        val marks = OdometerMarks(ctx())
        marks.onReading(null, 1_000L)
        marks.onReading(0.0, 2_000L)
        assertTrue(marks.snapshot().isEmpty())
        marks.onReading(11042.4, 3_000L)
        assertEquals(3_000L, marks.snapshot().single().firstTs)
    }

    @Test fun `a jump of more than 1500 km replaces the pre-jump entry`() {
        // Previous process: kept.
        ctx().getSharedPreferences("odometer_marks", Context.MODE_PRIVATE).edit().putString(
            "marks",
            """[{"processStartTs":1,"firstTs":2,"firstKm":8590.0,"lastTs":500,"lastKm":8600.0}]""",
        ).commit()
        val marks = OdometerMarks(ctx())
        marks.onReading(8647.2, 1_000L)
        marks.onReading(86472.0, 2_000L)
        marks.onReading(86472.3, 3_000L)

        val all = OdometerMarks(ctx()).snapshot()
        assertEquals(2, all.size)
        assertEquals(8600.0, all[0].lastKm, 1e-9)
        assertEquals(86472.3, all[1].lastKm, 1e-9)
        assertEquals(2_000L, all[1].firstTs)
    }

    @Test fun `a changed odometer is persisted at once, a frozen one on the 15 s heartbeat`() {
        val marks = OdometerMarks(ctx())
        marks.onReading(100.0, 0L)
        marks.onReading(100.1, 1_000L)
        assertEquals(1_000L, OdometerMarks(ctx()).snapshot().single().lastTs)

        marks.onReading(100.1, 5_000L)
        assertEquals(1_000L, OdometerMarks(ctx()).snapshot().single().lastTs)

        marks.onReading(100.1, 16_000L)
        assertEquals(16_000L, OdometerMarks(ctx()).snapshot().single().lastTs)
    }

    @Test fun `keeps at most 50 entries`() {
        for (i in 0 until 55) OdometerMarks(ctx()).onReading(1000.0 + i, 1_000L * i)
        val all = OdometerMarks(ctx()).snapshot()
        assertEquals(50, all.size)
        assertEquals(1005.0, all.first().lastKm, 1e-9)
        assertEquals(1054.0, all.last().lastKm, 1e-9)
    }

    @Test fun `drops entries older than 14 days`() {
        OdometerMarks(ctx()).onReading(1000.0, 0L)
        OdometerMarks(ctx()).onReading(1010.0, 15L * DAY)
        assertEquals(1010.0, OdometerMarks(ctx()).snapshot().single().lastKm, 1e-9)
    }

    @Test fun `lastChangeTs follows the last odometer change, not the heartbeat`() {
        val marks = OdometerMarks(ctx())
        marks.onReading(100.0, 0L)
        assertEquals(0L, marks.snapshot().single().lastChangeTs)
        marks.onReading(100.1, 1_000L)
        marks.onReading(100.1, 20_000L)
        val m = OdometerMarks(ctx()).snapshot().single()
        assertEquals(1_000L, m.lastChangeTs)
        assertEquals(20_000L, m.lastTs)
    }

    @Test fun `old JSON without lastChangeTs loads with lastTs in its place`() {
        ctx().getSharedPreferences("odometer_marks", Context.MODE_PRIVATE).edit().putString(
            "marks",
            """[{"processStartTs":1,"firstTs":2,"firstKm":11042.4,"lastTs":5000,"lastKm":11092.9}]""",
        ).commit()
        val m = OdometerMarks(ctx()).snapshot().single()
        assertEquals(5_000L, m.lastChangeTs)
        assertEquals(11092.9, m.lastKm, 1e-9)
    }

    // ---- matcher ----

    private fun mark(lastChangeTs: Long, lastTs: Long, lastKm: Double, firstTs: Long = START) =
        Mark(0L, firstTs, 0.0, lastTs, lastKm, lastChangeTs)

    @Test fun `normal trip gets finish from the entry and start from the distance`() {
        val m = OdometerMarks.match(listOf(mark(END - 5_000L, END + 1_000L, 11092.94)), START, END, 50.5)
        assertEquals(11092.9, m.finishKm!!, 1e-9)
        assertEquals(11042.4, m.startKm!!, 1e-9)
    }

    @Test fun `process died long before the trip ended gives no odometer`() {
        val m = OdometerMarks.match(listOf(mark(END - 5 * 60_000L, END - 5 * 60_000L, 11080.0)), START, END, 50.5)
        assertNull(m.finishKm)
        assertNull(m.startKm)
    }

    @Test fun `heartbeat while parked with the car on before the end keeps the entry matchable`() {
        // Driven to a stop, then 5 min standing with the car on: the odometer froze, only
        // the heartbeat moved lastTs. The process dies at END + 1 s.
        val live = OdometerMarks(ctx())
        live.onReading(11042.4, START)
        live.onReading(11092.9, END - 5 * 60_000L)
        var t = END - 5 * 60_000L
        while (t <= END + 1_000L) { live.onReading(11092.9, t); t += 2_000L }

        val m = OdometerMarks.match(OdometerMarks(ctx()).snapshot(), START, END, 50.5)
        assertEquals(11092.9, m.finishKm!!, 1e-9)
        assertEquals(11042.4, m.startKm!!, 1e-9)
    }

    @Test fun `car stays on 10 min after the drive still matches`() {
        val live = OdometerMarks(ctx())
        live.onReading(11042.4, START)
        live.onReading(11092.9, END - 2_000L)
        var t = END
        while (t <= END + 10 * 60_000L) { live.onReading(11092.9, t); t += 2_000L }

        val m = OdometerMarks.match(OdometerMarks(ctx()).snapshot(), START, END, 50.5)
        assertEquals(11092.9, m.finishKm!!, 1e-9)
        assertEquals(11042.4, m.startKm!!, 1e-9)
    }

    @Test fun `drove again 5 min after the end in the same process gives no odometer`() {
        val live = OdometerMarks(ctx())
        live.onReading(11042.4, START)
        live.onReading(11092.9, END - 2_000L)
        var t = END
        while (t <= END + 5 * 60_000L) { live.onReading(11092.9, t); t += 2_000L }
        live.onReading(11093.0, END + 5 * 60_000L + 2_000L)
        live.onReading(11095.0, END + 8 * 60_000L)

        val m = OdometerMarks.match(OdometerMarks(ctx()).snapshot(), START, END, 50.5)
        assertNull(m.finishKm)
        assertTrue(m.reason.startsWith("moved after end"))
    }

    @Test fun `zero-km record gets start equal to finish`() {
        val m = OdometerMarks.match(listOf(mark(START, END + 1_000L, 11092.9)), START, END, 0.0)
        assertEquals(11092.9, m.finishKm!!, 1e-9)
        assertEquals(11092.9, m.startKm!!, 1e-9)
    }

    @Test fun `no trip distance leaves the start empty`() {
        val m = OdometerMarks.match(listOf(mark(END, END, 11092.9)), START, END, null)
        assertEquals(11092.9, m.finishKm!!, 1e-9)
        assertNull(m.startKm)
    }

    @Test fun `start below zero is dropped`() {
        val m = OdometerMarks.match(listOf(mark(END, END, 10.0)), START, END, 50.5)
        assertEquals(10.0, m.finishKm!!, 1e-9)
        assertNull(m.startKm)
    }

    @Test fun `several entries pick the one alive at the end with the latest change`() {
        val marks = listOf(
            mark(START - 60_000L, START - 60_000L, 11000.0, firstTs = START - 3_600_000L), // previous process, died before
            mark(END - 30_000L, END + 60_000L, 11092.0),                                   // alive, older change
            mark(END - 5_000L, END + 60_000L, 11092.9),                                    // alive, latest change
            mark(END + 11 * 60_000L, END + 12 * 60_000L, 11120.0, firstTs = END + 10 * 60_000L), // later process
        )
        val m = OdometerMarks.match(marks, START, END, 50.5)
        assertEquals(END - 5_000L, m.markChangeTs)
        assertEquals(11092.9, m.finishKm!!, 1e-9)
    }

    @Test fun `process died 45 s before the finish while moving gives no odometer`() {
        // The previous process read the true start; this one started after the trip start
        // and died on the move, so its last reading is below the real finish (11092.9).
        val marks = listOf(
            Mark(0L, START - 3_600_000L, 11000.0, START - 5_000L, 11042.4),
            Mark(1L, START + 30_000L, 11042.9, END - 45_000L, 11090.0),
        )
        val m = OdometerMarks.match(marks, START, END, 50.5)
        assertNull(m.finishKm)
        assertNull(m.startKm)
        assertTrue(m.reason.startsWith("start below earlier reading"))
    }

    @Test fun `moved 0,2 km and stopped 20 s after the end gives no odometer`() {
        val live = OdometerMarks(ctx())
        live.onReading(11042.4, START)
        live.onReading(11092.9, END - 2_000L)
        live.onReading(11093.1, END + 20_000L)
        var t = END + 22_000L
        while (t <= END + 60_000L) { live.onReading(11093.1, t); t += 2_000L }

        val m = OdometerMarks.match(OdometerMarks(ctx()).snapshot(), START, END, 50.5)
        assertNull(m.finishKm)
        assertTrue(m.reason.startsWith("moved after end"))
    }

    @Test fun `whole-km odometer allows the start 0,6 km below an earlier reading`() {
        val marks = listOf(
            Mark(0L, START - 3_600_000L, 11000.0, START - 5_000L, 11042.0),
            Mark(1L, START + 30_000L, 11043.0, END + 1_000L, 11092.0, lastChangeTs = END - 5_000L),
        )
        val m = OdometerMarks.match(marks, START, END, 50.6)
        assertEquals(11092.0, m.finishKm!!, 1e-9)
        assertEquals(11041.4, m.startKm!!, 1e-9)
    }

    @Test fun `0,1-km odometer rejects the start 0,3 km below an earlier reading`() {
        val marks = listOf(
            Mark(0L, START - 3_600_000L, 11000.0, START - 5_000L, 11042.4),
            Mark(1L, START + 30_000L, 11042.9, END + 1_000L, 11092.9, lastChangeTs = END - 5_000L),
        )
        val m = OdometerMarks.match(marks, START, END, 50.8)
        assertNull(m.finishKm)
        assertNull(m.startKm)
    }

    /** Two readings that happen to be whole on a 0.1 km car keep the 0.15 km tolerance: the
     *  firmware showed tenths before, so a start 0.8 km below the earlier reading is rejected. */
    @Test fun `whole values on a 0,1-km odometer keep the tight tolerance`() {
        val marks = listOf(
            Mark(0L, START - 7_200_000L, 10990.3, START - 3_700_000L, 10995.7),
            Mark(1L, START - 3_600_000L, 11000.0, START - 5_000L, 11042.0),
            Mark(2L, START + 30_000L, 11043.0, END + 1_000L, 11092.0, lastChangeTs = END - 5_000L),
        )
        val m = OdometerMarks.match(marks, START, END, 50.8)
        assertNull(m.finishKm)
        assertNull(m.startKm)
    }

    @Test fun `scale jump after a trip ended leaves that trip without odometer`() {
        val live = OdometerMarks(ctx())
        live.onReading(8647.2, START)
        live.onReading(8697.7, END - 2_000L)
        var t = END
        while (t <= END + 60_000L) { live.onReading(8697.7, t); t += 2_000L }
        live.onReading(86977.0, END + 2 * 60_000L)

        val all = OdometerMarks(ctx()).snapshot()
        assertEquals(86977.0, all.single().firstKm, 1e-9)
        val m = OdometerMarks.match(all, START, END, 50.5)
        assertNull(m.finishKm)
        assertNull(m.startKm)
    }

    private companion object {
        const val DAY = 24L * 3600 * 1000
        const val START = 1_700_000_000_000L
        const val END = START + 78 * 60_000L
    }
}
