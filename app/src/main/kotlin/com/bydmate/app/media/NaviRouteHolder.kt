package com.bydmate.app.media

/** Latest ongoing-notification content from Yandex Navigator, captured passively by
 *  MediaSessionListenerService. The voice agent reads it via the get_route_info tool.
 *  Content is whatever the Navigator renders (remaining distance, next maneuver);
 *  ETA and remaining distance are also available in bigTexts when the parser runs. */
object NaviRouteHolder {
    data class RouteSnapshot(
        val title: String?,
        val text: String?,
        val subText: String?,
        val postedAtMs: Long,
        val maneuver: String? = null,
        val maneuverDistance: String? = null,
        val street: String? = null,
        val bigTexts: List<String> = emptyList(),
        val maneuverIcon: String? = null,
    )

    const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

    @Volatile var latest: RouteSnapshot? = null
        private set

    fun update(
        pkg: String,
        title: String?,
        text: String?,
        subText: String?,
        nowMs: Long,
        parsed: NaviNotificationParser.Parsed? = null,
    ) {
        if (pkg !in com.bydmate.app.navdata.NavPackages.YANDEX_NAVI) return
        val hasParsed = parsed != null &&
            (parsed.maneuver != null || parsed.distance != null || parsed.bigTexts.isNotEmpty())
        if (title.isNullOrBlank() && text.isNullOrBlank() && subText.isNullOrBlank() && !hasParsed) return
        latest = RouteSnapshot(
            title, text, subText, nowMs,
            maneuver = parsed?.maneuver,
            maneuverDistance = parsed?.distance,
            street = parsed?.street,
            bigTexts = parsed?.bigTexts ?: emptyList(),
            maneuverIcon = parsed?.maneuverResource,
        )
    }

    fun clear(pkg: String) {
        if (pkg in com.bydmate.app.navdata.NavPackages.YANDEX_NAVI) latest = null
    }
}
