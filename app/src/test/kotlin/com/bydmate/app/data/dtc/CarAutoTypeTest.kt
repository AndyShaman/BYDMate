package com.bydmate.app.data.dtc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CarAutoTypeTest {

    @Test fun `a real model code is kept`() {
        assertEquals(260, CarAutoType.parse(260))
        assertEquals(1, CarAutoType.parse(1))
    }

    @Test fun `zero, 255, 65535, negatives and null mean unknown`() {
        for (raw in listOf(0, 255, 65535, -1, Int.MIN_VALUE)) assertNull("$raw", CarAutoType.parse(raw))
        assertNull(CarAutoType.parse(null))
    }
}
