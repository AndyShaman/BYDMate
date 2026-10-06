package com.bydmate.app.media

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.navdata.NavGuidanceHub
import com.bydmate.app.navdata.NavPackages
import com.bydmate.app.navdata.UnknownManeuverGate
import java.util.concurrent.ConcurrentHashMap

/** Listener with two narrow jobs: (1) its mere enabled existence lets
 *  MediaSessionManager.getActiveSessions() accept our component (Wave G, music playback);
 *  (2) it mirrors the Yandex Navigator/Maps ongoing notification into NaviRouteHolder
 *  (raw text for the get_route_info voice tool) and into NavGuidanceHub through the rich
 *  RemoteViews parser (donor port: cameras, maneuver PNGs, minimized-window guidance).
 *  No other package's notifications are read or stored. Listener access is self-granted
 *  through the helper daemon (see MediaSessionGrant). */
@Suppress("TooManyFunctions") // framework callbacks plus the pure parsing helpers they share
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

    /** Where the unknown-maneuver lines go; logcat in production, a collector in tests. */
    internal var unknownManeuverSink: (String) -> Unit = { Log.i(TAG, it) }
    // Maneuver icon names this lane could not map, a line per name per 5 min (lane thread).
    private val unknownManeuvers = UnknownManeuverGate(UnknownManeuverGate.MIN_INTERVAL_MS)

    /** Where the notification trace goes (issue #199); logcat in production, a collector in tests. */
    internal var naviNotifSink: (String) -> Unit = { Log.i(TAG, it) }
    private val naviNotifGate = NaviNotifTraceGate()

    override fun onCreate() {
        super.onCreate()
        lane = NaviNotificationLane()
    }

    // Bound and live, or let go by the framework: without these a HUD with no notification
    // guidance (#199) cannot tell a disconnected listener from a quiet navigator.
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "listener connected")
        Trace.event(TraceArea.HUD, "notif-listener", "state" to "connected")
    }

    override fun onListenerDisconnected() {
        Log.i(TAG, "listener disconnected")
        Trace.event(TraceArea.HUD, "notif-listener", "state" to "disconnected")
        super.onListenerDisconnected()
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
            val id = sbn.id
            if (isMediaNotification(notification)) return
            // Enqueue-first (spec R3): the lane task enters the queue BEFORE the
            // legacy step so rich processing follows binder callback order.
            // Null lane (onDestroy teardown race): rich channel is gone, but the
            // legacy channel must keep feeding NaviRouteHolder.
            val l = lane
            if (l != null) {
                l.onPosted(
                    richTask = Runnable { processRichPost(notification, pkg, id) },
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
            // Field trace (#199), gated here so a suppressed removal enqueues nothing; built in
            // its own runCatching so a notification it cannot read only loses the line.
            val trace = if (l == null) null else runCatching {
                if (isMediaNotification(sbn.notification) ||
                    !naviNotifGate.takeRemoval(System.currentTimeMillis())
                ) return@runCatching null
                val line = NaviNotifTraceGate.removedLine(sbn.packageName, sbn.id)
                Runnable {
                    naviNotifGate.removalWritten()
                    naviNotifSink(line)
                }
            }.getOrNull()
            if (l != null) {
                l.onRemoved(
                    legacyClear = Runnable { NaviRouteHolder.clear(sbn.packageName) },
                    trace = trace,
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
    private fun processRichPost(notification: Notification, pkg: String, id: Int) {
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
                tracePost(notification, pkg, id, "stub", null)
                return
            }
            lane?.markGuidancePosted()
            val update = NaviRichPostProcessor.buildRichUpdate(rich, title, text, subText)
            NavGuidanceHub.updateFromNotification(update)
            tracePost(notification, pkg, id, "rich", update)
            logUnknownManeuver(update, "notification", "res=${UnknownManeuverGate.quote(rich.maneuverRes)}")
            return
        }

        // EXTRAS_FALLBACK: RemoteViews absent or reflection broke - donor extras path.
        val snap = NavGuidanceHub.snapshot(System.currentTimeMillis())
        val iconName = smallIconName(notification, resolver)
        val isMaps = pkg in NavPackages.YANDEX_MAPS
        val hubHasKnownManeuver = snap.active && snap.maneuverGaode > 0
        val update = NaviRichPostProcessor.buildExtrasFallback(
            title, text, subText,
            smallIconName = iconName,
            isMaps = isMaps,
            hubHasKnownManeuver = hubHasKnownManeuver,
        ) ?: run {
            tracePost(notification, pkg, id, "empty", null)
            return
        }
        lane?.markGuidancePosted()
        NavGuidanceHub.updateFromNotification(update)
        tracePost(notification, pkg, id, "extras", update)
        // Maps drops its own maneuver while the hub knows one (rule R2-1): that 0 is not unknown.
        if (!(isMaps && hubHasKnownManeuver)) {
            logUnknownManeuver(update, "notification extras", "icon=${UnknownManeuverGate.quote(iconName)}")
        }
    }

    /** Field diagnostics: a guided post with a distance whose maneuver maps to 0 logs the
     *  maneuver icon name once per distinct name per 5 min. The texts are never logged, they carry
     *  streets. The route state is read only after the cheap checks. */
    private fun logUnknownManeuver(update: NavGuidanceHub.RichUpdate, path: String, value: String) {
        val nowMs = System.currentTimeMillis()
        if (!UnknownManeuverGate.applies(update.distanceMeters, update.maneuverGaode) { NavGuidanceHub.snapshot(nowMs).active }) return
        val line = "nav maneuver unknown [$path]: $value"
        if (unknownManeuvers.take(line, nowMs)) unknownManeuverSink(line)
    }

    /** Field diagnostics (issue #199): what the navigator posted and which path took it, numbers
     *  and ids only. [update] is what reached the hub, null for a post it did not take. */
    private fun tracePost(n: Notification, pkg: String, id: Int, kind: String, update: NavGuidanceHub.RichUpdate?) {
        val ongoing = (n.flags and Notification.FLAG_ONGOING_EVENT) != 0
        val man = update?.maneuverGaode ?: 0
        val skippedChanges = naviNotifGate.takePost(
            NaviNotifTraceGate.postKey(id, ongoing, kind, man), System.currentTimeMillis()) ?: return
        naviNotifSink(NaviNotifTraceGate.postLine(pkg, id, ongoing, n.channelId, kind, man,
            update?.distanceMeters ?: 0, update?.road?.length ?: 0, skippedChanges))
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
