package com.bydmate.app.hud

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
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
    )

    companion object {
        private const val TAG = "HudController"
        const val PREFS_NAME = "hud"
        const val KEY_ENABLED = "hud_enabled"
        const val KEY_SUPPORTED = "hud_supported"
        const val KEY_SPEED_SIGN = "hud_speed_sign"
        const val KEY_MODE = "hud_mode"

        /** Mode 1 (default, 3.19.0): hints go to the glass, the car's navigation status is never
         *  raised. Mode 2 (3.19.1): [HudArming] raises it while a route is guided (#266). */
        const val MODE_GLASS_ONLY = 1
        const val MODE_NAVI_STATUS = 2
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
    @Volatile private var bridge: HudSomeIpBridge? = null
    @Volatile private var loop: HudPushLoop? = null
    @Volatile private var arming: HudArming? = null

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

    private fun armsNaviStatus(): Boolean = mode() == MODE_NAVI_STATUS

    /** A running output follows the new mode at once: mode 1 disarms a raised status the same
     *  way [stop] does, mode 2 starts arming. Not running, the next start reads the mode. */
    fun setMode(mode: Int) {
        prefs().edit().putInt(KEY_MODE, mode).apply()
        scope.launch { applyMode() }
    }

    private suspend fun applyMode() = mutex.withLock {
        if (bridge == null && startJob?.isActive != true) return
        if (armsNaviStatus()) {
            // Still binding: the start job reads the mode once the gateway is up.
            if (bridge != null && arming == null) {
                ownerLock.withLock { arming = HudArming(helperClient, prefs()).also { startArming(it) } }
            }
        } else {
            ownerLock.withLock {
                arming?.stop()
                arming = null
            }
            restoreLeftover()
        }
    }

    /**
     * A leftover kept in prefs (our status and layout still up after a process death, or a
     * put-back a fullscreen cluster postponed) while no arming loop runs: this job owns putting
     * it back. It retries every [leftoverRetryMs] while the cluster keeps the layout waiting, and
     * ends once nothing is kept, the arming loop takes over, a put-back failed for another reason
     * (the next start tries again), or the output stops. Nothing kept = nothing read or written.
     */
    private fun restoreLeftover() {
        if (leftoverJob?.isActive == true || !prefs().contains(HudArming.KEY_AS_FOUND)) return
        leftoverJob = scope.launch {
            val arm = HudArming(helperClient, prefs())
            var tried = false
            while (prefs().contains(HudArming.KEY_AS_FOUND)) {
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

    /** One put-back; true when it is over, false while the fullscreen cluster still defers it. */
    private suspend fun putBackStep(arm: HudArming, retry: Boolean): Boolean = runCatching {
        if (retry) arm.retryDeferred() else arm.disarmLeftover(guided = false)?.screenDeferred != true
    }.getOrElse {
        Log.w(TAG, "hud disarm: reason=leftover failed: ${it.javaClass.simpleName}")
        true
    }

    private fun startArming(arm: HudArming) =
        arm.start(scope, layoutOwned = { !armingPaused }) { !armingPaused && NavGuidanceHub.snapshot().active }

    fun diag(): HudDiag? = loop?.let { l ->
        HudDiag(
            framesSent = l.framesSent,
            lastFrameTs = l.lastFrameTs,
            lastRc = l.lastRc,
            nonZeroRcCount = l.nonZeroRcCount,
            amapCapable = l.amap?.capable ?: false,
            amapFramesSent = l.amap?.framesSent ?: 0,
            amapStopsSent = l.amap?.stopsSent ?: 0,
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
            scope.launch { stopSequence() }
        }
    }

    /** TrackingService.onCreate hook. */
    fun startIfEnabled() {
        if (isEnabled()) scope.launch { startSequence() } else scope.launch { putBackLeftover() }
    }

    /** TrackingService.onDestroy hook. */
    fun stop() {
        NavA11yFeed.enabled = false
        scope.launch { stopSequence() }
    }

    private suspend fun startSequence() = mutex.withLock {
        if (!isEnabled()) return
        if (startJob?.isActive == true || bridge != null) return
        // Probe BEFORE any helper-daemon work: unsupported cars must see zero side effects.
        if (!HudSomeIpBridge.isServicePresent(context.packageManager)) {
            prefs().edit().putBoolean(KEY_SUPPORTED, false).apply()
            _status.value = Status.UNSUPPORTED
            Log.i(TAG, "SOME/IP gateway absent; HUD output stays unloaded")
            putBackLeftover()
            return
        }
        prefs().edit().putBoolean(KEY_SUPPORTED, true).apply()
        _status.value = Status.CONNECTING
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
                    _status.value = Status.BIND_FAILED
                    return@launch
                }
                val rc = b.startService(HudSomeIpBridge.SERVICE_ID_NAVI)
                if (rc < 0) {
                    b.unbind()
                    _status.value = Status.BIND_FAILED
                    return@launch
                }
                HudIconLoader.init(context)
                // Taken before the bridge is set: cancelled while waiting, nothing is up yet.
                ownerLock.withLock {
                    bridge = b
                    NavA11yFeed.enabled = true
                    loop = HudPushLoop(b, speedSignEnabled = { isSpeedSignEnabled() },
                        amap = HudAmapBroadcaster(context),
                        maneuvers = HudManeuverJournal(prefs()))
                        .also { it.start(scope) }
                    // Mode 2: the car's navigation status, only while a route is guided; own
                    // coroutine, so a slow or refused write never delays a frame.
                    if (armsNaviStatus()) arming = HudArming(helperClient, prefs()).also { startArming(it) }
                }
                if (arming == null) restoreLeftover()
                _status.value = Status.ON
                Log.i(TAG, "HUD output active")
            } catch (ce: CancellationException) {
                b.unbind()
                throw ce
            }
        }
    }

    /** A layout the HUD check kept (process killed mid-check, or its put-back deferred at a
     *  fullscreen cluster) on a car where the HUD output does not start: nothing else would put it
     *  back. Without the key nothing starts, reads or writes. */
    private suspend fun putBackLeftover() {
        if (!prefs().contains(HudArming.KEY_AS_FOUND) || armingPaused || NavGuidanceHub.snapshot().active) return
        if (!helperBootstrap.ensureRunning()) return
        runCatching { HudArming(helperClient, prefs()).disarmLeftover(guided = false) }
            .onFailure { Log.w(TAG, "hud disarm: reason=leftover failed: ${it.javaClass.simpleName}") }
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
                arming?.stop()
                arming = null
                // A layout the stop deferred has no loop left to finish it. bridge null = a stop
                // already ended this session: its late callback starts nothing.
                if (bridge != null) restoreLeftover()
                bridge = null   // the bridge already unbound itself in onBindingDied
                _status.value = Status.BIND_FAILED
            }
        }
    }

    private suspend fun stopSequence() {
        NavA11yFeed.enabled = false
        mutex.withLock {
            startJob?.let { it.cancel(); it.join() }
            startJob = null
            leftoverJob?.cancelAndJoin()
            leftoverJob = null
            // startJob may have flipped the feed back on between our first write and its
            // completion (no suspension points after bind()) - re-clear (final-review fix 3).
            NavA11yFeed.enabled = false
            loop?.stop()
            loop = null
            // Close the car's navigation status and put the layout back before the channel goes.
            arming?.stop()
            arming = null
            bridge?.let {
                // Leave the HUD clean before tearing the channel down (Codex fix 4).
                runCatching { it.fireEvent(HudSomeIpBridge.TOPIC_NAVI, HudProtobufBuilder.buildClearFrame(0)) }
                runCatching { it.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
                runCatching { it.unbind() }
            }
            bridge = null
            _status.value = Status.OFF
            // NavGuidanceHub is intentionally NOT reset: the voice agent keeps using it.
        }
    }
}
