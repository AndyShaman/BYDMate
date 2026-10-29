package com.bydmate.app.ui.tech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TechCardOrderTest {

    @Test
    fun `empty storage reads as the factory order`() {
        assertEquals(TechCardOrder.DEFAULT, TechCardOrder.parse(""))
    }

    @Test
    fun `a full saved order round-trips through storage`() {
        val order = listOf(
            TechCard.MOTORS, TechCard.CAR_STATE, TechCard.BATTERY_NOW,
            TechCard.DIAGNOSTICS, TechCard.HISTORY, TechCard.LIMITS,
        )
        assertEquals(order, TechCardOrder.parse(TechCardOrder.serialize(order)))
    }

    @Test
    fun `a repeated id is kept once and the rest appended in factory order`() {
        assertEquals(
            listOf(
                TechCard.MOTORS, TechCard.BATTERY_NOW,
                TechCard.LIMITS, TechCard.HISTORY, TechCard.CAR_STATE, TechCard.DIAGNOSTICS,
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

    /** DM-i (#184): only a hybrid's order holds the fuel card; any other car drops it. */
    @Test
    fun `the fuel card joins the order only on a hybrid`() {
        val hybrid = com.bydmate.app.data.nativestack.VehicleKind.HYBRID
        val withFuel = TechCardOrder.forKind(TechCardOrder.DEFAULT, hybrid)
        assertEquals(TechCardOrder.DEFAULT + TechCard.FUEL, withFuel)
        assertEquals(withFuel, TechCardOrder.forKind(withFuel, hybrid))
        assertEquals(TechCardOrder.DEFAULT, TechCardOrder.forKind(withFuel, com.bydmate.app.data.nativestack.VehicleKind.EV))
        assertEquals(TechCardOrder.DEFAULT, TechCardOrder.forKind(withFuel, com.bydmate.app.data.nativestack.VehicleKind.UNKNOWN))
    }

    private val hybrid = com.bydmate.app.data.nativestack.VehicleKind.HYBRID
    private val ev = com.bydmate.app.data.nativestack.VehicleKind.EV

    @Test
    fun `legacy climate and tyres merge into one car-state card at the earlier slot`() {
        val parsed = TechCardOrder.parse("battery_now,motors,climate,tyres,limits,history")
        assertEquals(
            listOf(
                TechCard.BATTERY_NOW, TechCard.MOTORS, TechCard.CAR_STATE,
                TechCard.LIMITS, TechCard.HISTORY, TechCard.DIAGNOSTICS,
            ),
            parsed,
        )
        assertEquals("battery_now,motors,climate,limits,history,diagnostics", TechCardOrder.serialize(parsed))
    }

    @Test
    fun `a stored tyres id alone puts the car-state card first`() {
        assertEquals(
            listOf(
                TechCard.CAR_STATE, TechCard.BATTERY_NOW, TechCard.LIMITS,
                TechCard.HISTORY, TechCard.MOTORS, TechCard.DIAGNOSTICS,
            ),
            TechCardOrder.parse("tyres,battery_now,climate"),
        )
    }

    @Test
    fun `a hybrid keeps its fuel slot and an EV drops it`() {
        val saved = "battery_now,limits,history,motors,climate,tyres,fuel"
        assertEquals(
            listOf(
                TechCard.BATTERY_NOW, TechCard.LIMITS, TechCard.HISTORY, TechCard.MOTORS,
                TechCard.CAR_STATE, TechCard.FUEL, TechCard.DIAGNOSTICS,
            ),
            TechCardOrder.forKind(TechCardOrder.parse(saved), hybrid),
        )
        assertEquals(
            listOf(
                TechCard.BATTERY_NOW, TechCard.LIMITS, TechCard.HISTORY, TechCard.MOTORS,
                TechCard.CAR_STATE, TechCard.DIAGNOSTICS,
            ),
            TechCardOrder.forKind(TechCardOrder.parse(saved), ev),
        )
    }

    @Test
    fun `an order saved before fuel existed gets diagnostics then fuel on a hybrid`() {
        assertEquals(
            listOf(
                TechCard.BATTERY_NOW, TechCard.LIMITS, TechCard.HISTORY, TechCard.MOTORS,
                TechCard.CAR_STATE, TechCard.DIAGNOSTICS, TechCard.FUEL,
            ),
            TechCardOrder.forKind(TechCardOrder.parse("battery_now,limits,history,motors,climate,tyres"), hybrid),
        )
    }

    @Test
    fun `empty storage gives six cards and unknown ids are ignored`() {
        assertEquals(6, TechCardOrder.parse("").size)
        assertEquals(
            listOf(
                TechCard.BATTERY_NOW, TechCard.LIMITS, TechCard.HISTORY,
                TechCard.MOTORS, TechCard.CAR_STATE, TechCard.DIAGNOSTICS,
            ),
            TechCardOrder.parse("foo,battery_now"),
        )
        assertFalse(TechCardOrder.serialize(TechCardOrder.parse("tyres,climate")).contains("tyres"))
    }
}
