package com.bydmate.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hybrid probe exists to answer one question from a single dump (#184): which ICE-side
 * addresses are live on a DM-i. That only works if the addresses stay exactly as measured and
 * the raw word survives printing, sentinels included.
 */
class HybridProbeDiagnosticsTest {

    @Test fun `batch items carry each address with its own transact`() {
        val items = HybridProbeDiagnostics.batchItems()
        assertEquals(HybridProbeDiagnostics.FIDS.size, items.size)
        HybridProbeDiagnostics.FIDS.forEachIndexed { i, f ->
            assertEquals("tx for ${f.name}", f.tx, items[i].tx)
            assertEquals("dev for ${f.name}", f.dev, items[i].dev)
            assertEquals("fid for ${f.name}", f.fid, items[i].fid)
        }
    }

    @Test fun `values are printed with their fid coordinates and transact`() {
        val lines = HybridProbeDiagnostics.format(
            HybridProbeDiagnostics.FIDS.mapIndexed { i, _ -> 0 to i }
        )
        assertEquals(HybridProbeDiagnostics.FIDS.size, lines.size)
        assertEquals("engine_rpm[dev=1012 fid=339738642 tx=5]=0", lines[0])
        assertEquals("fuel_instant[dev=1014 fid=1246760996 tx=7]=6", lines[6])
        assertEquals("fuel_tank_cap[dev=1001 fid=1336934422 tx=5]=11", lines[11])
    }

    /** On a full EV these addresses answer sentinels, and that answer is the finding. */
    @Test fun `sentinel values are printed verbatim`() {
        val readings = List(HybridProbeDiagnostics.FIDS.size) { 0 to 65535 }.toMutableList()
        readings[1] = 0 to -10013

        val lines = HybridProbeDiagnostics.format(readings)

        assertTrue("link error must survive, got: ${lines[0]}", lines[0].endsWith("=65535"))
        assertTrue("wrong-transact sentinel must survive, got: ${lines[1]}", lines[1].endsWith("=-10013"))
    }

    @Test fun `a per-item protocol failure shows its status`() {
        val readings = List(HybridProbeDiagnostics.FIDS.size) { 0 to 1 }.toMutableList()
        readings[2] = -998 to 0

        val lines = HybridProbeDiagnostics.format(readings)

        assertTrue("failed item must show the status, got: ${lines[2]}", lines[2].endsWith("=(status=-998)"))
    }

    @Test fun `no readings collapses to one unavailable line`() {
        assertEquals(listOf("(unavailable)"), HybridProbeDiagnostics.format(null))
    }

    @Test fun `a length mismatch is reported as unavailable rather than misaligned`() {
        assertEquals(listOf("(unavailable)"), HybridProbeDiagnostics.format(listOf(0 to 1, 0 to 2)))
    }
}
