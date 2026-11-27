package com.bydmate.app.data.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ADAS states ride the push subscription only where the car's catalog gives the symbol the
 * same fid, so a mistyped fid or symbol is a state that is silently never traced. The fixture is
 * the ADAS lines of a Tang L owner's catalog (#383), not numbers typed from the code.
 */
class AdasStatesCatalogTest {

    private val catalog: Map<String, Int> =
        checkNotNull(javaClass.classLoader?.getResource("fid-catalog-tangl-adas.txt")).readText()
            .lineSequence().filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=').trim().toInt() }

    @Test fun `every traced ADAS state carries the fid a real catalog gives its symbol`() {
        PushStateTrace.ADAS_STATES.forEach { assertEquals(it.symbol, catalog[it.symbol], it.fid) }
    }

    @Test fun `the states around cruise and lane keeping are traced (383)`() {
        val traced = PushStateTrace.ADAS_STATES.map { it.symbol }
        listOf(
            "Adas.ADAS_ASSIST_DRIVE_MODE_STATUS", "Adas.ADAS_ACC_MODE_ARHUD",
            "Adas.ADAS_BROADCAST_AFTER_GET_ICCSIGNAL", "Adas.ADAS_DNP_STATE",
        ).forEach { assertTrue(it, it in traced) }
        // ADAS_ACC_MODE became the cruiseMode field (a FidMap entry), and the lane signal
        // ADAS_TJA_ICA_STATE flipped hundreds of times a trip, so neither is traced any more.
        listOf("Adas.ADAS_ACC_MODE", "Adas.ADAS_TJA_ICA_STATE").forEach { assertTrue(it, it !in traced) }
    }

    /** The daemon keys its tables by fid, and a trace line is read by its name. */
    @Test fun `no fid and no trace name is listed twice`() {
        val states = PushStateTrace.ADAS_STATES
        assertEquals(states.size, states.map { it.fid }.toSet().size)
        assertEquals(states.size, states.map { it.name }.toSet().size)
    }
}
