package com.bydmate.app.data.nativestack

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FidResolveDiagnosticsTest {

    private val plausible = FidProbe { requests ->
        requests.map { if (it.transact == 7) java.lang.Float.floatToRawIntBits(5.0f) else 5 }
    }

    private fun catalog(name: String): FidCatalog = FidCatalog.parse(
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fid-catalog-$name.txt"))
            .bufferedReader().readText()
    )

    @Test fun `without a catalog the section says so and lists nothing`() {
        val lines = FidResolveDiagnostics.format(
            FidResolver.constants(FidMap.all), null, emptyList())
        assertEquals("catalog: none (source=constants)", lines[0])
        assertTrue(lines.contains("(every entry on its constant)"))
        assertTrue(lines[1].startsWith("resolved: ${FidMap.all.size} const"))
    }

    @Test fun `a resolved table lists only what moved, plus the write addresses`() = runTest {
        val songPlus = catalog("songplus")
        val outcome = FidResolver.resolve(FidMap.all, songPlus, plausible, "daemon fp")
        val lines = FidResolveDiagnostics.format(
            outcome.table,
            songPlus,
            listOf(WriteFidRow("ac_on", 1000, 501219364)),
        )
        assertEquals("catalog: source=daemon fp symbols=${songPlus.totalSymbols} devices=50", lines[0])
        // catalog line + summary + one line per moved entry + the write header and its row
        val moved = outcome.table.notes.count { it.outcome != FidResolution.CONST }
        assertEquals(2 + moved + 2, lines.size)
        assertTrue(lines.any { it.startsWith("soc ") && it.endsWith("catalog") })
        assertTrue(lines.contains("write (manual):"))
        // The write fid keeps its Leopard 3 number in the dump; only the catalog column moves.
        assertTrue(lines.any { it.contains("ac_on dev=1000 fid=501219364 Ac.AC_POWER_STATE_SET catalog=") })
    }
}
