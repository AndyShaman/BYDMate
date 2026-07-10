package com.bydmate.app.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

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
            NaviRouteHolder.update(
                sbn.packageName,
                extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
                extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                System.currentTimeMillis(),
            )
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching { NaviRouteHolder.clear(sbn.packageName) }
    }
}
