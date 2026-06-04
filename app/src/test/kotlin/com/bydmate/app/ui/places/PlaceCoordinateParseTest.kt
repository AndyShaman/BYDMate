package com.bydmate.app.ui.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Regression for the "place coordinates always red, cannot save" bug: in a ru/be locale the
 * lat/lon fields are seeded with a comma decimal separator ("52,069746"), and a bare
 * toDoubleOrNull() returned null on the comma. parseCoordinate must accept both separators.
 */
class PlaceCoordinateParseTest {

    @Test
    fun `comma decimal separator parses (the regression)`() {
        // Documents why the bare parse failed before the fix.
        assertNull("52,069746".toDoubleOrNull())
        assertEquals(52.069746, parseCoordinate("52,069746")!!, 1e-9)
        assertEquals(23.763435, parseCoordinate("23,763435")!!, 1e-9)
    }

    @Test
    fun `dot decimal separator still parses`() {
        assertEquals(52.069746, parseCoordinate("52.069746")!!, 1e-9)
        assertEquals(-180.0, parseCoordinate("-180.0")!!, 1e-9)
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(23.763435, parseCoordinate("  23,763435  ")!!, 1e-9)
    }

    @Test
    fun `garbage and empty input return null`() {
        assertNull(parseCoordinate(""))
        assertNull(parseCoordinate("abc"))
    }
}
