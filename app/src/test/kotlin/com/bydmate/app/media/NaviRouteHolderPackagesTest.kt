package com.bydmate.app.media

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NaviRouteHolderPackagesTest {

    @After fun cleanup() {
        NaviRouteHolder.clear(NaviRouteHolder.NAVI_PACKAGE)
    }

    @Test fun `inhouse variant accepted`() {
        NaviRouteHolder.update("ru.yandex.yandexnavi.inhouse", "5 км", "направо", null, 1000L)
        assertNotNull(NaviRouteHolder.latest)
        assertEquals("5 км", NaviRouteHolder.latest?.title)
    }

    @Test fun `rustore variant accepted and cleared`() {
        NaviRouteHolder.update("ru.yandex.yandexnavi.rustore", "5 км", null, null, 1000L)
        assertNotNull(NaviRouteHolder.latest)
        NaviRouteHolder.clear("ru.yandex.yandexnavi.rustore")
        assertNull(NaviRouteHolder.latest)
    }

    @Test fun `foreign package still rejected`() {
        NaviRouteHolder.update("com.spotify.music", "x", "y", null, 1000L)
        assertNull(NaviRouteHolder.latest)
    }
}
