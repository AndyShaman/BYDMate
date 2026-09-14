package com.bydmate.app.ui.tech

import org.junit.Assert.assertEquals
import org.junit.Test

class TechCardOrderTest {

    @Test
    fun `empty storage reads as the factory order`() {
        assertEquals(TechCardOrder.DEFAULT, TechCardOrder.parse(""))
    }

    @Test
    fun `a full saved order round-trips through storage`() {
        val order = listOf(
            TechCard.MOTORS, TechCard.TYRES, TechCard.BATTERY_NOW,
            TechCard.CLIMATE, TechCard.HISTORY, TechCard.LIMITS,
        )
        assertEquals(order, TechCardOrder.parse(TechCardOrder.serialize(order)))
    }

    @Test
    fun `a repeated id is kept once and the rest appended in factory order`() {
        assertEquals(
            listOf(
                TechCard.MOTORS, TechCard.BATTERY_NOW,
                TechCard.LIMITS, TechCard.HISTORY, TechCard.CLIMATE, TechCard.TYRES,
            ),
            TechCardOrder.parse("motors,motors"),
        )
    }

    /** Dragging forward drops the card after the target; dragging back, before it. */
    @Test
    fun `a move inserts the card into the target slot`() {
        val order = listOf(TechCard.BATTERY_NOW, TechCard.LIMITS, TechCard.HISTORY)
        assertEquals(
            listOf(TechCard.LIMITS, TechCard.HISTORY, TechCard.BATTERY_NOW),
            TechCardOrder.move(order, TechCard.BATTERY_NOW, TechCard.HISTORY),
        )
        assertEquals(
            listOf(TechCard.HISTORY, TechCard.BATTERY_NOW, TechCard.LIMITS),
            TechCardOrder.move(order, TechCard.HISTORY, TechCard.BATTERY_NOW),
        )
    }

    @Test
    fun `dropping a card on itself changes nothing`() {
        assertEquals(
            TechCardOrder.DEFAULT,
            TechCardOrder.move(TechCardOrder.DEFAULT, TechCard.MOTORS, TechCard.MOTORS),
        )
    }

    /**
     * VadimV asked for the front/rear power split at the bottom: at the top it repeated what
     * the per-motor rows already show and cost a line of screen height.
     */
    @Test
    fun `the motors card draws the power split last`() {
        assertEquals(
            listOf(
                MotorRow.HEADER,
                MotorRow.MOTOR_TEMP,
                MotorRow.INVERTER_TEMP,
                MotorRow.RPM,
                MotorRow.PEDALS,
                MotorRow.POWER_SPLIT,
            ),
            MOTOR_CARD_ROWS,
        )
    }
}
