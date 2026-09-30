package com.bydmate.app.hud

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.navdata.NavGuidanceHub
import kotlin.random.Random
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Ways 2 and 3 of the HUD output, on top of the frames and the raised status ([HudArming]):
 * way 2 adds the instrument's CAN guidance fields ([HudCanChannel]), written the way OpenBYD's
 * CanBydFidStrategy writes them: the icon id as is into both icon fids with the distance when
 * either changes, the road name (in Latin, as OpenBYD transliterates it) when it changes. Way 3
 * adds OpenBYD's LAUNCHER_MAP_CN family ([HudLauncherMapCnFrames]): its six gateway services
 * started at the route start, the update set every [PERIOD_MS], the off events and the stops at
 * the end.
 *
 * Only while a route is guided and the status is up (`active`). [close] runs before every disarm
 * (route end, way change, HUD off, service stop): the CAN fields blanked with distance 0, then the
 * family's off events and stopped services. What a process death leaves is kept in [prefs]
 * ([KEY_CAN_LEFT], [KEY_LMCN_LEFT]), committed to disk before the first write it covers, and
 * cleaned at the next start (CAN values also by the next way 2 or 3 run, before its first disarm);
 * a refused CAN clear is kept too and tried again every [RETRY_MS], and so is a refused CAN write.
 */
