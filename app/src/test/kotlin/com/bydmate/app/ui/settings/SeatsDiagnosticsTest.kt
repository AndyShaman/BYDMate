package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The seats dump section is the only window into why status=1 writes do nothing on
 * Song L / Leopard 5 / Han EV, so its formatting must stay lossless: raw values,
 * sentinels printed as-is, and an honest single line when the channel is down.
 */
class SeatsDiagnosticsTest {

    private fun okReadings(vararg values: Int) = values.map { 0 to it }

    @Test fun `batch items mirror the fid table as tx 5 reads`() {
        assertEquals(SeatsDiagnostics.FIDS.size, SeatsDiagnostics.batchItems.size)
        SeatsDiagnostics.FIDS.forEachIndexed { i, f ->
            val item = SeatsDiagnostics.batchItems[i]
            assertEquals("tx for ${f.name}", 5, item.tx)
            assertEquals("dev for ${f.name}", f.dev, item.dev)
            assertEquals("fid for ${f.name}", f.fid, item.fid)
        }
    }

    @Test fun `the table covers the diagnostic fids and the candidate families`() {
        assertEquals(24, SeatsDiagnostics.FIDS.size)
        val devs = SeatsDiagnostics.FIDS.map { it.dev }.toSet()
        assertEquals(setOf(1000, 1023), devs)
        assertEquals(
            "config flags must be present",
            listOf(715132952, 715132955),
            SeatsDiagnostics.FIDS.filter { it.name.startsWith("config_") }.map { it.fid },
        )
        assertEquals(
            "dev=1023 trim flags must be present",
            listOf(-811597816, -811597813),
            SeatsDiagnostics.FIDS.filter { it.dev == 1023 }.map { it.fid },
        )
    }

    /** The candidate families are the whole point of the next field dump: 3CE for the front
     *  seats, LRSE for the rear ones. They must be read-only additions to the same batch. */
    @Test fun `candidate status families are sampled read-only`() {
        assertEquals(
            "3CE hal_only block must be present",
            listOf(1021313032, 1021313034, 1021313044, 1021313048),
            SeatsDiagnostics.FIDS.filter { it.name.startsWith("cand_3ce_") }.map { it.fid },
        )
        assertEquals(
            "LRSE rear block must be present",
            listOf(412180522, 412180526, 412180528, 412180532, 412180536, 412180540, 412180544, 412180548),
            SeatsDiagnostics.FIDS.filter { it.name.startsWith("cand_lrse_") }.map { it.fid },
        )
        assertTrue(
            "candidates must be plain tx=5 reads on dev=1000",
            SeatsDiagnostics.FIDS.filter { it.name.startsWith("cand_") }.all { it.dev == 1000 },
        )
    }

    @Test fun `values are printed with their fid coordinates`() {
        val values = IntArray(SeatsDiagnostics.FIDS.size) { 1 }.toMutableList()
        values[0] = 0
        values[3] = 3
        val lines = SeatsDiagnostics.format(values.map { 0 to it })

        assertEquals(SeatsDiagnostics.FIDS.size, lines.size)
        assertEquals("vent_status_driver[dev=1000 fid=702545928]=0", lines[0])
        assertEquals("heat_level_driver[dev=1000 fid=702545948]=3", lines[3])
        assertEquals("has_driver_seat_heating[dev=1023 fid=-811597813]=1", lines[9])
        assertEquals("cand_passenger_vent_status[dev=1000 fid=711983112]=1", lines[10])
        assertEquals("cand_3ce_vent_status_driver[dev=1000 fid=1021313032]=1", lines[12])
    }

    /** 711983128/711983132 are AC_PASSENGER_SEAT_*_LEVEL (0=off, 1..5), not the driver's
     *  1=on/2=off status enum — the label has to say so, and the real passenger status pair
     *  is sampled as an unvalidated candidate next to it. */
    @Test fun `passenger fids are labelled as levels and the status pair is a candidate`() {
        assertEquals(
            listOf("vent_level_passenger" to 711983128, "heat_level_passenger" to 711983132),
            SeatsDiagnostics.FIDS.filter { it.name.endsWith("_passenger") }.map { it.name to it.fid },
        )
        assertEquals(
            listOf(711983112, 711983116),
            SeatsDiagnostics.FIDS.filter { it.name.startsWith("cand_passenger_") }.map { it.fid },
        )
    }

    /** 65535 (link error) and -10011 (wrong direction) are the diagnosis, not noise. */
    @Test fun `sentinel values are printed verbatim`() {
        val readings = List(SeatsDiagnostics.FIDS.size) { 0 to 65535 }.toMutableList()
        readings[1] = 0 to -10011

        val lines = SeatsDiagnostics.format(readings)

        assertTrue("link error must survive, got: ${lines[0]}", lines[0].endsWith("=65535"))
        assertTrue("wrong-direction sentinel must survive, got: ${lines[1]}", lines[1].endsWith("=-10011"))
    }

    @Test fun `a per-item protocol failure shows its status`() {
        val readings = List(SeatsDiagnostics.FIDS.size) { 0 to 1 }.toMutableList()
        readings[4] = -998 to 0

        val lines = SeatsDiagnostics.format(readings)

        assertTrue("failed item must show the status, got: ${lines[4]}", lines[4].endsWith("=(status=-998)"))
        assertTrue("other items stay readable, got: ${lines[0]}", lines[0].endsWith("=1"))
    }

    @Test fun `no readings collapses to one unavailable line`() {
        assertEquals(listOf("(unavailable)"), SeatsDiagnostics.format(null))
    }

    @Test fun `a length mismatch is reported as unavailable rather than misaligned`() {
        assertEquals(listOf("(unavailable)"), SeatsDiagnostics.format(okReadings(0, 1, 2)))
    }
}
