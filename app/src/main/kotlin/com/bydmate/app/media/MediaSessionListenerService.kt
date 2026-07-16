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
            if (sbn.packageName !in com.bydmate.app.navdata.NavPackages.YANDEX_NAVI) return
            val extras = sbn.notification.extras
            // RemoteViews reflection may break on any Navigator/Android update;
            // extras keep working as the raw fallback.
            val resolver = naviResourceResolver(sbn.packageName)
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
            // Same parse also feeds the unified guidance hub (numerics for HUD + agent).
            // No-op on the 2026 Navigator build whose notification is a static stub.
            parsed?.let {
                com.bydmate.app.navdata.NavGuidanceHub.updateFromNotification(
                    it.maneuverResource, it.distance, it.street, System.currentTimeMillis())
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching {
            NaviRouteHolder.clear(sbn.packageName)
            // Route ended on the legacy notification channel: deactivate the hub too,
            // unless the a11y feed still delivers fresh guidance (Codex audit fix 3).
            if (sbn.packageName in com.bydmate.app.navdata.NavPackages.YANDEX_NAVI) {
                com.bydmate.app.navdata.NavGuidanceHub.markNotificationEnded()
            }
        }
    }

    private val naviResources = HashMap<String, android.content.res.Resources>()

    // Resource names (view ids, maneuver drawables) belong to the NAVIGATOR's package,
    // so resolution needs its Resources; cached per package after the first lookup.
    private fun naviResourceResolver(pkg: String): (Int) -> String? {
        val res = naviResources[pkg] ?: runCatching {
            createPackageContext(pkg, 0).resources
        }.getOrNull()?.also { naviResources[pkg] = it }
        return { id -> runCatching { res?.getResourceEntryName(id) }.getOrNull() }
    }
}