@Suppress("TooManyFunctions") // the route's open, writes, close and their log lines, kept in one place
class HudWayChannels(
    val way: Int,
    private val can: HudCanChannel,
    private val gateway: HudSomeIpBridge?,
    private val prefs: SharedPreferences,
    private val position: () -> HudLauncherMapCnFrames.Position,
) {
    internal var snapshot: () -> NavGuidanceHub.Snapshot = { NavGuidanceHub.snapshot() }
    internal var random: Random = Random.Default
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var log: (String) -> Unit = { Log.i(TAG, it) }

    /** CAN guidance writes since this way started, for the dump; the clears are not counted. */
    @Volatile var canAccepted: Long = 0L
        private set
    @Volatile var canRefused: Long = 0L
        private set

    private val lock = Mutex()
    private var job: Job? = null
    @Volatile private var closing = false
    private var routeOpen = false
    /** Our values may be on the instrument: kept before the first write, dropped by an accepted
     *  clear. Read from [prefs], so values a process death left are this way's to clear too. */
    private val canDirty: Boolean get() = prefs.contains(KEY_CAN_LEFT)
    /** What the instrument accepted last; a refused write waits [RETRY_MS] before it is tried again. */
    private var lastGuidance: Pair<Int, Int>? = null
    private var lastRoad: String? = null
    private var guidanceWaitMs = 0L
    private var roadWaitMs = 0L
    /** The navigator's road and what [roadName] made of it: the transliteration runs on a change only. */
    private var roadMemo: Pair<String, String>? = null
    private var routeAccepted = 0
    private var routeRefused = 0
    private val lmcnServices = LinkedHashMap<Long, Int>()
    private val lmcnFires = LinkedHashMap<Long, MutableMap<Int, Int>>()
    private var routeId = 0L
    private var counter = 0
    /** The position goes out every tick but is looked up once per [RETRY_MS]: a system call. */
    private var lastPosition = HudLauncherMapCnFrames.Position.DEFAULT
    private var positionAgeMs = 0L
    private var retryWaitMs = 0L

    private val lmcn: Boolean get() = way >= HudController.MODE_LMCN && gateway != null
    /** This route runs the family: way 3 with its marker on disk. */
    private var lmcnRoute = false

    /** The loop: [active] = a route is guided and the status is up. */
    fun start(scope: CoroutineScope, active: () -> Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                val on = runCatching { active() }.getOrDefault(false)
                runCatching { tick(on) }.onFailure {
                    if (it is CancellationException) throw it
                    Log.w(TAG, "hud way: step failed: ${it.javaClass.simpleName}")
                }
                delay(PERIOD_MS)
            }
        }
    }

    /** Stops the loop and cleans up what the route left. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        withContext(NonCancellable) { close() }
    }

    /** The cleanup before a disarm; nothing written = nothing to undo. From here until [reopen]
     *  the loop writes nothing and starts nothing: a route back while the disarm still runs would
     *  otherwise draw again under a status about to go down. */
    suspend fun close() {
        closing = true
        lock.withLock { closeLocked() }
    }

    /** The next arm raised the status again ([HudArming.afterArm]): the loop may write. */
    fun reopen() {
        closing = false
    }

    internal suspend fun tick(active: Boolean) = lock.withLock {
        if (!active) {
            if (!routeOpen && !canDirty && lmcnServices.isEmpty()) return@withLock
            if (retryWaitMs > 0) {
                retryWaitMs -= PERIOD_MS
                if (retryWaitMs > 0) return@withLock
            }
            closeLocked()
            return@withLock
        }
        if (closing) return@withLock
        val s = snapshot()
        if (!routeOpen) openLocked()
        writeCan(s)
        if (lmcnRoute) fireUpdate(s)
    }

    private fun openLocked() {
        routeOpen = true
        retryWaitMs = 0L
        routeAccepted = 0
        routeRefused = 0
        lmcnFires.clear()
        guidanceWaitMs = 0L
        roadWaitMs = 0L
        val bridge = gateway
        lmcnRoute = false
        if (lmcn && bridge != null) {
            routeId = HudLauncherMapCnFrames.newRouteId(random)
            // Kept on disk before the first start: a process death from here on leaves the family up.
            lmcnRoute = mark("lmcn", KEY_LMCN_LEFT) { putLong(KEY_LMCN_LEFT, routeId) }
        }
        if (lmcnRoute && bridge != null) {
            counter = 0
            positionAgeMs = RETRY_MS
            HudLauncherMapCnFrames.SERVICE_IDS.forEach { id ->
                val rc = runCatching { bridge.startService(id) }.getOrDefault(THREW)
                lmcnServices[id] = rc
                Trace.event(TraceArea.HUD, "way-service", "op" to "start", "id" to HudSomeIpBridge.hex(id), "rc" to rc)
            }
        }
        val services = if (lmcn) " services=${HudSomeIpBridge.describeServices(lmcnServices)}" else ""
        log("hud way: route start way=$way channels=${channels()}$services")
        Trace.event(
            TraceArea.HUD, "way-route", "way" to way, "channels" to channels(),
            "started" to if (lmcn) okCount(lmcnServices) else "none",
        )
    }

    private suspend fun writeCan(s: NavGuidanceHub.Snapshot) {
        guidanceWaitMs = (guidanceWaitMs - PERIOD_MS).coerceAtLeast(0L)
        roadWaitMs = (roadWaitMs - PERIOD_MS).coerceAtLeast(0L)
        val guidance = turnKind(s.maneuverGaode) to s.distanceMeters.coerceIn(0, MAX_DISTANCE_M)
        val road = roadMemo?.takeIf { it.first == s.road }?.second
            ?: roadName(s.road).also { roadMemo = s.road to it }
        val guidanceDue = guidance != lastGuidance && guidanceWaitMs == 0L
        val roadDue = road != lastRoad && roadWaitMs == 0L
        if (!guidanceDue && !roadDue) return
        // Kept on disk before the first write: a process death from here on leaves our values up.
        if (!canDirty && !mark("can", KEY_CAN_LEFT) { putBoolean(KEY_CAN_LEFT, true) }) {
            guidanceWaitMs = RETRY_MS
            roadWaitMs = RETRY_MS
            return
        }
        if (guidanceDue) {
            if (can.guidance(guidance.first, guidance.second).map(::count).all { it }) lastGuidance = guidance
            else guidanceWaitMs = RETRY_MS
        }
        if (roadDue) {
            if (count(can.road(road))) lastRoad = road else roadWaitMs = RETRY_MS
        }
    }

    /** Counts one CAN write for the dump; true when the car accepted it. */
    private fun count(rc: Int?): Boolean {
        val ok = accepted(rc)
        if (ok) { routeAccepted++; canAccepted++ } else { routeRefused++; canRefused++ }
        return ok
    }

    /** Commits a leftover marker; a failed disk write is taken back out of memory and logged. */
    private fun mark(what: String, key: String, put: SharedPreferences.Editor.() -> Unit): Boolean {
        if (prefs.edit().apply(put).commit()) return true
        prefs.edit().remove(key).apply()
        log("hud way: marker not saved what=$what, held")
        Trace.event(TraceArea.HUD, "way-marker", "what" to what, "ok" to false)
        return false
    }

    private fun fireUpdate(s: NavGuidanceHub.Snapshot) {
        val bridge = gateway ?: return
        if (positionAgeMs >= RETRY_MS) {
            lastPosition = position()
            positionAgeMs = 0L
        }
        positionAgeMs += PERIOD_MS
        HudLauncherMapCnFrames.update(
            iconId = s.maneuverGaode, distanceM = s.distanceMeters, remainDistanceM = s.totalDistMeters,
            remainTimeS = s.etaSeconds, position = lastPosition, routeId = routeId, counter = counter, nowMs = nowMs(),
        ).forEach { e -> countFire(e.topic, fire(bridge, e)) }
        counter = (counter + 1) and COUNTER_MASK
    }

    private fun fire(bridge: HudSomeIpBridge, e: HudLauncherMapCnFrames.Event): Int =
        runCatching { bridge.fireEvent(e.topic, e.payload) }.getOrDefault(THREW)

    private fun countFire(topic: Long, rc: Int) {
        val counts = lmcnFires.getOrPut(topic) { sortedMapOf() }
        counts[rc] = (counts[rc] ?: 0) + 1
    }

    /** CAN first, while the status is still up, then the family; the route's end line. */
    private suspend fun closeLocked() {
        var clear = "none"
        if (canDirty) {
            val ok = runCatching { clearCan(can) }.getOrElse {
                if (it is CancellationException) throw it
                false
            }
            clear = if (ok) "ok" else "refused"
            if (ok) prefs.edit().remove(KEY_CAN_LEFT).apply()
        }
        var stopped: Map<Long, Int> = emptyMap()
        val bridge = gateway
        if (bridge != null && lmcnServices.isNotEmpty()) {
            HudLauncherMapCnFrames.stop(routeId, nowMs()).forEach { e -> countFire(e.topic, fire(bridge, e)) }
            stopped = stopServices(bridge, lmcnServices.keys, prefs)
            lmcnServices.clear()
        }
        if (routeOpen) logEnd(clear, stopped)
        routeOpen = false
        lmcnRoute = false
        lastGuidance = null
        lastRoad = null
        retryWaitMs = if (canDirty) RETRY_MS else 0L
    }

    private fun logEnd(clear: String, stopped: Map<Long, Int>) {
        val fired = HudSomeIpBridge.describeFires(lmcnFires)
        val lmcnPart = if (lmcn) " fire=$fired stop=${HudSomeIpBridge.describeServices(stopped)}" else ""
        log("hud way: route end way=$way can accepted=$routeAccepted refused=$routeRefused clear=$clear$lmcnPart")
        Trace.event(
            TraceArea.HUD, "way-route-end", "way" to way, "can-ok" to routeAccepted, "can-refused" to routeRefused,
            "clear" to clear, "fired" to lmcnFires.values.sumOf { it.values.sum() },
            "stopped" to if (lmcn) okCount(stopped) else "none",
        )
        // A trace value holds 80 characters: each topic its own line.
        lmcnFires.forEach { (topic, counts) ->
            Trace.event(TraceArea.HUD, "way-fire", "topic" to HudSomeIpBridge.hex(topic), "rc" to histogram(counts))
        }
    }

    private fun channels(): String = if (lmcn) "can,someip-lmcn" else "can"

    companion object {
        private const val TAG = "HudWayChannels"

        /** HUD prefs: our CAN guidance values may still be on the instrument. */
        const val KEY_CAN_LEFT = "hud_can_left"
        /** HUD prefs: the family's route id while its services may still be up. */
        const val KEY_LMCN_LEFT = "hud_lmcn_left"

        /** The family's keep-alive; the CAN fields are looked at on the same tick. */
        const val PERIOD_MS = HudLauncherMapCnFrames.PERIOD_MS
        const val RETRY_MS = HudArming.CHECK_PERIOD_MS

        /** BYDAutoInstrumentDevice.sendSimpleGuidanceInfo accepts icon ids 0..102. */
        private const val MAX_TURN_KIND = 102
        /** ... and distances up to 16777214 m. */
        const val MAX_DISTANCE_M = 16_777_214
        /** sendNextPathName takes at most 255 bytes of UTF-16LE. */
        private const val MAX_ROAD_CHARS = 127
        private const val COUNTER_MASK = 0xFF
        /** rc slot of a gateway call that threw instead of answering. */
        private const val THREW = -3
        private const val UNBOUND = -1

        /** OpenBYD writes its icon id into TURN_KIND as is; ours is the same numbering (1 = left).
         *  Outside what the instrument accepts: blank. */
        fun turnKind(iconId: Int): Int = if (iconId in 0..MAX_TURN_KIND) iconId else 0

        /** The road name as the instrument takes it: in Latin ([HudTextSanitizer], trimmed), capped
         *  after that, a space when empty (the car rejects an empty buffer). */
        fun roadName(road: String): String = HudTextSanitizer.sanitize(road).take(MAX_ROAD_CHARS).ifEmpty { " " }

        internal fun accepted(rc: Int?): Boolean = rc != null && rc >= 0

        /** Blanks the CAN fields with the SDK's invalid distance 0; true when every write was accepted. */
        private suspend fun clearCan(can: HudCanChannel): Boolean {
            val sent = can.clear()
            return listOf(sent.iconRc, sent.iconAheadRc, sent.distRc, sent.roadRc).all(::accepted)
        }

        /** CAN values a process death left: blanked and forgotten; kept when the car refused.
         *  True when nothing is left. */
        suspend fun clearCanLeftover(can: HudCanChannel, prefs: SharedPreferences, log: (String) -> Unit = { Log.i(TAG, it) }): Boolean {
            if (!prefs.contains(KEY_CAN_LEFT)) return true
            val ok = runCatching { clearCan(can) }.getOrElse {
                if (it is CancellationException) throw it
                false
            }
            if (ok) prefs.edit().remove(KEY_CAN_LEFT).apply()
            log("hud way: leftover can clear=${if (ok) "ok" else "refused"}")
            Trace.event(TraceArea.HUD, "way-leftover", "what" to "can", "ok" to ok)
            return ok
        }

        /** The family a process death left up: its route's off events and the six stops on a new
         *  binding. Kept for the next start while the gateway does not answer. */
        fun stopLmcnLeftover(
            gateway: HudSomeIpBridge,
            prefs: SharedPreferences,
            nowMs: Long = System.currentTimeMillis(),
            log: (String) -> Unit = { Log.i(TAG, it) },
        ) {
            if (!prefs.contains(KEY_LMCN_LEFT)) return
            val routeId = prefs.getLong(KEY_LMCN_LEFT, 0L)
            HudLauncherMapCnFrames.stop(routeId, nowMs).forEach { e ->
                runCatching { gateway.fireEvent(e.topic, e.payload) }
            }
            val stopped = stopServices(gateway, HudLauncherMapCnFrames.SERVICE_IDS, prefs)
            log("hud way: leftover lmcn stop=${HudSomeIpBridge.describeServices(stopped)}")
            Trace.event(TraceArea.HUD, "way-leftover", "what" to "lmcn", "stopped" to okCount(stopped))
        }

        /** Stops [ids]; the family's leftover key goes once every stop was answered: not unbound
         *  (-1), not a transact that threw (-2), not a call that threw here (-3). */
        private fun stopServices(gateway: HudSomeIpBridge, ids: Collection<Long>, prefs: SharedPreferences): Map<Long, Int> {
            val stopped = LinkedHashMap<Long, Int>()
            ids.forEach { id ->
                val rc = runCatching { gateway.stopService(id) }.getOrDefault(THREW)
                stopped[id] = rc
                Trace.event(TraceArea.HUD, "way-service", "op" to "stop", "id" to HudSomeIpBridge.hex(id), "rc" to rc)
            }
            if (stopped.values.none { it in THREW..UNBOUND }) prefs.edit().remove(KEY_LMCN_LEFT).apply()
            return stopped
        }

        private fun okCount(services: Map<Long, Int>): String = "${services.values.count { it == 0 }}/${services.size}"

        private fun histogram(rcs: Map<Int, Int>): String =
            rcs.entries.joinToString(",", prefix = "{", postfix = "}") { "${it.key}:${it.value}" }
    }
}
