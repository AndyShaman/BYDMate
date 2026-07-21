package com.bydmate.app.navdata

/** Yandex guidance app package variants (donor YANDEX_PKGS split into two sets). */
object NavPackages {
    val YANDEX_NAVI = setOf(
        "ru.yandex.yandexnavi",
        "ru.yandex.yandexnavi.inhouse",
        "ru.yandex.yandexnavi.rustore",
    )

    val YANDEX_MAPS = setOf(
        "ru.yandex.yandexmaps",
        "ru.yandex.yandexmaps.beta",
        "ru.yandex.yandexmaps.inhouse",
        "ru.yandex.yandexmaps.rustore",
    )

    /** All packages treated as guidance sources (a11y feed, notification mirror, HUD). */
    val GUIDANCE_SOURCES = YANDEX_NAVI + YANDEX_MAPS
}
