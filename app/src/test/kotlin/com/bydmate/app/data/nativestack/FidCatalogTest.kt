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
}
