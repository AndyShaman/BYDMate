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

    @Test fun update_from_other_package_is_ignored() {
        NaviRouteHolder.update("com.other.app", "title", "text", null, 1_000L)
        assertNull(NaviRouteHolder.latest)
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

    @Test fun clear_from_navi_package_resets() {
        NaviRouteHolder.update(NaviRouteHolder.NAVI_PACKAGE, "title", null, null, 1_000L)
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
        assertNull(NaviRouteHolder.latest)
    }

    @Test fun update_overwrites_previous_snapshot() {
        NaviRouteHolder.update(NaviRouteHolder.NAVI_PACKAGE, "old", "old text", null, 1_000L)
        NaviRouteHolder.update(NaviRouteHolder.NAVI_PACKAGE, "new", "new text", null, 2_000L)
        val snap = NaviRouteHolder.latest
        assertEquals("new", snap?.title)
        assertEquals("new text", snap?.text)
        assertEquals(2_000L, snap?.postedAtMs)
    }
}
