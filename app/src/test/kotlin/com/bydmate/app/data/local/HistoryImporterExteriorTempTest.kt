package com.bydmate.app.data.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * energydata has no temperature of its own, so an imported trip may only borrow the live one
 * while the drive has just ended. Everything older is left empty on purpose.
 */
class HistoryImporterExteriorTempTest {

    private val now = 1_000_000_000L

    @Test
    fun `a trip that ended a minute ago takes the live temperature`() {
        assertEquals(-7, HistoryImporter.importedExteriorTemp(now - 60_000L, now, -7))
    }

    @Test
    fun `the window closes after ten minutes`() {
        assertEquals(-7, HistoryImporter.importedExteriorTemp(now - 10 * 60_000L, now, -7))
        assertNull(HistoryImporter.importedExteriorTemp(now - 10 * 60_000L - 1, now, -7))
    }

    @Test
    fun `no live reading means no temperature`() {
        assertNull(HistoryImporter.importedExteriorTemp(now, now, null))
    }

    @Test
    fun `a trip stamped in the future is not borrowed from either`() {
        assertNull(HistoryImporter.importedExteriorTemp(now + 60_000L, now, -7))
    }
}
