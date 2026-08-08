package com.bydmate.app.hud

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub

/** Channel A: standard AutoNavi/Amap HUD broadcast. Broadcast format is donor-derived
 *  (OpenBYD 2.4.2 HudController.java:210-320 in the decompile); icon mapping is
 *  receiver-derived from the com.byd.amapservice canonical AutoNavi table — not OpenBYD's
 *  broadcast path, which is dead code on-car. Fires alongside the SOME/IP frame on the
 *  same NavGuidanceHub snapshot. Capability-gated: without a known receiver package
 *  installed nothing is broadcast and no further work happens after the one-time lazy
 *  package probe. */
class HudAmapBroadcaster(private val context: Context) {

    companion object {
        private const val TAG = "HudAmapBroadcaster"
        const val ACTION = "AUTONAVI_STANDARD_BROADCAST_SEND"
        val RECEIVER_PACKAGES = listOf("com.byd.amapservice", "com.example.amapservice")

        /** Maps our internal maneuver code to the canonical AutoNavi NEW_ICON value decoded
         *  by the receiver (com.byd.amapservice m[] array). The previous OpenBYD-derived
         *  table was wrong because OpenBYD's broadcast path is dead code on-car. Default
         *  9=straight; the old default 2 rendered as a left arrow on-car. */
        fun gaodeToAmapIcon(gaode: Int): Int = when (gaode) {
            1 -> 2         // LEFT
            2 -> 3         // RIGHT
            3 -> 4         // SLIGHT_LEFT
            4 -> 5         // SLIGHT_RIGHT
            7 -> 6         // SHARP_LEFT
            8 -> 7         // SHARP_RIGHT
            9 -> 8         // U_TURN_LEFT
            10 -> 19       // U_TURN_RIGHT
            11, 12 -> 9    // STRAIGHT (solid, dotted)
            13 -> 11       // ROUNDABOUT_ENTER
            24 -> 12       // ROUNDABOUT_EXIT
            // Codes 25..34 arrive while approaching/entering the roundabout, so the canonical
            // icon is 11 (ENTER); 12 on approach rendered as an animated left turn on-car
            // (issue #94). The exit number is still carried by the ROUNG_ABOUT_NUM extra.
            in 25..34 -> 11 // NUMBERED_ROUNDABOUT_ENTER (exit number = code-24, sent as ROUNG_ABOUT_NUM extra)
            45 -> 10       // WAYPOINT
            46 -> 13       // SERVICE_AREA
            47 -> 14       // TOLLBOOTH
            48 -> 15       // DESTINATION
            49 -> 16       // TUNNEL
            else -> 9      // neutral default: STRAIGHT
        }
    }

    @Volatile var framesSent: Long = 0L
        private set
    @Volatile var stopsSent: Long = 0L
        private set
    @Volatile private var guiding = false

    /** True when at least one known receiver package is installed on this device.
     *  Computed once on first access; false on any car without the Amap HUD service. */
    val capable: Boolean by lazy {
        RECEIVER_PACKAGES.any { pkg ->
            runCatching { context.packageManager.getPackageInfo(pkg, 0) }.isSuccess
        }
    }

    /** Called on every push-loop tick with the current guidance snapshot (null = ended).
     *  Sends a guide broadcast when s != null, a stop broadcast once when transitioning
     *  from guiding to null, and nothing on repeated null calls. */
    fun onSnapshot(s: NavGuidanceHub.Snapshot?) {
        if (!capable) return
        if (s == null) {
            if (guiding) sendStop()
            return
        }
        guiding = true
        try {
            sendAll(buildGuideIntent(s))
            framesSent++
        } catch (e: Exception) {
            Log.w(TAG, "amap guide broadcast failed", e)
        }
    }

    /** Called when the push loop stops while guidance was active (loop.stop()). */
    fun onStop() {
        if (capable && guiding) sendStop()
    }

