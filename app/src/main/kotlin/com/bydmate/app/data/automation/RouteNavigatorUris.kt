package com.bydmate.app.data.automation

import android.net.Uri

/**
 * Deep links of the map apps the `navigate` action can open (#190): Yandex Navigator (default)
 * and 2GIS, chosen by the user in settings.
 *
 * The two schemes disagree on everything except the intent action: Yandex takes named `lat`/`lon`
 * parameters, 2GIS takes a path segment with the LONGITUDE first. Keeping both shapes here means
 * the dispatcher branches once on the app and never on the coordinate order.
 */
object RouteNavigatorUris {

    /** SharedPreferences file the voice/agent settings already live in. */
    const val PREFS_NAME = "voice"
    const val KEY_ROUTE_NAVIGATOR = "route_navigator"

    const val YANDEX = "yandex"
    const val DGIS = "dgis"

    const val YANDEX_PACKAGE = "ru.yandex.yandexnavi"
    const val DGIS_PACKAGE = "ru.dublgis.dgismobile"

    /** Log/diagnostic names of the three deep links. */
    const val MODE_SEARCH = "search"
    const val MODE_SHOW = "show"
    const val MODE_ROUTE = "route"

    /** Stored value normalised: anything unset or unknown is the Yandex default. */
    fun normalize(value: String?): String = if (value == DGIS) DGIS else YANDEX

    fun packageOf(navigator: String): String =
        if (normalize(navigator) == DGIS) DGIS_PACKAGE else YANDEX_PACKAGE

    /** Free-text search on the map ("найди кафе"). */
    fun search(navigator: String, query: String): String =
        if (normalize(navigator) == DGIS) "dgis://2gis.ru/search/${Uri.encode(query)}"
        else "yandexnavi://map_search?text=${Uri.encode(query)}"

    /** Pin without a route. [label] is the pin caption — Yandex only, the 2GIS geo link takes none. */
    fun showPoint(navigator: String, lat: Double, lon: Double, label: String?): String =
        if (normalize(navigator) == DGIS) "dgis://2gis.ru/geo/$lon,$lat"
        else buildString {
            append("yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=14")
            if (label != null) append("&desc=${Uri.encode(label)}")
        }

    /** Car route to the point. */
    fun route(navigator: String, lat: Double, lon: Double): String =
        if (normalize(navigator) == DGIS) "dgis://2gis.ru/routeSearch/rsType/car/to/$lon,$lat"
        else "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"
}
