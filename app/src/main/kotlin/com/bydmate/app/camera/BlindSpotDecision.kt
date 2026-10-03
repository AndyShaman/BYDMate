package com.bydmate.app.camera

/** Which blind-spot window is on screen; NONE = both hidden. */
enum class BlindSpotSide { NONE, LEFT, RIGHT }

/**
 * One tick of the fast telemetry loop.
 *
 * [blink] is the raw turn-signal mask (fid 950009900, live Leopard 3 2026-07-31):
 * 1=off, 2=left, 4=right, 6=hazard; null when the read failed.
 * [telemetryAgeMs] is the age of the last snapshot where all three signals read cleanly.
 * [nativeCameraForeground] is true while the BYD camera surface (com.byd.avc) holds the screen.
 */
data class BlindSpotInput(
    val blink: Int?,
    val speedKmh: Float?,
    val gearIsReverse: Boolean,
    val thresholdKmh: Int,
    val telemetryAgeMs: Long,
    val nativeCameraForeground: Boolean,
)

data class BlindSpotDecision(
    val show: BlindSpotSide,
    /** Keep the AVM camera open (previews bound, windows at alpha 0) so a show is instant. */
    val cameraWarm: Boolean,
)

/** Beyond this the telemetry is not trusted and both windows go down. */
const val BLIND_SPOT_WATCHDOG_MS = 750L

/** The camera stays warm this far below the show threshold, so slowing down does not
 *  flap the open/close of the vendor stack. */
const val BLIND_SPOT_WARM_HYSTERESIS_KMH = 5

/**
 * Pure decision for one tick. Reverse gear wins over everything: the factory rear view owns
 * the screens there, so the pipeline fully closes. Stale telemetry only hides the window —
 * cooling the camera down is the controller's job (it holds the 10 s timer), and doing it here
 * would tear the stack down on a single missed read.
 *
 * The factory 360 view owns the screen while it is up (it pops up on its own below 15 km/h and
 * goes away above 30 km/h on some cars), so our windows would only overlap it: they go down for
 * as long as it is in the foreground, and the camera goes cold with them. A warm camera under it
 * kept two previews streaming and the 360 stuttered until BYDMate was killed (tester dump
 * 2026-10-03); once the 360 is gone the camera warms again by the speed rule, as after reverse.
 */
fun decideBlindSpot(input: BlindSpotInput): BlindSpotDecision {
    val speed = input.speedKmh
    val warm = !input.gearIsReverse && !input.nativeCameraForeground && speed != null &&
        speed >= input.thresholdKmh - BLIND_SPOT_WARM_HYSTERESIS_KMH
    val show = when {
        input.gearIsReverse -> BlindSpotSide.NONE
        input.telemetryAgeMs > BLIND_SPOT_WATCHDOG_MS -> BlindSpotSide.NONE
        input.nativeCameraForeground -> BlindSpotSide.NONE
        speed == null || speed < input.thresholdKmh -> BlindSpotSide.NONE
        // Anything outside the two single-side masks (off, hazard, the transient 9 seen on
        // the push channel) means "no blind-spot view".
        input.blink == BLINK_LEFT -> BlindSpotSide.LEFT
        input.blink == BLINK_RIGHT -> BlindSpotSide.RIGHT
        else -> BlindSpotSide.NONE
    }
    return BlindSpotDecision(show, warm)
}

private const val BLINK_LEFT = 2
private const val BLINK_RIGHT = 4

/** Gear value that means R (DiParsData: 1=P, 2=R, 3=N, 4=D). */
const val BLIND_SPOT_GEAR_REVERSE = 2

/**
 * Should the fast loop run at all? Answered from the 1 s main poll: the loop, the daemon
 * traffic and the camera only exist while the feature is on, the car is out of reverse and
 * within the warm band below the show threshold.
 */
fun blindSpotArmed(enabled: Boolean, gear: Int?, speedKmh: Int?, thresholdKmh: Int): Boolean =
    enabled && gear != BLIND_SPOT_GEAR_REVERSE && speedKmh != null &&
        speedKmh >= thresholdKmh - BLIND_SPOT_WARM_HYSTERESIS_KMH

/**
 * Human-readable reason [blindSpotArmed] is false, for the dump: which of the three gates
 * (switch, gear, speed) is holding the pipeline down, or "ok" once armed.
 */
fun blindSpotArmedReason(enabled: Boolean, gear: Int?, speedKmh: Int?, thresholdKmh: Int): String = when {
    !enabled -> "disabled"
    gear == BLIND_SPOT_GEAR_REVERSE -> "reverse"
    speedKmh == null -> "no speed"
    speedKmh < thresholdKmh - BLIND_SPOT_WARM_HYSTERESIS_KMH ->
        "speed $speedKmh < ${thresholdKmh - BLIND_SPOT_WARM_HYSTERESIS_KMH}"
    else -> "ok"
}

/** A shown window whose vendor stream has been silent this long is frozen, not idle. */
const val BLIND_SPOT_STALL_MS = 1_500L

