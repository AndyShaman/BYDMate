package com.bydmate.app.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class NaviRouteHolderTest {

    @Before
    @After
    fun resetHolder() {
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
    }

    @Test fun update_with_all_blank_fields_is_ignored() {
        NaviRouteHolder.update(NaviRouteHolder.NAVI_PACKAGE, null, "", "  ", 1_000L)
        assertNull(NaviRouteHolder.latest)
    }

    @Test fun clear_from_other_package_does_not_reset() {
        NaviRouteHolder.update(NaviRouteHolder.NAVI_PACKAGE, "title", null, null, 1_000L)
        NaviRouteHolder.clear("com.other.app")
        assertNotNull(NaviRouteHolder.latest)
    }

    @Test
    fun `parsed-only notification without extras is still stored`() {
        NaviRouteHolder.update(
            NaviRouteHolder.NAVI_PACKAGE, null, null, null, 1L,
            NaviNotificationParser.Parsed(
                maneuver = "направо", maneuverResource = null,
                distance = "350 м", street = null, bigTexts = emptyList(),
            ),
        )
        assertEquals("350 м", NaviRouteHolder.latest?.maneuverDistance)
    }
}
