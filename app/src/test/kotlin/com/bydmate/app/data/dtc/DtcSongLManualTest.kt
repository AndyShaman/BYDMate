package com.bydmate.app.data.dtc

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** The shipped tables (`src/main/assets/dtc/`), read from the module directory the tests run in. */
class DtcSongLManualTest {

    private fun asset(name: String) = File("src/main/assets/dtc/$name").reader(Charsets.UTF_8)

    private val d = DtcDescriptions(
        DtcTablesLoader.parse(asset("texts.tsv"), asset("manuals.tsv"), asset("rows.tsv"), asset("models.tsv")),
    )

    @Test fun `Song L autotype 265 reads its own manual 8788 as this model`() {
        // B1F7F04 is in the ADAS class of manual 8788 with one text
        val r = d.lookup(265, DtcEnergy.EV, "ADAS", "B1F7F04")
        assertNotNull(r)
        assertEquals(DtcDescLevel.EXACT, r!!.level)
        assertEquals("8788", r.manual)
    }

    @Test fun `autotype 113 stays unmapped`() {
        assertNull(d.lookup(113, DtcEnergy.EV, "ADAS", "B1F7F04")?.takeIf { it.level == DtcDescLevel.EXACT })
    }
}
