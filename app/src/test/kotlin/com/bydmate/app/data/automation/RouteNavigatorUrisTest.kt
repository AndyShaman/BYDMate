package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #190: the Yandex links must stay byte-for-byte what every released build sent, and the 2GIS
 * ones must carry the longitude first — the mistake this object exists to make impossible.
 * Robolectric because the query encoder is android.net.Uri.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RouteNavigatorUrisTest {

    @Test fun `unknown and missing values resolve to yandex`() {
        assertEquals(RouteNavigatorUris.YANDEX, RouteNavigatorUris.normalize(null))
        assertEquals(RouteNavigatorUris.YANDEX, RouteNavigatorUris.normalize(""))
        assertEquals(RouteNavigatorUris.YANDEX, RouteNavigatorUris.normalize("2gis"))
        assertEquals(RouteNavigatorUris.DGIS, RouteNavigatorUris.normalize("dgis"))
        assertEquals(RouteNavigatorUris.WAZE, RouteNavigatorUris.normalize("waze"))
        assertEquals(RouteNavigatorUris.GOOGLE_MAPS, RouteNavigatorUris.normalize("google_maps"))
    }

    @Test fun `packages follow the selection`() {
        assertEquals("ru.yandex.yandexnavi", RouteNavigatorUris.packageOf(RouteNavigatorUris.YANDEX))
        assertEquals("ru.dublgis.dgismobile", RouteNavigatorUris.packageOf(RouteNavigatorUris.DGIS))
        assertEquals("com.waze", RouteNavigatorUris.packageOf(RouteNavigatorUris.WAZE))
        assertEquals(
            "com.google.android.apps.maps",
            RouteNavigatorUris.packageOf(RouteNavigatorUris.GOOGLE_MAPS),
        )
    }

    @Test fun `yandex links are unchanged`() {
        assertEquals(
            "yandexnavi://map_search?text=%D0%BA%D0%B0%D1%84%D0%B5",
            RouteNavigatorUris.search(RouteNavigatorUris.YANDEX, "кафе"),
        )
        assertEquals(
            "yandexnavi://show_point_on_map?lat=55.75&lon=37.62&zoom=14",
            RouteNavigatorUris.showPoint(RouteNavigatorUris.YANDEX, 55.75, 37.62, null),
        )
        assertEquals(
            "yandexnavi://show_point_on_map?lat=55.75&lon=37.62&zoom=14&desc=%D0%9A%D0%B0%D1%84%D0%B5",
            RouteNavigatorUris.showPoint(RouteNavigatorUris.YANDEX, 55.75, 37.62, "Кафе"),
        )
        assertEquals(
            "yandexnavi://build_route_on_map?lat_to=57.0&lon_to=36.0",
            RouteNavigatorUris.route(RouteNavigatorUris.YANDEX, 57.0, 36.0),
        )
    }

    @Test fun `2gis links take the longitude first`() {
        assertEquals(
            "dgis://2gis.ru/geo/37.62,55.75",
            RouteNavigatorUris.showPoint(RouteNavigatorUris.DGIS, 55.75, 37.62, "Кафе"),
        )
        assertEquals(
            "dgis://2gis.ru/routeSearch/rsType/car/to/36.0,57.0",
            RouteNavigatorUris.route(RouteNavigatorUris.DGIS, 57.0, 36.0),
        )
    }

    @Test fun `2gis search encodes spaces and cyrillic`() {
        assertEquals(
            "dgis://2gis.ru/search/%D0%BA%D0%B0%D1%84%D0%B5%20%D1%83%20%D0%B4%D0%BE%D0%BC%D0%B0",
            RouteNavigatorUris.search(RouteNavigatorUris.DGIS, "кафе у дома"),
        )
    }

    /** The pin caption is a Yandex-only parameter; the 2GIS geo link must not grow one. */
    @Test fun `2gis show point ignores the label`() {
        assertEquals(
            RouteNavigatorUris.showPoint(RouteNavigatorUris.DGIS, 55.75, 37.62, null),
            RouteNavigatorUris.showPoint(RouteNavigatorUris.DGIS, 55.75, 37.62, "Кафе"),
        )
    }

    @Test fun `waze links use the official deep-link format`() {
        assertEquals(
            "https://waze.com/ul?q=%D0%BA%D0%B0%D1%84%D0%B5",
            RouteNavigatorUris.search(RouteNavigatorUris.WAZE, "кафе"),
        )
        assertEquals(
            "https://waze.com/ul?ll=55.75,37.62",
            RouteNavigatorUris.showPoint(RouteNavigatorUris.WAZE, 55.75, 37.62, "Кафе"),
        )
        assertEquals(
            "https://waze.com/ul?ll=57.0,36.0&navigate=yes",
            RouteNavigatorUris.route(RouteNavigatorUris.WAZE, 57.0, 36.0),
        )
    }

    @Test fun `google maps uses official maps urls`() {
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=%D0%BA%D0%B0%D1%84%D0%B5",
            RouteNavigatorUris.search(RouteNavigatorUris.GOOGLE_MAPS, "кафе"),
        )
        assertEquals(
            "https://www.google.com/maps/search/?api=1&query=55.75%2C37.62%20(%D0%9A%D0%B0%D1%84%D0%B5)",
            RouteNavigatorUris.showPoint(RouteNavigatorUris.GOOGLE_MAPS, 55.75, 37.62, "Кафе"),
        )
        assertEquals(
            "https://www.google.com/maps/dir/?api=1&destination=57.0%2C36.0&travelmode=driving&dir_action=navigate",
            RouteNavigatorUris.route(RouteNavigatorUris.GOOGLE_MAPS, 57.0, 36.0),
        )
    }

    /** #200: the Maps dialect is per-command and never reachable from the settings value. */
    @Test fun `maps links use the yandexmaps scheme`() {
        assertEquals("yandexmaps://maps.yandex.ru/?rtext=~55.75,37.62&rtt=auto",
            RouteNavigatorUris.mapsRoute(55.75, 37.62))
        assertEquals("yandexmaps://maps.yandex.ru/?pt=55.75,37.62&z=14",
            RouteNavigatorUris.mapsShowPoint(55.75, 37.62))
        assertEquals("yandexmaps://maps.yandex.ru/?text=%D0%BA%D0%B0%D1%84%D0%B5",
            RouteNavigatorUris.mapsSearch("кафе"))
    }

    /** #200: Maps grew into the third settings value, alongside its per-command app="maps" use. */
    @Test fun `maps is a valid settings selection`() {
        assertEquals(RouteNavigatorUris.MAPS, RouteNavigatorUris.normalize(RouteNavigatorUris.MAPS))
    }
}
