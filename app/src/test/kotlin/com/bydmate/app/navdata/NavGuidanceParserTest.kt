package com.bydmate.app.navdata

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NavGuidanceParserTest {

    private fun raw(
        maneuverDesc: String? = null, exitNumber: String? = null,
        distance: String? = null, distanceUnit: String? = null,
        nextStreet: String? = null, statusPanel: String? = null,
        etaTime: String? = null, etaDistance: String? = null, speedLimit: String? = null,
    ) = NavGuidanceParser.RawFields(
        maneuverDesc, exitNumber, distance, distanceUnit,
        nextStreet, statusPanel, etaTime, etaDistance, speedLimit,
    )

    @Test fun `no guidance widgets means null`() {
        assertNull(NavGuidanceParser.parse(raw(etaTime = "27 мин", speedLimit = "60")))
    }

    @Test fun `meters distance parsed`() {
        val d = NavGuidanceParser.parse(raw(maneuverDesc = "Поверните направо", distance = "250", distanceUnit = "м"))!!
        assertEquals(2, d.maneuverGaode)
        assertEquals(250, d.distanceMeters)
    }

    @Test fun `fractional km distance parsed`() {
        val d = NavGuidanceParser.parse(raw(maneuverDesc = ">>>", distance = "1,2", distanceUnit = "км"))!!
        assertEquals(1200, d.distanceMeters)
    }

    @Test fun `exit number forces roundabout exit`() {
        val d = NavGuidanceParser.parse(raw(maneuverDesc = "Поверните направо", exitNumber = "2", distance = "300", distanceUnit = "м"))!!
        assertEquals(24, d.maneuverGaode)
    }

    @Test fun `eta minutes and hours parsed`() {
        assertEquals(27 * 60, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaTime = "27 мин"))!!.etaSeconds)
        assertEquals(3900, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaTime = "1 ч 5 мин"))!!.etaSeconds)
    }

    @Test fun `total distance km parsed`() {
        assertEquals(28000, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaDistance = "28 км"))!!.totalDistMeters)
        assertEquals(800, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", etaDistance = "800 м"))!!.totalDistMeters)
    }

    @Test fun `road prefers next street over status panel`() {
        assertEquals("ул. Ленина", NavGuidanceParser.parse(raw(maneuverDesc = ">>>", nextStreet = "ул. Ленина", statusPanel = "ул. Качаны"))!!.road)
        assertEquals("ул. Качаны", NavGuidanceParser.parse(raw(maneuverDesc = ">>>", statusPanel = "ул. Качаны"))!!.road)
    }

    @Test fun `speed limit parsed`() {
        assertEquals(60, NavGuidanceParser.parse(raw(maneuverDesc = ">>>", speedLimit = "60"))!!.speedLimit)
    }

    @Test fun `distance text combined parsed`() {
        assertEquals(300, NavGuidanceParser.parseDistanceText("300 м"))
        assertEquals(1200, NavGuidanceParser.parseDistanceText("1,2 км"))
        assertEquals(0, NavGuidanceParser.parseDistanceText("скоро"))
        assertEquals(0, NavGuidanceParser.parseDistanceText(null))
    }
}
