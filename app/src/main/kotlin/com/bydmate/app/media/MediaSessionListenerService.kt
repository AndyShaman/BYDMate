package com.bydmate.app.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/** Listener with two narrow jobs: (1) its mere enabled existence lets
 *  MediaSessionManager.getActiveSessions() accept our component (Wave G, music playback);
 *  (2) it passively mirrors the Yandex Navigator ongoing notification into NaviRouteHolder
 *  for the get_route_info voice tool. No other package's notifications are read or stored.
 *  Listener access is self-granted through the helper daemon (see MediaSessionGrant). */
class MediaSessionListenerService : NotificationListenerService() {

    // Both callbacks run on the framework binder thread: an uncaught exception there kills
    // the process AND unbinds this listener, breaking music-session access (Wave G role)
    // along with the navi mirror. Navigator's notification shape is not contractual, so
    // parsing failures must degrade to "no route info", never to a crash.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            if (sbn.packageName != NaviRouteHolder.NAVI_PACKAGE) return
            val extras = sbn.notification.extras
            // RemoteViews reflection may break on any Navigator/Android update;
            // extras keep working as the raw fallback.
            val resolver = naviResourceResolver()
            val parsed = runCatching {
                NaviNotificationParser.parse(sbn.notification, resolver)
            }.getOrNull()
            // Calibration path (spec: debug dump). When the mapped fields come back empty,
            // the Navigator layout likely changed - dump the raw actions so an on-car
            // logcat grab is enough to re-map without a special build.
            if (parsed == null || (parsed.maneuver == null && parsed.distance == null && parsed.bigTexts.isEmpty()) || (parsed.maneuver == null && parsed.maneuverResource != null)) {
                runCatching {
                    Log.d("NaviNotifParser", NaviNotificationParser.dump(sbn.notification, resolver))
                }
            }
            NaviRouteHolder.update(
                sbn.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                System.currentTimeMillis(),
                parsed,
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { NaviRouteHolder.clear(sbn.packageName) }
    }

    private var naviResources: android.content.res.Resources? = null

    // Resource names (view ids, maneuver drawables) belong to the NAVIGATOR's package,
    // so resolution needs its Resources; cached after the first successful lookup.
    private fun naviResourceResolver(): (Int) -> String? {
        val res = naviResources ?: runCatching {
            createPackageContext(NaviRouteHolder.NAVI_PACKAGE, 0).resources
        }.getOrNull()?.also { naviResources = it }
        return { id -> runCatching { res?.getResourceEntryName(id) }.getOrNull() }
    }
}
