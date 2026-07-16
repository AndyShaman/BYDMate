package com.bydmate.app.hud

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.navdata.NavA11yFeed
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** HUD output lifecycle. Ordering rules (Codex fixes 1/2/4):
 *  - the SOME/IP package probe runs BEFORE any helper-daemon work: cars without the
 *    factory HUD see zero side effects and KEY_SUPPORTED=false is persisted so the
 *    a11y self-heal never keeps the service alive for HUD alone;
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

    companion object {
        private const val TAG = "HudController"
        const val PREFS_NAME = "hud"
        const val KEY_ENABLED = "hud_enabled"
        const val KEY_SUPPORTED = "hud_supported"
        const val KEY_SPEED_SIGN = "hud_speed_sign"
    }

    /** Single lane: stop()/startIfEnabled() launched across a service restart must
     *  execute in submission order - guards alone cannot give that (final-review fix 2). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    internal var scope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    internal var bridgeFactory: (Context, () -> Unit) -> HudSomeIpBridge =
        { ctx, onLost -> HudSomeIpBridge(ctx, onLost) }

    private val mutex = Mutex()
    private var startJob: Job? = null
    @Volatile private var bridge: HudSomeIpBridge? = null
    @Volatile private var loop: HudPushLoop? = null

    private val _status = MutableStateFlow(
        if (isEnabled() && !prefs().getBoolean(KEY_SUPPORTED, true)) Status.UNSUPPORTED else Status.OFF)
    val status: StateFlow<Status> = _status.asStateFlow()

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    fun isSpeedSignEnabled(): Boolean = prefs().getBoolean(KEY_SPEED_SIGN, true)

    fun setSpeedSignEnabled(on: Boolean) {
        prefs().edit().putBoolean(KEY_SPEED_SIGN, on).apply()
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
        if (isEnabled()) scope.launch { startSequence() }
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
            return
        }
        prefs().edit().putBoolean(KEY_SUPPORTED, true).apply()
        _status.value = Status.CONNECTING
        // Self-enable the a11y data source via the helper daemon (DiLink has no a11y UI).
        if (helperBootstrap.ensureRunning()) helperClient.enableAccessibilityService()
        // Bind OUTSIDE the mutex: up to ~71 s and must not block toggle-off (Codex fix 2).
        startJob = scope.launch {
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
                bridge = b
                NavA11yFeed.enabled = true
                loop = HudPushLoop(b, speedSignEnabled = { isSpeedSignEnabled() })
                    .also { it.start(scope) }
                _status.value = Status.ON
                Log.i(TAG, "HUD output active")
            } catch (ce: CancellationException) {
                b.unbind()
                throw ce
            }
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
            // startJob may have flipped the feed back on between our first write and its
            // completion (no suspension points after bind()) - re-clear (final-review fix 3).
            NavA11yFeed.enabled = false
            loop?.stop()
            loop = null
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
