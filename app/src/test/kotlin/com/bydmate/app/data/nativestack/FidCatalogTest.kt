package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FidCatalogTest {

    @Test fun `parses symbols and the device table, skipping the header`() {
        val catalog = FidCatalog.parse(
            """
            BYDMate 3.15.0 (449), 2026-09-10 13:20
            model: trinket for arm64  build: QKQ1.200816.002 release-key
            ---
            Ac.AC_TEMP_MAIN=330301480
            BYDAutoConstants.BYDAUTO_DEVICE_AC=1000
            """.trimIndent()
        )
        assertEquals(330301480, catalog.fidOf("Ac.AC_TEMP_MAIN"))
        assertEquals(1000, catalog.deviceOf("AC"))
        assertEquals(1, catalog.totalSymbols)
    }

    @Test fun `ignores the flat BYDAutoFeatureIds namespace`() {
        val catalog = FidCatalog.parse("BYDAutoFeatureIds.AC_TEMP_MAIN=1\nAc.AC_TEMP_MAIN=2")
        assertNull(catalog.fidOf("BYDAutoFeatureIds.AC_TEMP_MAIN"))
        assertEquals(2, catalog.fidOf("Ac.AC_TEMP_MAIN"))
    }

    @Test fun `drops a symbol listed twice with different values`() {
        val catalog = FidCatalog.parse("Ac.A=1\nAc.A=2\nAc.B=3\nAc.B=3")
        assertNull(catalog.fidOf("Ac.A"))
        assertEquals(3, catalog.fidOf("Ac.B"))
    }

    @Test fun `skips values that do not fit in an int`() {
        val catalog = FidCatalog.parse("Ac.LONG=9999999999\nAc.OK=-1728052956")
        assertNull(catalog.fidOf("Ac.LONG"))
        assertEquals(-1728052956, catalog.fidOf("Ac.OK"))
    }

    @Test fun `every fixture parses into a usable catalog`() {
        listOf("leopard3", "songplus", "dilink4").forEach { name ->
            val text = checkNotNull(
                javaClass.classLoader?.getResourceAsStream("fid-catalog-$name.txt")
            ).bufferedReader().readText()
            val catalog = FidCatalog.parse(text)
            assertEquals("device table of $name", 50, catalog.devices.size)
            assertEquals("$name has symbols", true, catalog.symbols.size > 100)
        }
    }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream(name)) { "missing fixture $name" }
            .bufferedReader().readText()

    private val flatOnly = "BYDAutoConstants.BYDAUTO_DEVICE_STATISTIC=1014\n" +
        "BYDAutoFeatureIds.STATISTIC_ELEC_PERCENTAGE=1033543720"

    @Test fun `a flat-only dump maps flat names onto the wanted nested symbols`() {
        val catalog = FidCatalog.parse(flatOnly, listOf("Statistic.STATISTIC_ELEC_PERCENTAGE"))
        assertEquals(1033543720, catalog.fidOf("Statistic.STATISTIC_ELEC_PERCENTAGE"))
        assertEquals(1014, catalog.deviceOf("STATISTIC"))
    }

    @Test fun `one nested line switches the flat fallback off`() {
        val catalog = FidCatalog.parse(
            "BYDAutoFeatureIds.STATISTIC_ELEC_PERCENTAGE=1\nAc.AC_TEMP_MAIN=2",
            listOf("Statistic.STATISTIC_ELEC_PERCENTAGE", "Ac.AC_TEMP_MAIN"),
        )
        assertNull(catalog.fidOf("Statistic.STATISTIC_ELEC_PERCENTAGE"))
        assertEquals(2, catalog.fidOf("Ac.AC_TEMP_MAIN"))
        assertEquals(1, catalog.symbols.size)
    }

    @Test fun `without aliases a flat dump still parses empty`() {
        assertEquals(true, FidCatalog.parse(flatOnly).symbols.isEmpty())
    }

    @Test fun `flat names that no wanted symbol asks for are not added`() {
        val catalog = FidCatalog.parse(
            "BYDAutoFeatureIds.STATISTIC_ELEC_PERCENTAGE=1033543720\nBYDAutoFeatureIds.AC_TEMP_MAIN=330301480",
            listOf("Statistic.STATISTIC_ELEC_PERCENTAGE"),
        )
        assertEquals(1, catalog.symbols.size)
    }

    @Test fun `a flat name listed twice with different values is dropped`() {
        val catalog = FidCatalog.parse("BYDAutoFeatureIds.X=1\nBYDAutoFeatureIds.X=2", listOf("Ac.X"))
        assertNull(catalog.fidOf("Ac.X"))
    }

    @Test fun `the Yuan Pro flat fixture yields a usable catalog`() {
        val catalog = FidCatalog.parse(resource("fid-catalog-yuanpro-flat.txt"), FidCatalogManager.wantedSymbols())
        assertEquals(50, catalog.devices.size)
        assertEquals(1033543720, catalog.fidOf("Statistic.STATISTIC_ELEC_PERCENTAGE"))
        assertEquals(1033543696, catalog.fidOf("Statistic.STATISTIC_TOTAL_MILEAGE"))
        assertEquals(303038472, catalog.fidOf("Speed.SPEED_AUTO_SPEED"))
        assertEquals(1145047064, catalog.fidOf("Charging.CHARGING_CHARGE_CURRENT"))
    }

    /** Real-data pin: on a firmware that has both namespaces the flat value is the nested value. */
    @Test fun `the flat namespace of a nested dump equals its nested classes`() {
        val nested = FidCatalog.parse(resource("fid-catalog-songplus.txt"))
        val flat = FidCatalog.parse(resource("fid-catalog-songplus-flat.txt"), nested.symbols.keys)
        assertEquals(true, flat.symbols.size > 50)
        flat.symbols.forEach { (symbol, value) -> assertEquals(symbol, nested.fidOf(symbol), value) }
    }
}
