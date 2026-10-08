package com.bydmate.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.navdata.NavGuidanceHub
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** #301: cars WITHOUT the SOME/IP gateway. The stock Amap adapter (com.example.amapservice) writes
 *  the cluster's navigation card from a TYPE 0 broadcast; it drops every TYPE but 0 and 1, so the
 *  gateway path's TYPE 8 frames never reach it. Runs only from HudController's gateway-absent branch:
 *  cars with the gateway keep [HudAmapBroadcaster] untouched (the adapter is installed there too and
 *  would start writing the cluster). IS_BYD_MAP=false yields to the car's own map while it guides.
 *  A plain addressed broadcast, no helper-daemon work. */
class HudAmapClusterLoop(
    private val context: Context,
    private val prefs: SharedPreferences,
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "HudAmapClusterLoop"
        const val PACKAGE = "com.example.amapservice"
        const val ACTION_KILL = "byd.intent.action.KILL_BydAutoMap"
        /** A route's TYPE 0 frames went out and no KILL followed yet: a process death leaves it for the next start. */
        const val KEY_AMAP_CLUSTER_LEFT = "hud_amap_cluster_left"
        /** The adapter writes the status and the icon on every broadcast; notifications come about every 2 s. */
        const val PERIOD_MS = 1_000L

        fun isAdapterPresent(pm: PackageManager): Boolean =
            runCatching { pm.getPackageInfo(PACKAGE, 0) }.isSuccess

        /** The adapter parses only 天/时/分; "20 min" would leave the time unwritten. */
        internal fun remainTimeAuto(etaSeconds: Int): String {
            val totalMin = etaSeconds / 60
            val hours = totalMin / 60
            val mins = totalMin % 60
            return if (hours > 0) "${hours}时${mins}分" else "${mins}分"
        }

        // Same receiver flags as HudAmapBroadcaster (0x11000000: RECEIVER_FOREGROUND | hidden
        // RECEIVER_INCLUDE_BACKGROUND), so this channel differs from it only in TYPE, IS_BYD_MAP and addressing.
        private const val RECEIVER_FLAGS = 285212672

        @SuppressLint("WrongConstant")  // the hidden flag has no public constant
        internal fun buildGuideIntent(s: NavGuidanceHub.Snapshot): Intent {
            val routeRemDis = if (s.totalDistMeters > 0) s.totalDistMeters else -1
            val routeRemTime = if (s.etaSeconds > 0) s.etaSeconds else -1
            val intent = Intent(HudAmapBroadcaster.ACTION).setPackage(PACKAGE)
            intent.putExtra("KEY_TYPE", 10001)
            intent.putExtra("TYPE", 0)
            intent.putExtra("IS_BYD_MAP", false)
            intent.putExtra("IS_BYD_BAIDU_MAP", false)
            // 0 = no maneuver: an empty turn kind; gaodeToAmapIcon's default 9 would hold a straight arrow.
            intent.putExtra("NEW_ICON", if (s.maneuverGaode == 0) 0 else HudAmapBroadcaster.gaodeToAmapIcon(s.maneuverGaode))
            if (s.maneuverGaode in 25..34) {
                intent.putExtra("ROUNG_ABOUT_NUM", s.maneuverGaode - 24)
            }
            val segDis = if (s.distanceMeters in 1 until HudProtobufBuilder.MIN_DISTANCE_METERS)
                HudProtobufBuilder.MIN_DISTANCE_METERS else s.distanceMeters
            intent.putExtra("SEG_REMAIN_DIS", segDis)
            intent.putExtra("NEXT_ROAD_NAME", s.road)
            intent.putExtra("ROUTE_REMAIN_DIS", routeRemDis)
            intent.putExtra("ROUTE_REMAIN_TIME", routeRemTime)
            if (routeRemTime != -1) intent.putExtra("ROUTE_REMAIN_TIME_AUTO", remainTimeAuto(routeRemTime))
            intent.addFlags(RECEIVER_FLAGS)
            return intent
        }

        @SuppressLint("WrongConstant")  // same flag literal as buildGuideIntent
        private fun sendKill(context: Context, prefs: SharedPreferences) {
            context.sendBroadcast(Intent(ACTION_KILL).setPackage(PACKAGE).addFlags(RECEIVER_FLAGS))
            prefs.edit().remove(KEY_AMAP_CLUSTER_LEFT).apply()
        }

        /** A card a process death left up: one KILL. True when it went out; on a failure the key stays. */
        fun killLeftover(context: Context, prefs: SharedPreferences): Boolean {
            if (!prefs.contains(KEY_AMAP_CLUSTER_LEFT)) return false
            runCatching { sendKill(context, prefs) }.onFailure {
                Log.w(TAG, "leftover kill failed: ${it.javaClass.simpleName}")
                return false
            }
            Log.i(TAG, "leftover card killed")
            return true
        }
    }

    private var job: Job? = null
    /** At least one TYPE 0 went out since the last KILL that went out: while set, a KILL is owed. */
    @Volatile private var sentSinceKill = false
    private var routeFrames = 0L

    @Volatile var framesSent: Long = 0L; private set
    @Volatile var killsSent: Long = 0L; private set
    @Volatile var lastFrameTs: Long = 0L; private set

    fun start(scope: CoroutineScope) {
        if (job?.isActive == true) return
        // With a route guided the first frames take the card over and the route's end KILLs it.
        if (!NavGuidanceHub.snapshot(nowMsProvider()).active && killLeftover(context, prefs)) killsSent++
        job = scope.launch {
            while (isActive) {
                runCatching { tick() }
                delay(PERIOD_MS)
            }
        }
    }

    /** Switch off or service stop: the card goes only if our frames put it up. */
    fun stop() {
        job?.cancel()
        job = null
        if (sentSinceKill) kill()
    }

    /** No route: the KILL owed is sent, and retried on the next tick until it goes out. A route: one
     *  TYPE 0; the first one since the last KILL writes the leftover key, only once it went out. */
    internal fun tick() {
        val s = NavGuidanceHub.snapshot(nowMsProvider())
        if (!s.active) {
            if (sentSinceKill) kill()
            return
        }
        val routeStart = !sentSinceKill
        context.sendBroadcast(buildGuideIntent(s))
        if (routeStart) {
            prefs.edit().putLong(KEY_AMAP_CLUSTER_LEFT, nowMsProvider()).apply()
            routeFrames = 0
            Trace.event(TraceArea.HUD, "amap-cluster", "state" to "on")
        }
        sentSinceKill = true
        framesSent++
        routeFrames++
        lastFrameTs = System.currentTimeMillis()
        if (routeStart) {
            Log.i(TAG, "frame gaode=${s.maneuverGaode} dist=${s.distanceMeters}m sent=$framesSent" +
                " src=${s.maneuverSource.ifEmpty { "none" }} raw=\"${s.maneuverRaw}\"")
        }
    }

    private fun kill() {
        runCatching { sendKill(context, prefs) }.onFailure {
            Log.w(TAG, "kill failed: ${it.javaClass.simpleName}")
            return
        }
        sentSinceKill = false
        killsSent++
        Trace.event(TraceArea.HUD, "amap-cluster", "state" to "off", "sent" to routeFrames)
    }
}