    // Donor flag value 0x11000000 = FLAG_RECEIVER_FOREGROUND | 0x01000000 (hidden
    // framework FLAG_RECEIVER_INCLUDE_BACKGROUND, no public constant) — must stay a
    // raw literal to match OpenBYD's broadcast byte-for-byte.
    @SuppressLint("WrongConstant")
    private fun buildGuideIntent(s: NavGuidanceHub.Snapshot): Intent {
        val routeRemDis = if (s.totalDistMeters > 0) s.totalDistMeters else -1
        val routeRemTime = if (s.etaSeconds > 0) s.etaSeconds else -1
        val intent = Intent(ACTION)
        intent.putExtra("KEY_TYPE", 10001)
        intent.putExtra("TYPE", 8)
        intent.putExtra("EXTRA_STATE", 8)
        intent.putExtra("EXTRA_IS_FOREGROUND", 0)
        intent.putExtra("IS_BYD_MAP", true)
        intent.putExtra("IS_BYD_BAIDU_MAP", false)
        intent.putExtra("NEW_ICON", gaodeToAmapIcon(s.maneuverGaode))
        if (s.maneuverGaode in 25..34) {
            intent.putExtra("ROUNG_ABOUT_NUM", s.maneuverGaode - 24)
        }
        intent.putExtra("SEG_REMAIN_DIS", s.distanceMeters)
        intent.putExtra("NEXT_ROAD_NAME", s.road)
        intent.putExtra("ROUTE_REMAIN_DIS", routeRemDis)
        intent.putExtra("ROUTE_REMAIN_TIME", routeRemTime)
        // SEG_REMAIN_DIS_AUTO: donor :237-242
        val segAuto = if (s.distanceMeters >= 1000) {
            String.format("%.1f km", s.distanceMeters / 1000.0f)
        } else {
            "${s.distanceMeters} m"
        }
        intent.putExtra("SEG_REMAIN_DIS_AUTO", segAuto)
        // ROUTE_REMAIN_DIS_AUTO: donor :243-249 (only when route distance is known)
        if (routeRemDis != -1) {
            val routeAuto = if (routeRemDis >= 1000) {
                String.format("%.1f km", routeRemDis / 1000.0f)
            } else {
                "$routeRemDis m"
            }
            intent.putExtra("ROUTE_REMAIN_DIS_AUTO", routeAuto)
        }
        // ROUTE_REMAIN_TIME_AUTO + ROUTE_REMAIN_TIME_STRING: donor :251-262
        if (routeRemTime != -1) {
            val totalMin = routeRemTime / 60
            val hours = totalMin / 60
            val mins = totalMin % 60
            val timeStr = if (hours > 0) "${hours}h ${mins}m" else "${mins} min"
            intent.putExtra("ROUTE_REMAIN_TIME_AUTO", timeStr)
            intent.putExtra("ROUTE_REMAIN_TIME_STRING", timeStr)
        }
        intent.addFlags(285212672)  // 0x11000000: RECEIVER_FOREGROUND | hidden RECEIVER_INCLUDE_BACKGROUND
        return intent
    }

    @SuppressLint("WrongConstant")  // same donor flag literal as buildGuideIntent
    private fun sendStop() {
        guiding = false
        try {
            val intent = Intent(ACTION)
            intent.putExtra("KEY_TYPE", 10001)
            intent.putExtra("TYPE", 9)
            intent.putExtra("EXTRA_STATE", 1)
            intent.putExtra("EXTRA_IS_FOREGROUND", 1)
            intent.putExtra("IS_BYD_MAP", true)
            intent.putExtra("IS_BYD_BAIDU_MAP", false)
            intent.putExtra("NEW_ICON", -1)
            intent.putExtra("SEG_REMAIN_DIS", -1)
            intent.putExtra("NEXT_ROAD_NAME", "")
            intent.putExtra("ROUTE_REMAIN_DIS", -1)
            intent.putExtra("ROUTE_REMAIN_TIME", -1)
            intent.addFlags(285212672)
            sendAll(intent)
            stopsSent++
        } catch (e: Exception) {
            Log.w(TAG, "amap stop broadcast failed", e)
        }
    }

    /** Sends intent to each targeted package and once without a package (implicit). */
    private fun sendAll(intent: Intent) {
        RECEIVER_PACKAGES.forEach { pkg ->
            context.sendBroadcast(Intent(intent).setPackage(pkg))
        }
        context.sendBroadcast(intent)
    }
}
