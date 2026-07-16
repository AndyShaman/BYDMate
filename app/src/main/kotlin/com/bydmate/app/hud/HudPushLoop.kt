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
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = s.maneuverGaode,
            distanceMeters = s.distanceMeters,
            road = s.road,
            etaString = etaString(s.etaSeconds),
            totalDistMeters = s.totalDistMeters,
            speedLimit = s.speedLimit,
            maneuverIconPng = HudIconLoader.iconFor(s.maneuverGaode),
            speedSignPng = signPng,
        )
        sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, frame)
        return true
    }

    /** Remaining seconds -> wall-clock arrival "HH:MM" (f26); null when unknown. */
    internal fun etaString(etaSeconds: Int): String? {
        if (etaSeconds <= 0) return null
        val cal = Calendar.getInstance().apply { timeInMillis = nowMsProvider() + etaSeconds * 1000L }
        return String.format(Locale.US, "%02d:%02d",
            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
    }
}
