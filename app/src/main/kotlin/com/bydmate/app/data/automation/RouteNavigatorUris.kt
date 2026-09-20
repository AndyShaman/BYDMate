package com.bydmate.app.data.automation

import android.net.Uri

/**
 * Deep links of the map apps the `navigate` action can open (#190, #200): Yandex Navigator
 * (default), 2GIS or Yandex Maps, chosen by the user in settings.
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
    const val WAZE = "waze"
    const val GOOGLE_MAPS = "google_maps"

    /**
     * Third settings value (#200): the same yandexmaps:// dialect that `app="maps"` reaches
     * per-command (see below) is now also a persisted "Навигатор для маршрутов" choice, so
     * routes/search/points without an explicit `app` land here too.
     */
    const val MAPS = "maps"

    const val YANDEX_PACKAGE = "ru.yandex.yandexnavi"
    const val DGIS_PACKAGE = "ru.dublgis.dgismobile"
    const val WAZE_PACKAGE = "com.waze"
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"

    /** Log/diagnostic names of the three deep links. */
    const val MODE_SEARCH = "search"
    const val MODE_SHOW = "show"
    const val MODE_ROUTE = "route"

    /** Stored value normalised: anything unset or unknown is the Yandex Navigator default. */
    fun normalize(value: String?): String = when (value) {
        DGIS -> DGIS
        MAPS -> MAPS
        WAZE -> WAZE
        GOOGLE_MAPS -> GOOGLE_MAPS
        else -> YANDEX
    }

    fun packageOf(navigator: String): String = when (normalize(navigator)) {
        DGIS -> DGIS_PACKAGE
        WAZE -> WAZE_PACKAGE
        GOOGLE_MAPS -> GOOGLE_MAPS_PACKAGE
        else -> YANDEX_PACKAGE
    }

    fun intentPackage(navigator: String): String? = when (normalize(navigator)) {
        DGIS -> DGIS_PACKAGE
        WAZE -> WAZE_PACKAGE
        GOOGLE_MAPS -> GOOGLE_MAPS_PACKAGE
        else -> null
    }

    /** Free-text search on the map ("найди кафе"). */
    fun search(navigator: String, query: String): String = when (normalize(navigator)) {
        DGIS -> "dgis://2gis.ru/search/${Uri.encode(query)}"
        WAZE -> "https://waze.com/ul?q=${Uri.encode(query)}"
        GOOGLE_MAPS -> "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"
        else -> "yandexnavi://map_search?text=${Uri.encode(query)}"
    }

    /** Pin without a route. */
    fun showPoint(navigator: String, lat: Double, lon: Double, label: String?): String =
        when (normalize(navigator)) {
            DGIS -> "dgis://2gis.ru/geo/$lon,$lat"
            WAZE -> "https://waze.com/ul?ll=$lat,$lon"
            GOOGLE_MAPS -> buildString {
                append("https://www.google.com/maps/search/?api=1&query=$lat%2C$lon")
                if (label != null) append("%20(${Uri.encode(label)})")
            }
            else -> buildString {
                append("yandexnavi://show_point_on_map?lat=$lat&lon=$lon&zoom=14")
                if (label != null) append("&desc=${Uri.encode(label)}")
            }
        }

    /** Car route to the point. */
    fun route(navigator: String, lat: Double, lon: Double): String =
        when (normalize(navigator)) {
            DGIS -> "dgis://2gis.ru/routeSearch/rsType/car/to/$lon,$lat"
            WAZE -> "https://waze.com/ul?ll=$lat,$lon&navigate=yes"
            GOOGLE_MAPS -> "https://www.google.com/maps/dir/?api=1&destination=$lat%2C$lon&travelmode=driving&dir_action=navigate"
            else -> "yandexnavi://build_route_on_map?lat_to=$lat&lon_to=$lon"
        }

    /**
     * Yandex Maps' own dialect (#200), reached either per command with `app="maps"` or, since
     * the setting grew a third value, when [MAPS] is the chosen navigator — Maps is a separate
     * app from the Navigator and speaks `yandexmaps://`. The route form leaves the start point
     * empty (`rtext=~to`, "from me"); that form is absent from Yandex' public docs but is what
     * current Maps builds honour.
     */
    fun mapsSearch(query: String): String = "yandexmaps://maps.yandex.ru/?text=${Uri.encode(query)}"

    /** Pin without a route. The `pt` dialect carries no caption, so the label stays in the log. */
    fun mapsShowPoint(lat: Double, lon: Double): String = "yandexmaps://maps.yandex.ru/?pt=$lat,$lon&z=14"

    fun mapsRoute(lat: Double, lon: Double): String = "yandexmaps://maps.yandex.ru/?rtext=~$lat,$lon&rtt=auto"
}
