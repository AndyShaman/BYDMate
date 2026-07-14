package com.bydmate.app.data.camera

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects when the BYD built-in camera/parking surface (`com.byd.avc`) or a
 * YouTube client takes the foreground. Reads the most recent MOVE_TO_FOREGROUND
 * event from UsageStatsManager every [POLL_INTERVAL_MS]. Requires the
 * GET_USAGE_STATS appop, which TrackingService grants at startup via the
 * on-device ADB. YouTube detection feeds [youtubeForeground]; hiding is opt-in
 * via WidgetPreferences.KEY_HIDE_ON_YOUTUBE.
 */
@Singleton
class CameraStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    private val _youtubeForeground = MutableStateFlow(false)
    val youtubeForeground: StateFlow<Boolean> = _youtubeForeground.asStateFlow()

    private var scope: CoroutineScope? = null
    private var job: Job? = null

    // Tail-the-event-stream state: remember the most recent ACTIVITY_RESUMED
    // we have already consumed so each poll queries only the delta since then.
    // Survives polling ticks; reset by start(). On the very first poll
    // (lastEventTs == 0) we use a wider initial window so we catch a foreground
    // event that fired before TrackingService started — e.g. camera engaged
    // before BYDMate boot finishes.
    @Volatile private var lastForegroundPkg: String? = null
    @Volatile private var lastEventTs: Long = 0L

    fun start() {
        if (job?.isActive == true) return
        lastForegroundPkg = null
        lastEventTs = 0L
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        job = s.launch {
            while (true) {
                refreshForegroundPackage()
                // queryEvents is blocking; check we weren't cancelled mid-IO
                // before publishing. Stops a stale value from clobbering the
                // false set in stop().
                ensureActive()
                _active.value = lastForegroundPkg == CAMERA_PACKAGE
                _youtubeForeground.value = isYoutubePackage(lastForegroundPkg)
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        scope?.cancel()
        scope = null
        _active.value = false
        _youtubeForeground.value = false
        lastForegroundPkg = null
        lastEventTs = 0L
    }

    private fun refreshForegroundPackage() {
        val now = System.currentTimeMillis()
        val beginTs = if (lastEventTs == 0L) now - INITIAL_LOOKBACK_MS else lastEventTs + 1
        try {
            val latest = latestResumed(usm, beginTs, now + 1)
            if (latest != null) {
                lastForegroundPkg = latest.first
                if (latest.second > lastEventTs) lastEventTs = latest.second
            }
            // No new events => keep prior foreground (camera still on, etc.).
            // Forward through start() ensures the very first call has lastEventTs=0,
            // forcing the wider window above.
            if (lastEventTs == 0L) lastEventTs = now
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "queryEvents failed: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "CameraMonitor"
        private const val CAMERA_PACKAGE = "com.byd.avc"
        private const val POLL_INTERVAL_MS = 2_000L
        // First-poll window — wide enough to catch the camera ACTIVITY_RESUMED
        // that fired before our service even started (boot + immediate-reverse
        // scenario). Subsequent polls only fetch the delta since lastEventTs.
        private const val INITIAL_LOOKBACK_MS = 5L * 60_000L

        // Known YouTube clients for the "hide widget over YouTube" toggle. anddea.youtube is
        // the ReVanced build already preferred by the youtube automation action; extend the
        // list if the on-car client is missing from it.
        private val YOUTUBE_PACKAGES = setOf(
            "anddea.youtube",
            "com.google.android.youtube",
            "com.google.android.youtube.tv",
            "app.revanced.android.youtube",
            "app.rvx.android.youtube",
        )

        fun isYoutubePackage(pkg: String?): Boolean = pkg != null && pkg in YOUTUBE_PACKAGES

        /** Latest ACTIVITY_RESUMED (package, timestamp) in [beginTs, endTs], or null when there
         *  are no such events. Shared by the continuous poller and one-shot foreground checks
         *  (agent navigate verification). Caller handles exceptions. */
        fun latestResumed(usm: UsageStatsManager, beginTs: Long, endTs: Long): Pair<String, Long>? {
            val events = usm.queryEvents(beginTs, endTs)
            val ev = UsageEvents.Event()
            // Pick latest by timestamp, not by iteration order — Android's queryEvents is
            // documented to return chronological order, but relying on it would silently
            // break under future framework changes or vendor patches.
            var latestTs = -1L
            var latestPkg: String? = null
            while (events.hasNextEvent()) {
                events.getNextEvent(ev)
                if (ev.eventType == UsageEvents.Event.ACTIVITY_RESUMED && ev.timeStamp > latestTs) {
                    latestTs = ev.timeStamp
                    latestPkg = ev.packageName
                }
            }
            return latestPkg?.let { it to latestTs }
        }
    }
}
