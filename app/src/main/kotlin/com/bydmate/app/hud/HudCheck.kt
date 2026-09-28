package com.bydmate.app.hud

import android.content.Context
import android.os.Build
import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperBootstrap
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.navdata.NavGuidanceHub
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * «Проверка HUD» (HUD wave 1): about 90 s of test hints on the glass, no route needed, so a video
 * of the glass plus the recorded log tells which channel draws on a given car. Each step draws its
 * own distance marker:
 *  1. our SOME/IP frame, the car's navigation status not raised: 111 m, «BYDMATE 1»;
 *  2. the status raised the product's way ([HudArming]), same frame: 222 m, «BYDMATE 2»;
 *  3. the instrument's own CAN fields ([HudCanChannel]) while raised: 333 m, «BYDMATE 3».
 * Then the restore: CAN fields blanked, NAVI_STATUS = 4, the layout as found, our gateway service
 * stopped. The restore runs whatever happened before it, cancellation included. A layout the
 * fullscreen cluster holds back is retried every 5 s for up to a minute.
 *
 * Refuses to start while a real route is guided, while the car moves faster than
 * [MAX_SPEED_KMH], or without the helper daemon (the speed cannot be known then). The speed is
 * looked at about once a second through the steps: moving off, or a speed that cannot be read,
 * ends the check with the full restore and [Refusal.MOVING]. A route that
 * starts during the steps ends the check at once: it stops drawing, blanks its CAN fields and,
 * when the projection's arming runs, leaves the status and the layout (with the as-found kept in
 * prefs) to it instead of closing them under the route.
 */
