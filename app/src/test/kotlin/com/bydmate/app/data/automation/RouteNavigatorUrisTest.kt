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
    }

    @Test fun `packages follow the selection`() {
        assertEquals("ru.yandex.yandexnavi", RouteNavigatorUris.packageOf(RouteNavigatorUris.YANDEX))
        assertEquals("ru.dublgis.dgismobile", RouteNavigatorUris.packageOf(RouteNavigatorUris.DGIS))
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
}
