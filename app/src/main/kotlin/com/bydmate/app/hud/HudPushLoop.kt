package com.bydmate.app.hud

import android.util.Log
import com.bydmate.app.navdata.NavGuidanceHub
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** 300 ms push loop: NavGuidanceHub snapshot -> protobuf frame -> SOME/IP fireEvent.
 *  When guidance ends (hub goes inactive) exactly one clear frame wipes the HUD.
 *  [speedSignEnabled] is read every tick, so the settings toggle applies within
 *  one period without restarting the loop. */
class HudPushLoop(
    private val sink: HudEventSink,
    private val speedSignEnabled: () -> Boolean = { true },
    private val nowMsProvider: () -> Long = { System.currentTimeMillis() },
) {
    companion object {
        private const val TAG = "HudPushLoop"
        const val PERIOD_MS = 300L
    }

    private var job: Job? = null
    private var counter = 0   // clear frames only; guidance frames carry the constant 2 in f2

    fun start(scope: CoroutineScope, periodMs: Long = PERIOD_MS) {
        if (job?.isActive == true) return
        job = scope.launch {
            var wasActive = false
            while (isActive) {
                wasActive = runCatching { tick(wasActive) }.getOrDefault(wasActive)
                delay(periodMs)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /** One tick; returns whether guidance was active (input for the next tick). */
    internal fun tick(wasActive: Boolean): Boolean {
        val s = NavGuidanceHub.snapshot(nowMsProvider())
        if (!s.active) {
            if (wasActive) {
                sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, HudProtobufBuilder.buildClearFrame(counter++))
                Log.i(TAG, "guidance ended, clear frame sent")
            }
            return false
        }
        val signPng = if (speedSignEnabled() && s.speedLimit > 0) HudSpeedSign.render(s.speedLimit) else null
        // Camera takeover (donor LoopRunner): while a camera alert is active the icon
        // slot (f8) shows the camera, f9 counts down to the camera, and the reference
        // arrow (f28) is suppressed so the HUD doesn't draw a stale maneuver arrow.
        val cameraActive = s.cameraAlert.isNotEmpty()
        val baseIcon = HudIconLoader.iconFor(s.maneuverGaode) ?: s.maneuverPng
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = s.maneuverGaode,
            distanceMeters = if (cameraActive && s.cameraDistanceMeters > 0) s.cameraDistanceMeters else s.distanceMeters,
            road = runningLine(s),
            etaString = etaString(s.etaSeconds),
            totalDistMeters = s.totalDistMeters,
            speedLimit = s.speedLimit,
            maneuverIconPng = if (cameraActive) s.cameraIconPng ?: baseIcon else baseIcon,
            speedSignPng = signPng,
            suppressArrow = cameraActive,
        )
        sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, frame)
        return true
    }

    /** Donor running line (f10): beyond 3 km to go, enrich the street with remaining
     *  time and wall-clock arrival: "<road> | ЧЧ:ММ мин | ЧЧ:ММ". */
    internal fun runningLine(s: NavGuidanceHub.Snapshot): String {
        if (s.totalDistMeters <= 3000 || s.etaSeconds <= 0 || s.road.isEmpty()) return s.road
        val etaTotalMin = s.etaSeconds / 60
        val remStr = String.format(Locale.US, "%02d:%02d", etaTotalMin / 60, etaTotalMin % 60)
        return "${s.road} | $remStr мин | ${etaString(s.etaSeconds)}"
    }

    /** Remaining seconds -> wall-clock arrival "HH:MM" (f26); null when unknown. */
    internal fun etaString(etaSeconds: Int): String? {
        if (etaSeconds <= 0) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMsProvider() + etaSeconds * 1000L }
        return String.format(Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
