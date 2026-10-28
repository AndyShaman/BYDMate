package com.bydmate.app.data.nativestack

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldGuardsTest {

    @Test fun pm25_value_ranges() {
        for (f in listOf("pm25In", "pm25Out")) {
            assertEquals(0, FieldGuards.int(f, 0))
            assertEquals(3000, FieldGuards.int(f, 3000))
            assertNull(FieldGuards.int(f, 3001))
            assertNull(FieldGuards.int(f, -1))
        }
    }

    @Test fun pm25_level_ranges() {
        for (f in listOf("pm25LevelIn", "pm25LevelOut")) {
            assertEquals(1, FieldGuards.int(f, 1))
            assertEquals(6, FieldGuards.int(f, 6))
            assertNull(FieldGuards.int(f, 7))
            assertNull(FieldGuards.int(f, 0))
        }
    }
}