@Suppress("TooManyFunctions") // refusals, three steps, the restore and their log lines, kept in one place
@Singleton
class HudCheck @Inject constructor(
    @ApplicationContext private val context: Context,
    private val helperClient: HelperClient,
    private val helperBootstrap: HelperBootstrap,
    private val hudController: HudController,
) {
    sealed interface State {
        data object Idle : State
        data object Preparing : State
        data class Step(val number: Int) : State
        data object Restoring : State
        data object Done : State
        data class Refused(val reason: Refusal) : State
        /** A route started during the steps; the check stopped there. */
        data object RouteStarted : State
    }

    /** Thrown out of the steps when a route starts; [step] is where it was seen. */
    private class RouteStartedException(val step: Int) : Exception()

    /** Thrown out of the steps when the car moves off or its speed is unknown; [step] as above. */
    private class MovingException(val step: Int) : Exception()

    enum class Refusal { GUIDANCE, MOVING, NO_LINK }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    internal var scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    internal var stepMs = STEP_MS
    internal var bridgeFactory: (Context) -> HudSomeIpBridge = { HudSomeIpBridge(it) }
    internal var gatewayPresent: () -> Boolean = { HudSomeIpBridge.isServicePresent(context.packageManager) }
    internal var guidanceActive: () -> Boolean = { NavGuidanceHub.snapshot().active }
    internal var speedKmh: suspend () -> Int? = { readSpeed() }
    internal var fingerprint: String = Build.FINGERPRINT.orEmpty()
    internal var log: (String) -> Unit = { Log.i(TAG, it) }

    private var job: Job? = null

    /** What one run holds from step to step, and what the restore has to undo. */
    private inner class Run {
        val arming = HudArming(helperClient, context.getSharedPreferences(HudController.PREFS_NAME, Context.MODE_PRIVATE))
            .also { it.log = log }
        val can = HudCanChannel(helperClient)
        var sink: HudEventSink? = null
        var ownBridge: HudSomeIpBridge? = null
        var startRc = "none"
        var canShown = false
        var sinceSpeedMs = 0L
    }

    /** Starts a check unless one runs; the outcome lands in [state]. */
    fun start() {
        if (job?.isActive == true) return
        job = scope.launch { run() }
    }

    internal suspend fun run() {
        _state.value = State.Preparing
        refusal()?.let { reason ->
            log("hudprobe: refused reason=${reason.name.lowercase()}")
            Trace.event(TraceArea.HUD, "probe-refused", "reason" to reason)
            _state.value = State.Refused(reason)
            return
        }
        hudController.armingPaused = true
        val run = Run()
        var routeStep: Int? = null
        var movingStep: Int? = null
        try {
            runCatching { steps(run) }.onFailure {
                when (it) {
                    is CancellationException -> throw it
                    is RouteStartedException -> routeStep = it.step
                    is MovingException -> movingStep = it.step
                    else -> log("hudprobe: aborted ${it.javaClass.simpleName}")
                }
            }
        } finally {
            withContext(NonCancellable) { restore(run, routeStep, movingStep) }
        }
    }

    /** Looked at before anything goes out, at least once a second through the steps. */
    private fun watchGuidance(step: Int) {
        if (guidanceActive()) throw RouteStartedException(step)
    }

    /** The speed about once a second through the steps, [passedMs] after the last call. A route
     *  seen in the same look wins over the speed. */
    private suspend fun watchSpeed(run: Run, step: Int, passedMs: Long) {
        run.sinceSpeedMs += passedMs
        if (run.sinceSpeedMs < SPEED_LOOK_MS) return
        run.sinceSpeedMs = 0L
        val kmh = speedKmh()
        watchGuidance(step)
        if (kmh == null || kmh > MAX_SPEED_KMH) throw MovingException(step)
    }

    private suspend fun refusal(): Refusal? {
        if (guidanceActive()) return Refusal.GUIDANCE
        if (!helperBootstrap.ensureRunning()) return Refusal.NO_LINK
        val kmh = speedKmh() ?: return Refusal.NO_LINK
        return if (kmh > MAX_SPEED_KMH) Refusal.MOVING else null
    }

    private suspend fun steps(run: Run) {
        val gateway = gatewayPresent()
        census(run, gateway)
        HudIconLoader.init(context)

        _state.value = State.Step(1)
        openGateway(run, gateway)
        val step1 = frames(run, 1, MARKER_1, "BYDMATE 1", recheck = false)
        logSomeIpStep(1, armed = false, run.startRc, step1, MARKER_1)

        _state.value = State.Step(2)
        watchGuidance(2)
        val arm = run.arming.arm()
        log("hudprobe: arm ${arm.describe()}")
        run.arming.traceArm("probe-arm", arm)
        val step2 = frames(run, 2, MARKER_2, "BYDMATE 2", recheck = true)
        logSomeIpStep(2, armed = true, run.startRc, step2, MARKER_2)
        // Step 3 must show the CAN channel alone on the glass.
        watchGuidance(2)
        run.sink?.let { sink -> runCatching { sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, HudProtobufBuilder.buildClearFrame(0)) } }

        _state.value = State.Step(3)
        watchGuidance(3)
        run.canShown = true
        val sent = run.can.show(HudCanChannel.TURN_LEFT, MARKER_3, "BYDMATE 3")
        log("hudprobe: step=3 chan=can ${sent.describe()} marker=${MARKER_3}m")
        Trace.event(
            TraceArea.HUD, "probe-step", "step" to 3, "chan" to "can", "icon" to HudArming.rc(sent.iconRc),
            "ahead" to HudArming.rc(sent.iconAheadRc), "dist" to HudArming.rc(sent.distRc), "road" to HudArming.rc(sent.roadRc),
            "rb-icon" to sent.icon.toString(), "rb-dist" to sent.dist.toString(), "marker" to MARKER_3,
        )
        var heldMs = 0L
        while (heldMs < stepMs) {
            delay(GUIDANCE_LOOK_MS.coerceAtMost(stepMs - heldMs))
            heldMs += GUIDANCE_LOOK_MS
            watchGuidance(3)
            watchSpeed(run, 3, GUIDANCE_LOOK_MS)
            if (heldMs < stepMs && heldMs % HudArming.CHECK_PERIOD_MS == 0L) {
                recheck(run)
                run.can.show(HudCanChannel.TURN_LEFT, MARKER_3, "BYDMATE 3")
            }
        }
    }

    private suspend fun census(run: Run, gateway: Boolean) {
        val sdk = when (helperClient.hudNaviStatus(HelperBinderProtocol.HUD_NAVI_PROBE)?.outcome) {
            HelperBinderProtocol.HUD_NAVI_CALLED -> "present"
            HelperBinderProtocol.HUD_NAVI_ABSENT -> "absent"
            HelperBinderProtocol.HUD_NAVI_THREW -> "error"
            else -> "unknown"
        }
        val v = run.arming.read(HUD_TYPE, HudArming.NAVI, HudArming.SCREEN, HudArming.CAN_NAVI, HudArming.ISA, HudArming.CLUSTER)
        val gatewayWord = if (gateway) "present" else "absent"
        log(
            "hudprobe: census fw=$fingerprint gateway=$gatewayWord sdk_navi=$sdk hudType(951058453@1023)=${v[0]} " +
                "navi(1138753594@1007)=${v[1]} screen(1276174357@1023)=${v[2]} canNavi(1083203624@1014)=${v[3]} " +
                "isa(1262485592@1014)=${v[4]} cluster(1086337074@1007)=${v[5]}"
        )
        Trace.event(
            TraceArea.HUD, "probe-census", "fw" to fingerprint, "gateway" to gatewayWord, "sdk" to sdk, "hudtype" to "${v[0]}",
            "navi" to "${v[1]}", "screen" to "${v[2]}", "can" to "${v[3]}", "isa" to "${v[4]}", "cluster" to "${v[5]}",
        )
    }

    /** The product's bound gateway when the projection is on; otherwise a binding of our own. */
    private suspend fun openGateway(run: Run, gateway: Boolean) {
        if (!gateway) { run.startRc = "absent"; return }
        hudController.boundBridge?.let { run.sink = it; run.startRc = "shared"; return }
        val bridge = bridgeFactory(context)
        run.ownBridge = bridge
        if (withTimeoutOrNull(BIND_TIMEOUT_MS) { bridge.bind() } != true) { run.startRc = "unbound"; return }
        val rc = bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI)
        run.startRc = rc.toString()
        if (rc >= 0) run.sink = bridge
    }

    /** One step of SOME/IP frames at the product's pace; returns how often each rc came back. */
    private suspend fun frames(run: Run, step: Int, marker: Int, road: String, recheck: Boolean): Map<Int, Int> {
        val rcs = sortedMapOf<Int, Int>()
        val frame = HudProtobufBuilder.buildFrameSafe(
            maneuverGaode = GAODE_TURN, distanceMeters = marker, road = road, etaString = null,
            totalDistMeters = 0, speedLimit = 0, maneuverIconPng = HudIconLoader.iconFor(GAODE_TURN),
            speedSignPng = null,
        )
        var elapsedMs = 0L
        var sinceCheckMs = 0L
        while (elapsedMs < stepMs) {
            watchGuidance(step)
            run.sink?.let { sink ->
                val rc = runCatching { sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, frame) }.getOrDefault(FIRE_THREW)
                rcs[rc] = (rcs[rc] ?: 0) + 1
            }
            delay(HudPushLoop.PERIOD_MS)
            elapsedMs += HudPushLoop.PERIOD_MS
            sinceCheckMs += HudPushLoop.PERIOD_MS
            watchSpeed(run, step, HudPushLoop.PERIOD_MS)
            if (recheck && sinceCheckMs >= HudArming.CHECK_PERIOD_MS) {
                recheck(run)
                sinceCheckMs = 0L
            }
        }
        return rcs
    }

    /** The product's 5 s look, so the check keeps the status up exactly like a route would. */
    private suspend fun recheck(run: Run) {
        val c = run.arming.recheck()
        val rearm = c.rearm ?: return
        log("hudprobe: re-arm navi=${c.status.navi} screen=${c.status.screen}")
        run.arming.traceArm("probe-rearm", rearm)
    }

    /** [routeStep]: the step a route started in, null when the check ran out or failed.
     *  [movingStep]: the step the car moved off in; it gets the full restore. */
    private suspend fun restore(run: Run, routeStep: Int?, movingStep: Int?) {
        _state.value = State.Restoring
        logAborted("guidance", routeStep)
        logAborted("moving", movingStep)
        if (run.canShown) {
            val cleared = runCatching { run.can.clear().describe() }.getOrElse { "failed ${it.javaClass.simpleName}" }
            log("hudprobe: can clear $cleared")
        }
        // The route's frames and arming are the projection's now: no clear frame under them, no
        // closing of the status they hold; its first arm takes the kept as-found. Without a
        // running projection nobody would ever close them, so the full restore runs.
        val handOver = routeStep != null && hudController.armingLive
        if (!handOver) {
            run.sink?.let { sink -> runCatching { sink.fireEvent(HudSomeIpBridge.TOPIC_NAVI, HudProtobufBuilder.buildClearFrame(0)) } }
        }
        var deferred = false
        val restored = if (handOver) {
            Trace.event(TraceArea.HUD, "probe-restore", "handover" to true, "armed" to run.arming.armed)
            "handover armed=${run.arming.armed} asfound=${run.arming.asFound ?: "na"}"
        } else if (run.arming.armed) {
            runCatching { run.arming.disarm() }.fold(
                onSuccess = { r ->
                    deferred = r.screenDeferred
                    run.arming.traceDisarm("probe-restore", r)
                    r.describe()
                },
                onFailure = {
                    Trace.event(TraceArea.HUD, "probe-restore", "error" to it.javaClass.simpleName, "ok" to false)
                    "failed ${it.javaClass.simpleName} ok=false"
                },
            )
        } else {
            Trace.event(TraceArea.HUD, "probe-restore", "navi" to "skipped", "ok" to true)
            "navi rc=skipped screen=na rc=skipped ok=true"
        }
        log("hudprobe: restore $restored")
        run.ownBridge?.let { bridge ->
            // A projection switched on meanwhile shares the gateway service: leave it running.
            if (hudController.boundBridge == null) runCatching { bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI) }
            runCatching { bridge.unbind() }
        }
        // Still the check's layout: the product's loop keeps off it until the retry is over.
        val routeInRetry = deferred && retryDeferredLayout(run)
        hudController.armingPaused = false
        _state.value = outcome(routeStep != null || routeInRetry, movingStep)
    }

    /** The check's last state: a route that started, in the steps or during the layout retry,
     *  wins over moving off. */
    private fun outcome(routeStarted: Boolean, movingStep: Int?): State = when {
        routeStarted -> State.RouteStarted
        movingStep != null -> State.Refused(Refusal.MOVING)
        else -> State.Done
    }

    /** A layout the restore left at a fullscreen cluster: looked at every 5 s for up to
     *  [DEFERRED_RETRY_MS]. Still fullscreen then, the kept as-found goes back at the next start.
     *  A route that starts meanwhile ends the retry at once and gets the kept as-found (true then),
     *  but only when the product's own arming loop is live to take that route over; without it the
     *  retry is the only thing that will ever put the layout back, so it runs its full course. */
    private suspend fun retryDeferredLayout(run: Run): Boolean {
        var waitedMs = 0L
        while (waitedMs < DEFERRED_RETRY_MS) {
            delay(GUIDANCE_LOOK_MS)
            waitedMs += GUIDANCE_LOOK_MS
            if (hudController.armingLive && guidanceActive()) {
                log("hudprobe: layout retry ended by a route, as-found kept")
                return true
            }
            if (waitedMs % HudArming.CHECK_PERIOD_MS != 0L) continue
            if (runCatching { run.arming.retryDeferred() }.getOrDefault(false)) return false
        }
        log("hudprobe: layout still deferred after ${DEFERRED_RETRY_MS / 1_000} s, kept for the next start")
        return false
    }

    /** The line and trace event of a check that a route or the speed ended in [step]. */
    private fun logAborted(reason: String, step: Int?) {
        step ?: return
        log("hudprobe: aborted reason=$reason step=$step")
        Trace.event(TraceArea.HUD, "probe-aborted", "reason" to reason, "step" to step)
    }

    private fun logSomeIpStep(step: Int, armed: Boolean, startRc: String, rcs: Map<Int, Int>, marker: Int) {
        val fired = histogram(rcs)
        log("hudprobe: step=$step chan=someip-ui7 armed=$armed start_rc=$startRc fire_rc=$fired marker=${marker}m")
        Trace.event(
            TraceArea.HUD, "probe-step", "step" to step, "chan" to "someip-ui7", "armed" to armed,
            "start" to startRc, "fire" to fired, "marker" to marker,
        )
    }

    /** `{0:66,1:1}`: each fireEvent rc with its count, the form the log line promises. */
    private fun histogram(rcs: Map<Int, Int>): String =
        rcs.entries.joinToString(",", prefix = "{", postfix = "}") { "${it.key}:${it.value}" }

    /** The speed through the daemon, rounded up like the other speed gates; null when unknown. */
    private suspend fun readSpeed(): Int? {
        val address = FidAddresses.of("speed")
        val (status, word) = helperClient.readBatch(listOf(BatchReadItem(tx = 7, dev = address.device, fid = address.fid)))
            ?.singleOrNull() ?: return null
        if (status != 0) return null
        return SentinelDecoder.parseFloatFromShellInt(word)?.let { ceil(it).toInt() }
    }

    companion object {
        private const val TAG = "HudCheck"
        const val STEPS = 3
        const val STEP_MS = 20_000L
        const val MAX_SPEED_KMH = 5
        const val MARKER_1 = 111
        const val MARKER_2 = 222
        const val MARKER_3 = 333
        /** A plain turn arrow for the SOME/IP steps (gaode code, f28 draws its chevron). */
        private const val GAODE_TURN = 2
        /** rc slot of a fireEvent that threw instead of answering. */
        private const val FIRE_THREW = -3
        /** Our own gateway binding may retry for over a minute; the check cannot wait that long. */
        private const val BIND_TIMEOUT_MS = 15_000L
        /** How often the CAN step looks for a route between its 5 s re-sends. */
        private const val GUIDANCE_LOOK_MS = 1_000L
        /** How often the steps read the speed. */
        private const val SPEED_LOOK_MS = 1_000L
        /** How long the restore waits for a fullscreen cluster to let the layout back. */
        private const val DEFERRED_RETRY_MS = 60_000L
        /** SET_HUD_CONFIG: 1 = W-HUD, 2 = AR-HUD (carsetting HudFuncVisibleUtils). */
        val HUD_TYPE = 1023 to 951058453
    }
}