/**
 * True when a window is shown, has already delivered honest frames, and the vendor stream has
 * gone silent for [stallMs]. The vendor stack can stop filling the surface mid-drive while the
 * camera stays open (field 2026-08-25): the TextureView then holds the last buffer, so every
 * following blinker shows a frozen picture until the pipeline is torn down and reopened.
 * Only a SHOWN window is evidence — a hidden one is off screen and may legitimately not be
 * redrawn.
 */
fun blindSpotFrameStalled(
    shown: Boolean,
    hasValidFrame: Boolean,
    lastFrameAt: Long,
    now: Long,
    stallMs: Long = BLIND_SPOT_STALL_MS,
): Boolean = shown && hasValidFrame && lastFrameAt > 0L && now - lastFrameAt >= stallMs

/** One decoded fast-loop read; a field is null when the fid answered a sentinel or the read failed. */
data class BlindSpotSample(val blink: Int?, val speedKmh: Float?, val gear: Int?) {
    /** All three signals present. A missing gear is NOT "not reverse", so a snapshot without
     *  it cannot be trusted to keep the camera open. */
    val isValid: Boolean get() = blink != null && speedKmh != null && gear != null
}

data class BlindSpotTelemetryState(
    /** Age of the last fully valid snapshot; [Long.MAX_VALUE] while there has never been one. */
    val ageMs: Long,
    /** The channel is gone, not hiccuping: close the camera and the windows outright. */
    val mustClose: Boolean,
    /** Last snapshot that read cleanly, or null while none has. */
    val lastValid: BlindSpotSample?,
)

/** Telemetry is unusable after this long without a fully valid snapshot. */
const val BLIND_SPOT_TELEMETRY_LOST_MS = 3_000L

/** Two dead batch reads in a row mean the daemon channel is down, not busy. */
const val BLIND_SPOT_MAX_READ_FAILURES = 2

/**
 * Freshness bookkeeping for the fast loop, kept out of the controller so it can be tested
 * without Android. [onSample] is called once per tick with the decoded sample, or null when
 * the batch read itself failed.
 */
class BlindSpotTelemetryGate {
    private var lastValidAt = 0L
    private var armedAt = 0L

    /** Batch reads that failed in a row; read by the controller for the loss log line. */
    var readFailures = 0
        private set
    private var lastValid: BlindSpotSample? = null

    /** Called when the loop starts, so the lost-telemetry timer counts from the arming point. */
    fun reset(now: Long) {
        lastValidAt = 0L
        armedAt = now
        readFailures = 0
        lastValid = null
    }

    fun onSample(sample: BlindSpotSample?, now: Long): BlindSpotTelemetryState {
        if (sample == null) readFailures++ else readFailures = 0
        if (sample != null && sample.isValid) {
            lastValidAt = now
            lastValid = sample
        }
        val ageMs = when {
            lastValidAt != 0L -> now - lastValidAt
            else -> Long.MAX_VALUE
        }
        // Before the first valid snapshot the loss timer runs from the arming point — a channel
        // that never answers must close the pipeline just like one that went quiet.
        val silentFor = if (lastValidAt != 0L) now - lastValidAt else now - armedAt
        val mustClose = readFailures >= BLIND_SPOT_MAX_READ_FAILURES ||
            silentFor > BLIND_SPOT_TELEMETRY_LOST_MS
        return BlindSpotTelemetryState(ageMs, mustClose, lastValid)
    }
}

/** Screen a blind-spot camera window goes to. */
enum class BlindSpotScreen { MAIN, CLUSTER }

/**
 * Where [side]'s camera goes. With a cluster panel the default is left on the cluster (that is
 * where the driver looks before a left lane change) and right on the main screen; [bothOnMain]
 * (#183) keeps both on the main screen for the drivers whose cluster is better left alone,
 * [bothOnCluster] (#240) puts both on the cluster and leaves the main screen free. The settings
 * card keeps the two opt-ins exclusive; should both be set anyway, [bothOnMain] wins, since it
 * never touches the cluster compositor. Cars without a projection display have no choice.
 */
fun blindSpotScreen(
    side: BlindSpotSide,
    bothOnMain: Boolean,
    bothOnCluster: Boolean,
    hasClusterDisplay: Boolean,
): BlindSpotScreen = when {
    !hasClusterDisplay || bothOnMain -> BlindSpotScreen.MAIN
    bothOnCluster || side == BlindSpotSide.LEFT -> BlindSpotScreen.CLUSTER
    else -> BlindSpotScreen.MAIN
}

/** The rule [blindSpotScreen] routed by, for the log. */
fun blindSpotRoutingReason(bothOnMain: Boolean, bothOnCluster: Boolean, hasClusterDisplay: Boolean): String =
    when {
        !hasClusterDisplay -> "no_cluster_display"
        bothOnMain -> "both_on_main"
        bothOnCluster -> "both_on_cluster"
        else -> "default"
    }

/**
 * Whether the shown window covers the main screen, where the floating widget lives: each side
 * only while its window sits there ([blindSpotScreen]) rather than on the cluster panel.
 */
fun blindSpotCoversMainScreen(
    side: BlindSpotSide,
    leftOnMainScreen: Boolean,
    rightOnMainScreen: Boolean,
): Boolean = when (side) {
    BlindSpotSide.LEFT -> leftOnMainScreen
    BlindSpotSide.RIGHT -> rightOnMainScreen
    BlindSpotSide.NONE -> false
}
