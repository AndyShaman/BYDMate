package com.bydmate.app.data.nativestack

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Resolver behaviour against the three field catalogs we have.
 *
 * Fixtures are the real dumps trimmed to the device table plus the symbols the app reads
 * (scripts/native-stack/fid-symbols.py fixtures). `fid-songplus-expected.tsv` carries the
 * addresses the tester's hand-patched Song Plus build actually uses.
 */
class FidResolverTest {

    private fun catalog(name: String): FidCatalog =
        FidCatalog.parse(resource("fid-catalog-$name.txt"))

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    /** Answers 5 (tx=5) / 5.0f (tx=7) — inside every decoder envelope we use. */
    private val alwaysPlausible = FidProbe { requests ->
        requests.map { request ->
            if (request.transact == 7) java.lang.Float.floatToRawIntBits(5.0f) else 5
        }
    }

    private val alwaysSilent = FidProbe { requests -> requests.map { null } }

    @Test
    fun `leopard 3 catalog leaves every entry on its constant`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, catalog("leopard3"), alwaysPlausible, "test")
        FidMap.all.forEach { entry ->
            if (entry.field == STALE_BY_DESIGN) return@forEach
            val address = outcome.table.address(entry.field)
            assertEquals("device moved for ${entry.field}", entry.device, address.device)
            assertEquals("fid moved for ${entry.field}", entry.fid, address.fid)
        }
        // The one deliberate exception: this constant answers a sentinel on Leopard 3 and
        // was kept only as a fallback, so its symbol is expected to move it (see FidMap).
        assertEquals(1145045000, outcome.table.fid(STALE_BY_DESIGN))
        assertEquals(
            "only $STALE_BY_DESIGN may move on Leopard 3",
            listOf(STALE_BY_DESIGN),
            outcome.table.notes.filter { it.outcome != FidResolution.CONST }.map { it.field },
        )
    }

    @Test
    fun `song plus catalog reproduces the addresses of the tester's patched build`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), alwaysPlausible, "test")
        val expected = resource("fid-songplus-expected.tsv")
            .lineSequence()
            .filterNot { it.startsWith("#") || it.isBlank() }
            .map { it.split("\t") }
            .filter { it[3] == "agrees" }
            .toList()
        assertEquals("expected fixture changed shape", 45, expected.size)
        expected.forEach { (field, device, fid, _) ->
            val address = outcome.table.address(field)
            assertEquals("fid for $field", fid.toInt(), address.fid)
            assertEquals("device for $field", device.toInt(), address.device)
        }
    }

    @Test
    fun `song plus keeps the constant where the symbol is unknown there`() = runTest {
        val songPlus = catalog("songplus")
        val outcome = FidResolver.resolve(FidMap.all, songPlus, alwaysPlausible, "test")
        // The seat level symbols and the rear-door position are absent from that catalog.
        listOf("seatHeatDriver", "seatVentDriver", "trunk").forEach { field ->
            val entry = FidMap.byField.getValue(field)
            assertNull("$field unexpectedly present", songPlus.fidOf(entry.symbol!!))
            assertEquals(entry.fid, outcome.table.fid(field))
        }
        // No symbol at all: nothing to resolve against, ever.
        assertNull(FidMap.byField.getValue("windowRRGen3").symbol)
        assertEquals(1267728408, outcome.table.fid("windowRRGen3"))
    }

    @Test
    fun `song plus turn signal takes the value of our own symbol`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), alwaysPlausible, "test")
        // 1564 is a CAN-FD branch id — small, but a legitimate catalog value.
        assertEquals(1564, outcome.table.fid("turnSignal"))
    }

    @Test
    fun `dilink 4 catalog produces a full table and reports what moved`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, catalog("dilink4"), alwaysPlausible, "test")
        FidMap.all.forEach { assertNotNull(outcome.table.address(it.field)) }
        assertEquals(FidMap.all.size, outcome.table.notes.size)
        assertTrue(
            "DiLink 4.0 is a different platform, something must move",
            outcome.table.catalogCount > 0,
        )
    }

    @Test
    fun `a sentinel answer keeps the constant`() = runTest {
        // 65535 = DEVICE_THE_FEATURE_LINK_ERROR on the int channel, -1.0f = "not
        // initialized" on the float one.
        val sentinel = FidProbe { requests ->
            requests.map {
                if (it.transact == 7) java.lang.Float.floatToRawIntBits(-1.0f) else 65535
            }
        }
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), sentinel, "test")
        assertEquals(0, outcome.table.catalogCount)
        assertTrue(outcome.table.rejectedCount > 0)
        val note = outcome.table.notes.first { it.field == "soc" }
        assertEquals(FidResolution.REJECTED, note.outcome)
        assertEquals(FidMap.byField.getValue("soc").fid, outcome.table.fid("soc"))
    }

    @Test
    fun `a value outside the decoder envelope keeps the constant`() = runTest {
        // 240 is not a cabin temperature; the percent and temperature decoders refuse it.
        val outOfRange = FidProbe { requests ->
            requests.map { if (it.transact == 7) java.lang.Float.floatToRawIntBits(5.0f) else 240 }
        }
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), outOfRange, "test")
        val note = outcome.table.notes.first { it.field == "insideTemp" }
        assertEquals(FidResolution.REJECTED, note.outcome)
        assertEquals("out of range", note.reason)
        assertEquals(FidMap.byField.getValue("insideTemp").fid, outcome.table.fid("insideTemp"))
    }

    @Test
    fun `a candidate that another entry already reads is refused`() = runTest {
        val entries = listOf(
            FidEntry("alpha", 1000, 100, 5, Decoder.INT_RAW, symbol = "Ac.ALPHA"),
            FidEntry("beta", 1000, 200, 5, Decoder.INT_RAW, symbol = "Ac.BETA"),
        )
        // The catalog sends alpha onto beta's address.
        val collidingCatalog = FidCatalog(mapOf("Ac.ALPHA" to 200, "Ac.BETA" to 200), mapOf("AC" to 1000))
        val outcome = FidResolver.resolve(entries, collidingCatalog, alwaysPlausible, "test")
        assertEquals(100, outcome.table.fid("alpha"))
        assertEquals("collision", outcome.table.notes.first { it.field == "alpha" }.reason)
    }

    @Test
    fun `entries sharing a symbol are aliases, not a collision`() = runTest {
        val entries = listOf(
            FidEntry("alpha", 1000, 100, 5, Decoder.INT_RAW, symbol = "Ac.ALPHA"),
            FidEntry("alphaAlias", 1000, 100, 5, Decoder.INT_RAW, symbol = "Ac.ALPHA"),
        )
        val catalog = FidCatalog(mapOf("Ac.ALPHA" to 300), mapOf("AC" to 1000))
        val outcome = FidResolver.resolve(entries, catalog, alwaysPlausible, "test")
        assertEquals(300, outcome.table.fid("alpha"))
        assertEquals(300, outcome.table.fid("alphaAlias"))
    }

    @Test
    fun `a silent probe transport keeps the constants and says so`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), alwaysSilent, "test")
        assertTrue(outcome.probeTransportDead)
        assertEquals(0, outcome.table.catalogCount)
        assertEquals("no answer", outcome.table.notes.first { it.field == "soc" }.reason)
    }

    @Test
    fun `an empty catalog is the same as no catalog`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, FidCatalog.EMPTY, alwaysPlausible, "test")
        assertEquals(FidMap.all.size, outcome.table.constCount)
        FidMap.all.forEach { assertEquals(it.fid, outcome.table.fid(it.field)) }
    }

    @Test
    fun `a probe that throws leaves everything on constants`() = runTest {
        val exploding = FidProbe { error("binder died") }
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), exploding, "test")
        assertEquals(0, outcome.table.catalogCount)
        FidMap.all.forEach { assertEquals(it.fid, outcome.table.fid(it.field)) }
    }

    @Test
    fun `device follows the symbol namespace only for a fid the catalog moved`() = runTest {
        val outcome = FidResolver.resolve(FidMap.all, catalog("songplus"), alwaysPlausible, "test")
        // The tester's build reads the 12V voltage from dev 1032 (the Ota namespace) and the
        // compressor from dev 1005 (Power), while ours read 1001 and 1000 on Leopard 3.
        assertEquals(1032, outcome.table.device("voltage12v"))
        assertEquals(1005, outcome.table.device("compressorW"))
        // autoWipers is a Setting symbol we read from the wiper device: its fid is unchanged
        // on this car, so the device must not be re-derived either.
        assertEquals(1046, outcome.table.device("autoWipers"))
    }

    private companion object {
        const val STALE_BY_DESIGN = "chargeBatteryVolt"
    }
}
