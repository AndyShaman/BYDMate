package com.bydmate.app.data.automation

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.automation.AutomationEngine.Companion.KEY_SERVICE_START_BOOT_ID
import com.bydmate.app.data.automation.AutomationEngine.Companion.KEY_SERVICE_START_CAR_OFF
import com.bydmate.app.data.automation.AutomationEngine.Companion.KEY_SERVICE_START_LAST_SEEN_ELAPSED
import com.bydmate.app.data.automation.AutomationEngine.Companion.SERVICE_START_WINDOW_MS
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The service_start window of [AutomationEngine] (#177). A new session arms it. The session
 * follows the head unit screen, not kernel suspend: between drives the screen is dark while
 * Android may stay awake. Decided on a screen-on tick only: the car-off marker (ACC_OFF), a
 * different kernel boot id (reboot), no stored heartbeat, elapsed going backwards (reboot), or
 * more than [SERVICE_START_WAKE_GAP_MS] since the last screen-on tick (car was off: screen dark and/or
 * process dead). Checked on every tick, so a process that lives through a stop re-arms when the
 * screen comes back. Ticks with the screen off write no heartbeat; a process started with the
 * screen off (restart after ACC_OFF) keeps the window closed and decides on its first screen-on
 * tick. A process restarted mid-drive (fresh heartbeat) marks the window as already spent (-1),
 * so service_start rules don't fire again. Wall-clock time takes no part in the decision nor in
 * the window deadline, so clock corrections can't reopen, stretch or cut the window.
 * Limitation: without a readable boot id, a reboot whose new elapsed already exceeds the saved
 * one and lies within the wake gap is not recognised.
 *
 * The firmware force-stops the process 1.7-2.3 s after ACC_OFF, so in practice the marker is
 * judged by the restarted process; the wait for a dark tick only covers those seconds before the
 * force-stop in a live process.
 *
 * [tick] runs under [AutomationEngine.evaluateMutex]. [onCarOff] never waits for it: it commits
 * the marker at once under [carOffLock]. A tick judges the marker from one snapshot taken under
 * that lock, and clears it or opens a session only if no ACC_OFF arrived since (the generation
 * check in [commitClearingCarOff]).
 *
 * The heartbeat of a lit screen is kept by its own timer ([startHeartbeat]) as well as by the
 * ticks: a tick loop stalled for over a minute with the screen on (a long action holding the
 * mutex, a slow read) must not look like a wake gap. The timer only carries a heartbeat the ticks
 * have already decided on; a gap it did not see closing (screen dark, suspend) is left to a tick.
 * A beat that sees the screen dark confirms the dark screen for the car-off marker, as a dark
 * tick does. A tick and a beat run under [heartbeatLock] (lock order: evaluateMutex, then
 * [heartbeatLock], then [carOffLock]). Beat and tick times are kept apart: the tick's elapsed is
 * read before the lock, so a beat may be a few ms ahead of it; the gap counts from the later of
 * the two, elapsed going backwards is judged tick to tick only, and the stored heartbeat never
 * moves back.
 */
@Suppress("TooManyFunctions") // one state machine: the heartbeat timer shares its private state
internal class ServiceStartSession(private val prefs: () -> SharedPreferences) {

    // Deadline on elapsed: 0 = never armed, -1 = closed for this session.
    @Volatile private var windowEnd = 0L
    // Rules that already fired in this session's window.
    val consumed: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    // Boot id and elapsed of the last screen-on tick, seeded from the persisted heartbeat on the
    // first tick (may stay null: nothing stored). sessionDecided flips on the first screen-on
    // tick, which alone may keep the window closed for a process restarted mid-drive.
    @Volatile private var seeded = false
    @Volatile private var sessionDecided = false
    @Volatile private var lastInteractive = false
    @Volatile private var sessionBootId: String? = null
    @Volatile private var savedBootId: String? = null
    @Volatile private var lastTickElapsed: Long? = null
    // Elapsed of the last heartbeat timer beat with the screen on.
    @Volatile private var lastBeatElapsed: Long? = null
    @Volatile private var lastHeartbeatWriteElapsed: Long? = null
    // Elapsed of the ACC_OFF this process marked while it has not seen the screen off since:
    // the car is going off, so nothing may fire or open a session until a dark tick. Null with
    // the marker set = dark confirmed, or a marker left by a dead process (its restart comes
    // with the screen off).
    @Volatile private var carOffLitSince: Long? = null
    // Elapsed time the last session opened. An eligible car-off marker within SERVICE_START_WAKE_GAP_MS of it
    // is dropped: the car was switched off and on again within a minute of a start. The session
    // is not reopened for that second start: accepted.
    @Volatile private var lastSessionOpenedElapsed: Long? = null
    // Guards the marker against a concurrent tick: every ACC_OFF bumps the generation, and a tick
    // that read the marker at an older generation must not clear it. The lit state changes under
    // it as well; carOffPersisted is false after a marker commit that failed on disk.
    private val carOffLock = Any()
    private var carOffGeneration = 0L
    private var carOffPersisted = true
    @Volatile private var heartbeatJob: Job? = null
    // Serialises a tick with a timer beat. The generation changes on every start and stop, so a
    // beat already running when its timer was stopped writes nothing.
    private val heartbeatLock = Any()
    private var heartbeatGeneration = 0L

    /** The car-off state a tick judges, read at once under [carOffLock]. */
    private data class CarOff(val generation: Long, val marked: Boolean, val litSince: Long?)

    fun tick(elapsed: Long, interactive: Boolean, bootId: () -> String) = synchronized(heartbeatLock) {
        val prefs = prefs()
        val firstTick = !seeded
        if (firstTick) {
            seeded = true
            sessionBootId = bootId()
            savedBootId = prefs.getString(KEY_SERVICE_START_BOOT_ID, null)
            lastTickElapsed = prefs.getLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, -1L).takeIf { it >= 0L }
        }
        if (interactive) {
            litTick(prefs, elapsed)
        } else {
            carOffLitSince = null
            if (firstTick) {
                Log.i(TAG, "service_start: screen off at start, waiting for wake-up")
                windowEnd = -1L
            } else {
                if (lastInteractive) Log.i(TAG, "service_start: screen off, heartbeat paused")
                // The car went off before a service_start rule matched: it must not fire later
                // with the screen dark.
                if (elapsed <= windowEnd) {
                    Log.i(TAG, "service_start: screen off, window closed")
                    windowEnd = -1L
                }
            }
        }
        lastInteractive = interactive
    }

    private fun litTick(prefs: SharedPreferences, elapsed: Long) {
        val carOff = synchronized(carOffLock) {
            CarOff(carOffGeneration, prefs.getBoolean(KEY_SERVICE_START_CAR_OFF, false), carOffLitSince)
        }
        if (waitsForDark(prefs, elapsed, carOff)) {
            keepAlive(prefs, elapsed)
            return
        }
        // A marker still lit here has just expired and been cleared.
        val eligible = carOff.marked && carOff.litSince == null
        val prevElapsed = lastTickElapsed
        val lastSeen = prevElapsed?.let { maxOf(it, lastBeatElapsed ?: it) }
        val gap = lastSeen?.let { "${(elapsed - it).coerceAtLeast(0L) / 1000}s" } ?: "-"
        val sinceOpened = lastSessionOpenedElapsed?.let { elapsed - it } ?: Long.MAX_VALUE
        val carOffInSession = eligible && sinceOpened <= SERVICE_START_WAKE_GAP_MS
        if (carOffInSession) {
            Log.i(TAG, "service_start: car off marker within session, ignored gap=${sinceOpened / 1000}s")
            commitClearingCarOff(prefs.edit(), carOff.generation, "service_start: car off marker commit failed")
        }
        val reason = newSessionReason(elapsed, prevElapsed, lastSeen, eligible && !carOffInSession)
        if (reason != null) {
            openSession(prefs, elapsed, carOff.generation, "service_start: new session reason=$reason gap=$gap")
        } else if (!sessionDecided) {
            Log.i(TAG, "service_start: process restarted mid-session, not re-arming gap=$gap")
            windowEnd = -1L
        }
        keepAlive(prefs, elapsed)
    }

    /**
     * ACC_OFF with the screen still lit: an open window must not let a rule fire at car off, and
     * no other reason may open a session over the marker, so the tick waits for a dark one.
     */
    private fun waitsForDark(prefs: SharedPreferences, elapsed: Long, carOff: CarOff): Boolean {
        val litSince = carOff.litSince ?: return false
        if (!carOff.marked) return false
        if (elapsed - litSince <= SERVICE_START_CAR_OFF_LIT_MAX_MS) {
            if (elapsed <= windowEnd) {
                Log.i(TAG, "service_start: ACC_OFF, window closed")
                windowEnd = -1L
            }
            return true
        }
        // The screen never went dark (the car stayed on, or a late broadcast): left in place, the
        // marker would fire at the next screen blink mid-drive.
        Log.i(TAG, "service_start: car off marker expired, screen stayed on")
        return !commitClearingCarOff(prefs.edit(), carOff.generation, "service_start: car off marker commit failed")
    }

    /**
     * Commits [edit] together with a cleared car-off marker and lit state, unless an ACC_OFF
     * arrived since the tick read the marker at [generation]: then the marker and its lit state
     * stay for the next tick to judge. Returns whether the marker was cleared.
     */
    private fun commitClearingCarOff(edit: SharedPreferences.Editor, generation: Long, failure: String): Boolean =
        synchronized(carOffLock) {
            val current = generation == carOffGeneration
            if (current) {
                edit.putBoolean(KEY_SERVICE_START_CAR_OFF, false)
                carOffLitSince = null
            } else {
                Log.i(TAG, "service_start: ACC_OFF arrived during the tick, marker kept")
            }
            if (!edit.commit()) Log.w(TAG, failure)
            current
        }

    private fun newSessionReason(elapsed: Long, prevElapsed: Long?, lastSeen: Long?, carOff: Boolean): String? {
        val bootId = sessionBootId.orEmpty()
        val saved = savedBootId.orEmpty()
        return when {
            carOff -> "car_off"
            bootId.isNotEmpty() && saved.isNotEmpty() && bootId != saved -> "boot"
            prevElapsed == null || lastSeen == null -> "first"
            elapsed < prevElapsed -> "elapsed_back"
            elapsed - lastSeen > SERVICE_START_WAKE_GAP_MS -> "wake"
            else -> null
        }
    }

    private fun openSession(prefs: SharedPreferences, elapsed: Long, generation: Long, line: String) {
        Log.i(TAG, line)
        val bootId = sessionBootId.orEmpty()
        // A beat may have stored a later heartbeat already: the stored one never moves back.
        val heartbeat = maxOf(elapsed, lastHeartbeatWriteElapsed ?: elapsed)
        // One synchronous write before any rule runs: a process killed right after the fire must
        // find this session's heartbeat and no car-off marker, or its restart would re-arm.
        val session = prefs.edit()
            .putString(KEY_SERVICE_START_BOOT_ID, bootId)
            .putLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, heartbeat)
        // The window opens under the lock too, so an ACC_OFF right after the commit closes it.
        val cleared = synchronized(carOffLock) {
            commitClearingCarOff(session, generation, "service_start: session commit failed")
                .also { if (it) windowEnd = elapsed + SERVICE_START_WINDOW_MS }
        }
        lastHeartbeatWriteElapsed = heartbeat
        savedBootId = bootId
        if (!cleared) {
            // The car is going off: its marker opens the session at the next start instead.
            Log.i(TAG, "service_start: ACC_OFF arrived during the tick, session not opened")
            windowEnd = -1L
            return
        }
        consumed.clear()
        lastSessionOpenedElapsed = elapsed
    }

    private fun keepAlive(prefs: SharedPreferences, elapsed: Long) {
        sessionDecided = true
        lastTickElapsed = elapsed
        val lastWrite = lastHeartbeatWriteElapsed
        if (lastWrite == null || elapsed - lastWrite >= SERVICE_START_HEARTBEAT_MS) {
            lastHeartbeatWriteElapsed = elapsed
            prefs.edit().putLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, elapsed).apply()
        }
    }

    /**
     * Starts the heartbeat timer in [scope], replacing a running one: every
     * [SERVICE_START_HEARTBEAT_MS] a lit screen refreshes the heartbeat without waiting for a tick.
     */
    fun startHeartbeat(scope: CoroutineScope, elapsed: () -> Long, interactive: () -> Boolean) {
        val generation = synchronized(heartbeatLock) { ++heartbeatGeneration }
        heartbeatJob?.cancel()
        Log.i(TAG, "service_start: heartbeat timer started period=${SERVICE_START_HEARTBEAT_MS / 1000}s")
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(SERVICE_START_HEARTBEAT_MS)
                beat(generation, elapsed(), interactive())
            }
        }
    }

    fun stopHeartbeat() {
        synchronized(heartbeatLock) { heartbeatGeneration++ }
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    private fun beat(generation: Long, elapsed: Long, interactive: Boolean) = synchronized(heartbeatLock) {
        if (generation != heartbeatGeneration) return@synchronized
        val lastTick = lastTickElapsed
        // A lit tick newer than this reading has already judged the screen.
        if (lastTick != null && elapsed < lastTick) return@synchronized
        if (!interactive) {
            darkBeat(elapsed)
            return@synchronized
        }
        // Before the first lit tick the stored heartbeat is still the evidence of a gap.
        if (!sessionDecided || lastTick == null) return@synchronized
        val prev = maxOf(lastTick, lastBeatElapsed ?: lastTick)
        // More than the wake gap since the last lit tick or beat: the screen was dark or the
        // system suspended, and the next tick decides on that gap.
        if (elapsed < prev || elapsed - prev > SERVICE_START_WAKE_GAP_MS) return@synchronized
        lastBeatElapsed = elapsed
        if (elapsed > (lastHeartbeatWriteElapsed ?: Long.MIN_VALUE)) {
            lastHeartbeatWriteElapsed = elapsed
            prefs().edit().putLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, elapsed).apply()
        }
    }

    /**
     * The screen is dark on a beat while no tick may run: the car-off marker gets its dark
     * screen and an open window closes, as on a dark tick. Opens no session.
     */
    private fun darkBeat(elapsed: Long) {
        synchronized(carOffLock) {
            // An ACC_OFF after this reading is not confirmed by it.
            val litSince = carOffLitSince
            if (litSince != null && litSince <= elapsed) {
                Log.i(TAG, "service_start: screen off seen by heartbeat timer")
                carOffLitSince = null
            }
        }
        if (elapsed <= windowEnd) {
            Log.i(TAG, "service_start: screen off, window closed")
            windowEnd = -1L
        }
    }

    /** Whether a service_start trigger of [ruleId] is true on this tick. */
    fun isActive(ruleId: Long, interactive: Boolean, elapsed: Long): Boolean =
        interactive && elapsed <= windowEnd && ruleId !in consumed

    /**
     * The car was switched off (byd.intent.action.ACC_OFF). Persisted at once: the process is
     * force-stopped shortly after, and the next car start must open a new session. A repeated
     * broadcast keeps the marker and whether the screen was already seen off, and retries a
     * commit that failed on disk. An open window closes at once: the evaluate() in progress may
     * still be walking the rules.
     */
    fun onCarOff(elapsed: Long) = synchronized(carOffLock) {
        // A repeat counts too: a tick that read the marker before it must not clear it.
        carOffGeneration++
        if (elapsed <= windowEnd) {
            Log.i(TAG, "service_start: ACC_OFF, window closed")
            windowEnd = -1L
        }
        val prefs = prefs()
        if (prefs.getBoolean(KEY_SERVICE_START_CAR_OFF, false)) {
            if (carOffPersisted) {
                Log.i(TAG, "service_start: ACC_OFF repeated, marker kept")
                return@synchronized
            }
            Log.i(TAG, "service_start: ACC_OFF repeated, marker retried")
        } else {
            Log.i(TAG, "service_start: ACC_OFF, car off marked")
            carOffLitSince = elapsed
        }
        // A failed commit still leaves the marker in memory: SharedPreferences apply it there first.
        carOffPersisted = prefs.edit().putBoolean(KEY_SERVICE_START_CAR_OFF, true).commit()
        if (!carOffPersisted) Log.w(TAG, "service_start: car off marker commit failed")
    }

    // One line for the diagnostics dump: the session as this process sees it. The heartbeat age
    // is read from prefs, as a restarted process would see it.
    fun dumpLine(interactive: Boolean, elapsed: Long, now: Long): String {
        val prefs = prefs()
        val end = windowEnd
        val window = when {
            end <= 0L -> "not armed"
            elapsed <= end -> "armed until " + SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now + end - elapsed))
            else -> "spent"
        }
        val heartbeat = prefs.getLong(KEY_SERVICE_START_LAST_SEEN_ELAPSED, -1L)
        val age = if (heartbeat >= 0L) "${(elapsed - heartbeat) / 1000}s" else "-"
        val bootId = prefs.getString(KEY_SERVICE_START_BOOT_ID, null)?.takeIf { it.isNotEmpty() } ?: "-"
        val carOff = if (prefs.getBoolean(KEY_SERVICE_START_CAR_OFF, false)) "pending" else "-"
        val lit = carOffLitSince?.let { "${(elapsed - it) / 1000}s" } ?: "-"
        val timer = if (heartbeatJob?.isActive == true) "timer" else "none"
        return "service_start: interactive=$interactive window=$window consumed=${consumed.size} " +
            "last_heartbeat_age=$age boot_id=$bootId car_off=$carOff car_off_lit=$lit heartbeat=$timer"
    }

    private companion object {
        const val TAG = "AutomationEngine"
        const val SERVICE_START_WAKE_GAP_MS = 60_000L
        const val SERVICE_START_HEARTBEAT_MS = 30_000L
        // How long the screen may stay on after ACC_OFF before the marker is taken for a car
        // that stayed on.
        const val SERVICE_START_CAR_OFF_LIT_MAX_MS = 120_000L
    }
}
