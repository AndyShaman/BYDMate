package com.bydmate.app.data.automation

import org.junit.Assert.assertEquals
import org.junit.Test

/** «Открой навигатор» tries the chosen app first and the Yandex Navigator last (#416). */
class RouteNavigatorUrisLaunchPackagesTest {

    @Test fun yandex_maps_choice_tries_the_maps_packages_then_the_navigator() {
        assertEquals(
            listOf(
                "ru.yandex.yandexmaps", "ru.yandex.yandexmaps.beta",
                "ru.yandex.yandexmaps.inhouse", "ru.yandex.yandexmaps.rustore",
                "ru.yandex.yandexnavi",
            ),
            RouteNavigatorUris.launchPackages(RouteNavigatorUris.MAPS),
        )
    }

    @Test fun dgis_and_waze_try_their_own_package_then_the_navigator() {
        assertEquals(
            listOf("ru.dublgis.dgismobile", "ru.yandex.yandexnavi"),
            RouteNavigatorUris.launchPackages(RouteNavigatorUris.DGIS),
        )
        assertEquals(
            listOf("com.waze", "ru.yandex.yandexnavi"),
            RouteNavigatorUris.launchPackages(RouteNavigatorUris.WAZE),
        )
    }

    @Test fun missing_or_unknown_choice_means_the_yandex_navigator_only() {
        val only = listOf("ru.yandex.yandexnavi")
        assertEquals(only, RouteNavigatorUris.launchPackages(null))
        assertEquals(only, RouteNavigatorUris.launchPackages(""))
        assertEquals(only, RouteNavigatorUris.launchPackages("2gis"))
        assertEquals(only, RouteNavigatorUris.launchPackages(RouteNavigatorUris.YANDEX))
    }
}
