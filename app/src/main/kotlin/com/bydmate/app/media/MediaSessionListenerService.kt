package com.bydmate.app.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavPackages
import java.util.concurrent.ConcurrentHashMap

/** Listener with two narrow jobs: (1) its mere enabled existence lets
 *  MediaSessionManager.getActiveSessions() accept our component (Wave G, music playback);
 *  (2) it mirrors the Yandex Navigator/Maps ongoing notification into NaviRouteHolder
 *  (raw text for the get_route_info voice tool) and into NavGuidanceHub through the rich
 *  RemoteViews parser (donor port: cameras, maneuver PNGs, minimized-window guidance).
 *  No other package's notifications are read or stored. Listener access is self-granted
 *  through the helper daemon (see MediaSessionGrant). */
class MediaSessionListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaSessionListener"

        /** Donor media filter: session token, transport category, or MediaStyle
         *  template. Alice music playing inside Navigator posts these - guidance
         *  parsing must not touch them (spec MEDIA outcome: full stop). */
        internal fun isMediaNotification(n: Notification): Boolean {
            val extras = n.extras
            return extras.containsKey(Notification.EXTRA_MEDIA_SESSION) ||
                n.category == Notification.CATEGORY_TRANSPORT ||
                extras.getString(Notification.EXTRA_TEMPLATE)?.contains("MediaStyle") == true
        }
    }

    internal var lane: NaviNotificationLane? = null

    override fun onCreate() {
        super.onCreate()
        lane = NaviNotificationLane()
    }

    override fun onDestroy() {
        lane?.shutdown()
        lane = null
        super.onDestroy()
    }

    // Both callbacks run on the framework binder thread: an uncaught exception there kills
    // the process AND unbinds this listener, breaking music-session access (Wave G role)
    // along with the navi mirror. Notification shape is not contractual, so parsing
    // failures must degrade to "no route info", never to a crash. The rich parse renders
    // views (waits up to 3 s) - it runs on the lane thread, never here.
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        runCatching {
            if (sbn.packageName !in NavPackages.GUIDANCE_SOURCES) return
            val notification = sbn.notification
            val pkg = sbn.packageName
            if (isMediaNotification(notification)) return
            // Enqueue-first (spec R3): the lane task enters the queue BEFORE the
            // legacy step so rich processing follows binder callback order.
            // Null lane (onDestroy teardown race): rich channel is gone, but the
            // legacy channel must keep feeding NaviRouteHolder.
            val l = lane
            if (l != null) {
                l.onPosted(
                    richTask = Runnable { processRichPost(notification, pkg) },
                    legacyStep = Runnable { legacyPost(notification, pkg) },
                )
            } else {
                legacyPost(notification, pkg)
            }
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        runCatching {
            if (sbn.packageName !in NavPackages.GUIDANCE_SOURCES) return
            // Donor removal semantics: never deactivate immediately - Navigator
            // flickers its notification on refresh. The debounced grace check runs
            // on the lane; the raw-text holder clears right away as before.
            // Null lane (onDestroy teardown race): still clear the holder.
            val l = lane
            if (l != null) {
                l.onRemoved(
                    legacyClear = Runnable { NaviRouteHolder.clear(sbn.packageName) },
                )
            } else {
                NaviRouteHolder.clear(sbn.packageName)
            }
        }
    }

    /** Legacy channel, synchronous on the binder thread (cheap: no view rendering). */
    private fun legacyPost(notification: Notification, pkg: String) {
        val extras = notification.extras
        // RemoteViews reflection may break on any Navigator/Android update;
        // extras keep working as the raw fallback.
        val resolver = naviResourceResolver(pkg)
        val parsed = runCatching {
            NaviNotificationParser.parse(notification, resolver)
        }.getOrNull()
        // Calibration path (spec: debug dump). When the mapped fields come back empty,
        // the Navigator layout likely changed - dump the raw actions so an on-car
        // logcat grab is enough to re-map without a special build.
        if (parsed == null || (parsed.maneuver == null && parsed.distance == null && parsed.bigTexts.isEmpty()) || (parsed.maneuver == null && parsed.maneuverResource != null)) {
            runCatching {
                Log.d("NaviNotifParser", NaviNotificationParser.dump(notification, resolver))
            }
        }
        NaviRouteHolder.update(
            pkg,
            extras.getCharSequence(Notification.EXTRA_TITLE)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
            System.currentTimeMillis(),
            parsed,
        )
    }

    /** Rich channel, lane thread. Discriminated outcomes (spec §5): RICH (navi signal
     *  -> hub), STUB (parsed but no signal -> skip), EXTRAS_FALLBACK (no RemoteViews). */
    private fun processRichPost(notification: Notification, pkg: String) {
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val resolver = naviResourceResolver(pkg)

        val rich = runCatching {
            NaviRichNotificationParser.parse(this, notification, pkg, resolver)
        }.getOrNull()

        if (rich != null) {
            if (!NaviRichNotificationParser.hasNaviSignal(rich)) {
                Log.d(TAG, "rich parse: stub notification, skipped")
                return
            }
            lane?.markGuidancePosted()
            NavGuidanceHub.updateFromNotification(
                NaviRichPostProcessor.buildRichUpdate(rich, title, text, subText))
            return
        }

        // EXTRAS_FALLBACK: RemoteViews absent or reflection broke - donor extras path.
        val snap = NavGuidanceHub.snapshot(System.currentTimeMillis())
        val update = NaviRichPostProcessor.buildExtrasFallback(
            title, text, subText,
            smallIconName = smallIconName(notification, resolver),
            isMaps = pkg in NavPackages.YANDEX_MAPS,
            hubHasKnownManeuver = snap.active && snap.maneuverGaode > 0,
        ) ?: return
        lane?.markGuidancePosted()
        NavGuidanceHub.updateFromNotification(update)
    }

    private fun smallIconName(n: Notification, resolveName: (Int) -> String?): String =
        runCatching { n.smallIcon?.resId?.let { resolveName(it) } }.getOrNull() ?: ""

    // Read from two threads (binder = legacy, lane = rich), hence concurrent.
    private val naviResources = ConcurrentHashMap<String, android.content.res.Resources>()

    // Resource names (view ids, maneuver drawables) belong to the NAVIGATOR's package,
    // so resolution needs its Resources; cached per package after the first lookup.
    private fun naviResourceResolver(pkg: String): (Int) -> String? {
        val res = naviResources[pkg] ?: runCatching {
            createPackageContext(pkg, 0).resources
        }.getOrNull()?.also { naviResources[pkg] = it }
        return { id -> runCatching { res?.getResourceEntryName(id) }.getOrNull() }
    }
}
