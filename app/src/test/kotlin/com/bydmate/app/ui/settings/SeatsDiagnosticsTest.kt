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

    @Test fun `the table covers the ten diagnostic fids`() {
        assertEquals(10, SeatsDiagnostics.FIDS.size)
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

    @Test fun `values are printed with their fid coordinates`() {
        val lines = SeatsDiagnostics.format(okReadings(0, 1, 0, 3, 0, 0, 1, 1, 1, 1))

        assertEquals(10, lines.size)
        assertEquals("vent_status_driver[dev=1000 fid=702545928]=0", lines[0])
        assertEquals("heat_level_driver[dev=1000 fid=702545948]=3", lines[3])
        assertEquals("has_driver_seat_heating[dev=1023 fid=-811597813]=1", lines[9])
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
