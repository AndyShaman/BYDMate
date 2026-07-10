package com.bydmate.app.media

/** Latest ongoing-notification content from Yandex Navigator, captured passively by
 *  MediaSessionListenerService. The voice agent reads it via the get_route_info tool.
 *  Content is whatever the Navigator renders (remaining distance, next maneuver);
 *  ETA is NOT present in the notification - the tool must stay honest about that. */
object NaviRouteHolder {
    data class RouteSnapshot(
        val title: String?,
        val text: String?,
        val subText: String?,
        val postedAtMs: Long,
    )

    const val NAVI_PACKAGE = "ru.yandex.yandexnavi"

    @Volatile var latest: RouteSnapshot? = null
        private set

    fun update(pkg: String, title: String?, text: String?, subText: String?, nowMs: Long) {
        if (pkg != NAVI_PACKAGE) return
        if (title.isNullOrBlank() && text.isNullOrBlank() && subText.isNullOrBlank()) return
        latest = RouteSnapshot(title, text, subText, nowMs)
    }

    fun clear(pkg: String) {
        if (pkg == NAVI_PACKAGE) latest = null
    }
}
