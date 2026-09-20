package com.bydmate.app.data.automation

import android.content.Context
import com.bydmate.app.navdata.NavPackages

internal object RouteNavigatorResolver {
    fun resolve(
        context: Context,
        isPackageInstalled: (String) -> Boolean,
        log: (String) -> Unit,
    ): Pair<String, String?> {
        val chosen = RouteNavigatorUris.normalize(
            context.getSharedPreferences(RouteNavigatorUris.PREFS_NAME, Context.MODE_PRIVATE)
                .getString(RouteNavigatorUris.KEY_ROUTE_NAVIGATOR, null),
        )
        log("navigate: navigator=$chosen")

        if (chosen == RouteNavigatorUris.DGIS &&
            !isPackageInstalled(RouteNavigatorUris.DGIS_PACKAGE)
        ) {
            log("navigate: 2gis not installed, falling back to yandex")
            return RouteNavigatorUris.YANDEX to "2ГИС не установлен, открыт Яндекс Навигатор"
        }

        if (chosen == RouteNavigatorUris.MAPS &&
            NavPackages.YANDEX_MAPS.none(isPackageInstalled)
        ) {
            log("navigate: yandex maps not installed, falling back to yandex")
            return RouteNavigatorUris.YANDEX to "Яндекс Карты не установлены, открыт Яндекс Навигатор"
        }

        if (chosen == RouteNavigatorUris.WAZE &&
            !isPackageInstalled(RouteNavigatorUris.WAZE_PACKAGE)
        ) {
            return RouteNavigatorUris.YANDEX to "Waze не установлен, открыт Яндекс Навигатор"
        }

        if (chosen == RouteNavigatorUris.GOOGLE_MAPS &&
            !isPackageInstalled(RouteNavigatorUris.GOOGLE_MAPS_PACKAGE)
        ) {
            return RouteNavigatorUris.YANDEX to "Google Maps не установлен, открыт Яндекс Навигатор"
        }

        return chosen to null
    }
}
