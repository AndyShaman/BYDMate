package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SeatLevelMemoryTest {

    @Test
    fun `a step command carries its seat and level`() {
        assertEquals(
            ActionDispatcher.TOGGLE_SEAT_HEAT_DRIVER to 5,
            SeatLevelMemory.parseSeatCommand("主驾座椅加热5档"),
        )
        assertEquals(
            ActionDispatcher.TOGGLE_SEAT_VENT_PASSENGER to 2,
            SeatLevelMemory.parseSeatCommand("副驾座椅通风2档"),
        )
    }

    /** «Off» is a seat command too, it just carries no step to remember. */
    @Test
    fun `an off command reads as level zero`() {
        assertEquals(
            ActionDispatcher.TOGGLE_SEAT_HEAT_PASSENGER to 0,
            SeatLevelMemory.parseSeatCommand("副驾座椅加热关闭"),
        )
    }

    @Test
    fun `anything else is not a seat command`() {
        assertNull(SeatLevelMemory.parseSeatCommand("自动空调"))
        assertNull(SeatLevelMemory.parseSeatCommand("主驾座椅加热"))
        assertNull(SeatLevelMemory.parseSeatCommand(""))
    }
}
