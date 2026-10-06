package com.bydmate.app.hud

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.navdata.NavA11yFeed
import com.bydmate.app.navdata.NavGuidanceHub
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** HUD output lifecycle. Ordering rules (Codex fixes 1/2/4):
 *  - the SOME/IP package probe runs BEFORE any helper-daemon work: cars without the
 *    factory HUD see zero side effects and KEY_SUPPORTED=false is persisted so the
 *    a11y self-heal never keeps the service alive for HUD alone; a layout a HUD check left
 *    kept is put back on any car, HUD on or off;
 *  - bind (up to ~71 s of retries) runs OUTSIDE the mutex in a cancellable job, so
 *    toggle-off never blocks on it;
 *  - teardown sends one clear frame, then stopService, then unbind. */
@Singleton
class HudController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helperClient: HelperClient,
    private val helperBootstrap: HelperBootstrap,
) {
    enum class Status { OFF, UNSUPPORTED, CONNECTING, ON, BIND_FAILED }

    /** Snapshot of HUD push-loop diagnostics for the settings dump. */
    data class HudDiag(
        val framesSent: Long,
        val lastFrameTs: Long,
        val lastRc: Int,
        val nonZeroRcCount: Long,
        val amapCapable: Boolean = false,
        val amapFramesSent: Long = 0,
        val amapStopsSent: Long = 0,
        val canAccepted: Long = 0,
        val canRefused: Long = 0,
    )

    companion object {
        private const val TAG = "HudController"
        const val PREFS_NAME = "hud"
        const val KEY_ENABLED = "hud_enabled"
        const val KEY_SUPPORTED = "hud_supported"
        const val KEY_SPEED_SIGN = "hud_speed_sign"
        const val KEY_MODE = "hud_mode"

        /** The way hints go to the glass («Способ вывода на стекло»). Way 1 (default, 3.19.0): the
         *  frames alone, the car's navigation status is never raised. Way 2 (3.19.1 + CAN): [HudArming]
         *  raises it while a route is guided (#266) and [HudWayChannels] writes the CAN guidance
         *  fields. Way 3: way 2 plus OpenBYD's LAUNCHER_MAP_CN family. */
        const val MODE_GLASS_ONLY = 1
        const val MODE_NAVI_STATUS = 2
        const val MODE_LMCN = 3

        // Where a bind failed, for the status line.
        private const val STEP_BIND = "bind"
        private const val STEP_START_SERVICE = "start_service"
        private const val STEP_BINDING_LOST = "binding_lost"
    }

    /** Single lane: stop()/startIfEnabled() launched across a service restart must
     *  execute in submission order - guards alone cannot give that (final-review fix 2). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal var scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    internal var bridgeFactory: (Context, () -> Unit) -> HudSomeIpBridge =
        { ctx, onLost -> HudSomeIpBridge(ctx, onLost) }
    internal var leftoverRetryMs = HudArming.CHECK_PERIOD_MS

    private val mutex = Mutex()
    // One owner of the car's navigation values at a time: a leftover put-back step and the
    // moment the arming loop starts or stops both run under it.
    private val ownerLock = Mutex()
    private var startJob: Job? = null
    private var leftoverJob: Job? = null
    /** The put-back while the output is off ([putBackLeftover]), the family's one-off binding
     *  included: the output going on or stopping cancels all of it. */
    private var putBackJob: Job? = null
    @Volatile private var bridge: HudSomeIpBridge? = null
    @Volatile private var loop: HudPushLoop? = null
    @Volatile private var arming: HudArming? = null
    /** Ways 2 and 3: the CAN fields and the family, next to [arming]; never in way 1. */
    @Volatile private var channels: HudWayChannels? = null

    /** HUD check: while set, the product arming treats guidance as absent (disarming if it was
     *  armed), so the check's own arming session is the only one writing. */
    @Volatile internal var armingPaused = false

    /** HUD check: the product's bound gateway, borrowed instead of binding a second one. */
    internal val boundBridge: HudSomeIpBridge? get() = bridge

    /** HUD check: the product's arming loop runs, so a route that starts is armed and closed by it. */
    internal val armingLive: Boolean get() = arming != null

    private val _status = MutableStateFlow(
        if (isEnabled() && !prefs().getBoolean(KEY_SUPPORTED, true)) Status.UNSUPPORTED else Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    fun isSpeedSignEnabled(): Boolean = prefs().getBoolean(KEY_SPEED_SIGN, true)

    fun setSpeedSignEnabled(on: Boolean) {
        prefs().edit().putBoolean(KEY_SPEED_SIGN, on).apply()
    }

    fun mode(): Int = prefs().getInt(KEY_MODE, MODE_GLASS_ONLY)

    private fun armsNaviStatus(): Boolean = mode() >= MODE_NAVI_STATUS

    /** A running output follows the new way at once: way 1 cleans up and disarms a raised status
     *  the same way [stop] does, ways 2 and 3 start arming and their channels. Not running, the
     *  next start reads the way. */
    fun setMode(mode: Int) {
        prefs().edit().putInt(KEY_MODE, mode).apply()
        scope.launch { applyMode() }
    }

    private suspend fun applyMode() = mutex.withLock {
        if (bridge == null && startJob?.isActive != true) return
        if (armsNaviStatus()) {
            // Still binding: the start job reads the mode once the gateway is up.
            val b = bridge ?: return
            ownerLock.withLock {
                if (arming == null) arming = HudArming(helperClient, prefs()).also { startArming(it) }
                if (channels?.way != mode()) {
                    channels?.stop()
                    channels = startChannels(b)
                }
            }
        } else {
            ownerLock.withLock {
                channels?.stop()
                channels = null
                arming?.stop(HudArming.REASON_WAY_CHANGE)
                arming = null
            }
            restoreLeftover()
        }
        NavGuidanceHub.hudWay = mode()
    }

    /**
     * A leftover kept in prefs (our status and layout still up after a process death, or a
     * put-back a fullscreen cluster postponed) while no arming loop runs: this job owns putting
     * it back. It retries every [leftoverRetryMs] while the cluster keeps the layout waiting, and
     * ends once nothing is kept, the arming loop takes over, a put-back failed for another reason
     * (the next start tries again), or the output stops. Nothing kept = nothing read or written.
     */
    private fun restoreLeftover() {
        if (leftoverJob?.isActive == true || !leftoverKept()) return
        leftoverJob = scope.launch {
            val arm = HudArming(helperClient, prefs())
            var tried = false
            while (leftoverKept()) {
                val done = ownerLock.withLock {
                    when {
                        arming != null -> true
                        // The HUD check's session holds the kept layout; mode 2 leaves a guided
                        // route to the arming loop, but only while a bind that starts it is running.
                        armingPaused || (armsNaviStatus() && NavGuidanceHub.snapshot().active &&
                            startJob?.isActive == true && bridge == null) -> false
                        else -> withContext(NonCancellable) { putBackStep(arm, tried).also { tried = true } }
                    }
                }
                if (done) break
                delay(leftoverRetryMs)
            }
        }
    }

    /** Our status and layout kept up, CAN values of way 2 or 3 (way 3's rest of route too) left on
     *  the instrument, or way 3's family left up on the gateway. */
    private fun leftoverKept(): Boolean =
        HudArming.leftover(prefs()) || prefs().contains(HudWayChannels.KEY_CAN_LEFT) ||
            prefs().contains(HudWayChannels.KEY_REST_LEFT) || prefs().contains(HudWayChannels.KEY_LMCN_LEFT)

    /** One put-back; true when it is over, false while the fullscreen cluster still defers it. The
     *  CAN values go first, while the status is still up; a refused clear waits for the next start. */
    private suspend fun putBackStep(arm: HudArming, retry: Boolean): Boolean = runCatching {
        if (!retry) HudWayChannels.clearCanLeftover(HudCanChannel(helperClient), prefs())
        when {
            !HudArming.leftover(prefs()) -> true
            retry -> arm.retryDeferred()
            else -> arm.disarmLeftover(guided = false)?.screenDeferred != true
        }
    }.getOrElse {
        Log.w(TAG, "hud disarm: reason=leftover failed: ${it.javaClass.simpleName}")
        true
    }

    private fun startArming(arm: HudArming) {
        arm.beforeDisarm = { channels?.close() }
        arm.afterArm = { channels?.reopen() }
        arm.start(scope, layoutOwned = { !armingPaused }) { !armingPaused && NavGuidanceHub.snapshot().active }
    }

    /** Ways 2 and 3 only: they write while the arming holds a guided route's status up. */
    private fun startChannels(b: HudSomeIpBridge): HudWayChannels? {
        if (!armsNaviStatus()) return null
        return HudWayChannels(mode(), HudCanChannel(helperClient), b, prefs()) { HudPosition.lastKnown(context) }
            .also { ch ->
                ch.start(scope, held = { armingPaused }, closed = arming?.closing == true) {
                    NavGuidanceHub.snapshot().active && arming?.armed == true
                }
            }
    }

    fun diag(): HudDiag? = loop?.let { l ->
        HudDiag(
            framesSent = l.framesSent,
            lastFrameTs = l.lastFrameTs,
            lastRc = l.lastRc,
            nonZeroRcCount = l.nonZeroRcCount,
            amapCapable = l.amap?.capable ?: false,
            amapFramesSent = l.amap?.framesSent ?: 0,
            amapStopsSent = l.amap?.stopsSent ?: 0,
            canAccepted = channels?.canAccepted ?: 0,
            canRefused = channels?.canRefused ?: 0,
        )
    }

    /** True only when the feature is on AND the gateway probe confirmed support -
     *  the a11y keep-alive gate must not fire on the raw pref (Codex fix 1). */
    fun requiresA11y(): Boolean = isEnabled() && prefs().getBoolean(KEY_SUPPORTED, true) &&
        HudSomeIpBridge.isServicePresent(context.packageManager)

    fun setEnabled(on: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, on).apply()
        if (on) {
            scope.launch { startSequence() }
        } else {
            NavA11yFeed.enabled = false   // stop tree reads immediately; teardown is async
            scope.launch { stopSequence(HudArming.REASON_HUD_OFF) }
        }
    }

    /** TrackingService.onCreate hook. */
    fun startIfEnabled() {
        if (isEnabled()) {
            scope.launch { startSequence() }
        } else if (putBackJob?.isActive != true) {
            putBackJob = scope.launch { putBackLeftover() }
        }
    }

    /** TrackingService.onDestroy hook. */
    fun stop() {
        NavA11yFeed.enabled = false
        scope.launch { stopSequence(HudArming.REASON_SERVICE_STOP) }
    }

    private suspend fun startSequence() = mutex.withLock {
        if (!isEnabled()) return
        if (startJob?.isActive == true || bridge != null) return
        // The output's own leftover handling takes over; the HUD-off put-back must not race it.
        putBackJob?.cancelAndJoin()
        putBackJob = null
        // Probe BEFORE any helper-daemon work: unsupported cars must see zero side effects.
        if (!HudSomeIpBridge.isServicePresent(context.packageManager)) {
            prefs().edit().putBoolean(KEY_SUPPORTED, false).apply()
            setStatus(Status.UNSUPPORTED)
            Log.i(TAG, "SOME/IP gateway absent; HUD output stays unloaded")
            putBackLeftover()
            return
        }
        prefs().edit().putBoolean(KEY_SUPPORTED, true).apply()
        setStatus(Status.CONNECTING)
        // Self-enable the a11y data source via the helper daemon (DiLink has no a11y UI).
        if (helperBootstrap.ensureRunning()) helperClient.enableAccessibilityService()
        // Bind OUTSIDE the mutex: up to ~71 s and must not block toggle-off (Codex fix 2).
        startJob = scope.launch {
            // A process killed mid-route left our values up.
            restoreLeftover()
            val b = bridgeFactory(context) { onBindingLost() }
            try {
                if (!b.bind()) {
                    b.unbind()
                    Log.w(TAG, "HUD gateway bind failed")
                    setStatus(Status.BIND_FAILED, STEP_BIND)
                    return@launch
                }
                val rc = b.startService(HudSomeIpBridge.SERVICE_ID_NAVI)
                if (rc < 0) {
                    b.unbind()
                    Log.w(TAG, "HUD gateway startService failed rc=$rc")
                    setStatus(Status.BIND_FAILED, STEP_START_SERVICE, rc)
                    return@launch
                }
                HudIconLoader.init(context)
                // Taken before the bridge is set: cancelled while waiting, nothing is up yet.
                ownerLock.withLock {
                    bridge = b
                    // A family a process death left up is stopped before a new route can start one.
                    HudWayChannels.stopLmcnLeftover(b, prefs())
                    NavA11yFeed.enabled = true
                    loop = HudPushLoop(b, speedSignEnabled = { isSpeedSignEnabled() },
                        amap = HudAmapBroadcaster(context),
                        maneuvers = HudManeuverJournal(prefs()))
                        .also { it.start(scope) }
                    // Mode 2: the car's navigation status, only while a route is guided; own
                    // coroutine, so a slow or refused write never delays a frame.
                    if (armsNaviStatus()) {
                        arming = HudArming(helperClient, prefs()).also { startArming(it) }
                        channels = startChannels(b)
                    }
                }
                if (arming == null) restoreLeftover()
                NavGuidanceHub.hudWay = mode()
                setStatus(Status.ON, rc = rc)
                Log.i(TAG, "HUD output active")
            } catch (ce: CancellationException) {
                b.unbind()
                throw ce
            }
        }
    }

    /** A layout the HUD check kept (process killed mid-check, or its put-back deferred at a
     *  fullscreen cluster) on a car where the HUD output does not start: nothing else would put it
     *  back. Without the key nothing starts, reads or writes. A family left up gets its stops
     *  last, on a binding of its own. */
    private suspend fun putBackLeftover() {
        // Taken before anything suspends: a key a route writes meanwhile is not this put-back's.
        val lmcnRoute = prefs().takeIf { it.contains(HudWayChannels.KEY_LMCN_LEFT) }?.getLong(HudWayChannels.KEY_LMCN_LEFT, 0L)
        if (!leftoverKept() || armingPaused || NavGuidanceHub.snapshot().active) return
        if (!helperBootstrap.ensureRunning()) return
        runCatching {
            HudWayChannels.clearCanLeftover(HudCanChannel(helperClient), prefs())
            HudArming(helperClient, prefs()).disarmLeftover(guided = false)
        }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "hud disarm: reason=leftover failed: ${it.javaClass.simpleName}")
        }
        lmcnRoute?.let { stopLmcnLeftover(it) }
    }

    /** Way 3's family a process death left up while the output does not run: a one-off binding
     *  for its off events and stops, then let go. Unbound, the key waits for the next start. A key
     *  that no longer holds [routeId] when the binding is up (a new route's) is not its to stop. */
    private suspend fun stopLmcnLeftover(routeId: Long) {
        currentCoroutineContext().ensureActive()
        if (!HudSomeIpBridge.isServicePresent(context.packageManager)) return
        val b = bridgeFactory(context) {}
        try {
            if (!b.bind()) return
            val same = prefs().contains(HudWayChannels.KEY_LMCN_LEFT) &&
                prefs().getLong(HudWayChannels.KEY_LMCN_LEFT, 0L) == routeId
            if (same) HudWayChannels.stopLmcnLeftover(b, prefs())
            else Log.i(TAG, "hud way: leftover lmcn changed while binding, left to its owner")
        } finally {
            b.unbind()
        }
    }

    /** Gateway binding died (crash/update). Clean up so the next startIfEnabled()
     *  (TrackingService restarts every ignition cycle) can rebuild the channel instead
     *  of hitting the bridge!=null guard forever, and so the push loop stops firing
     *  into a dead binder (final-review fix 1). */
    private fun onBindingLost() {
        NavA11yFeed.enabled = false
        scope.launch {
            mutex.withLock {
                loop?.stop()
                loop = null
                channels?.stop()
                channels = null
                arming?.stop(HudArming.REASON_BINDING_LOST)
                arming = null
                // A layout the stop deferred has no loop left to finish it. bridge null = a stop
                // already ended this session: its late callback starts nothing.
                if (bridge != null) restoreLeftover()
                bridge = null   // the bridge already unbound itself in onBindingDied
                NavGuidanceHub.hudWay = 0
                setStatus(Status.BIND_FAILED, STEP_BINDING_LOST)
            }
        }
    }

    /** [reason]: why the output stops (HUD off or service stop), for the disarm line. */
    private suspend fun stopSequence(reason: String) {
        NavA11yFeed.enabled = false
        mutex.withLock {
            startJob?.let { it.cancel(); it.join() }
            startJob = null
            leftoverJob?.cancelAndJoin()
            leftoverJob = null
            putBackJob?.cancelAndJoin()
            putBackJob = null
            // startJob may have flipped the feed back on between our first write and its
            // completion (no suspension points after bind()) - re-clear (final-review fix 3).
            NavA11yFeed.enabled = false
            loop?.stop()
            loop = null
            // Blank the CAN fields and stop the family, then close the car's navigation status and put
            // the layout back, all before the channel goes.
            channels?.stop()
            channels = null
            arming?.stop(reason)
            arming = null
            bridge?.let {
                // Leave the HUD clean before tearing the channel down (Codex fix 4).
                runCatching { it.fireEvent(HudSomeIpBridge.TOPIC_NAVI, HudProtobufBuilder.buildClearFrame(0)) }
                runCatching { it.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
                runCatching { it.unbind() }
            }
            bridge = null
            NavGuidanceHub.hudWay = 0
            setStatus(Status.OFF)
            // NavGuidanceHub is intentionally NOT reset: the voice agent keeps using it.
        }
    }

    /** The status and its trace line, written on a change only: a service stop with the HUD off
     *  writes nothing. [step] and [rc]: where a bind failed and what the gateway answered. */
    private fun setStatus(to: Status, step: String? = null, rc: Int? = null) {
        if (_status.value == to) return
        _status.value = to
        Trace.event(TraceArea.HUD, "status", "to" to to, "step" to step, "rc" to rc)
    }
}
