package com.bydmate.app.hud

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The car's own HUD navigation status (HUD wave 1, #198). BYD's instrument draws navigation hints
 * on the glass only while that status is up. OpenBYD raises it with NAVI_STATUS = 2, the HUD
 * layout = 3 and the CAN navi / ISA map statuses = 1, and the firmware lets it drop within a
 * minute or two (#198, Han L: the glass held only while the call repeated every 5 s), so the
 * product re-checks it every [CHECK_PERIOD_MS] while a route is guided.
 *
 * NAVI_STATUS goes out through the SDK call sendAutoNaviStatus inside the daemon first (from the
 * app uid it is a SecurityException); the raw fid write is the fallback when the call is absent,
 * refused, or the daemon is too old to know it, and always while the cluster is fullscreen (rear
 * camera, [CLUSTER] reads 4): on some firmwares the call moves the layout itself. The layout is
 * the one value that stays in the car's settings after the route, so it is read before the first
 * write, kept in [prefs] until it is back (the process dies at ignition off mid-route), and
 * restored by [disarm]. It is never written while the cluster is fullscreen and never when the
 * value found could not be put back. canNavi and isa cannot be read back to an original, so
 * [disarm] sets them to 0 like OpenBYD's stop.
 *
 * One instance per owner: the product runs [start]/[stop], the HUD check drives
 * [arm]/[recheck]/[disarm] itself.
 */
@Suppress("TooManyFunctions") // arm, re-check, disarm, the loop around them and their log lines, kept in one place
class HudArming(
    private val helper: HelperClient,
    private val prefs: SharedPreferences? = null,
) {
    /** One fid read through the daemon: [status] null = no answer, 0 = [raw] is the value. */
    data class FidRead(val status: Int?, val raw: Int = 0) {
        val value: Int? get() = if (status == 0) raw else null

        /** Log form: the value, `e<status>` for an autoservice error, `na` without an answer. */
        override fun toString(): String = when (status) {
            null -> "na"
            0 -> raw.toString()
            else -> "e$status"
        }
    }

    /** What [recheck] and [arm] decide on. */
    data class Status(val navi: FidRead, val screen: FidRead, val cluster: FidRead)

    /** One arm: how NAVI_STATUS went out, each write's raw status, what the car reads after. */
    data class ArmReport(
        val via: String,
        val naviRc: Int?,
        val screenSkipped: Boolean,
        val screenRc: Int?,
        val canNaviRc: Int?,
        val isaRc: Int?,
        val navi: FidRead,
        val screen: FidRead,
        val canNavi: FidRead,
        val isa: FidRead,
    ) {
        fun describe(): String =
            "via=$via navi rc=${rc(naviRc)} screen rc=${if (screenSkipped) "skipped" else rc(screenRc)} " +
                "canNavi rc=${rc(canNaviRc)} isa rc=${rc(isaRc)} " +
                "readback navi=$navi screen=$screen canNavi=$canNavi isa=$isa"
    }

    /** One disarm: NAVI_STATUS = 4, canNavi and isa = 0, the layout put back to [asFound]
     *  (null = never known). */
    data class DisarmReport(
        val via: String,
        val naviRc: Int?,
        val asFound: Int?,
        val screenSkipped: Boolean,
        val screenRc: Int?,
        /** The layout needed putting back but the cluster was fullscreen: left for later. */
        val screenDeferred: Boolean,
        val canNaviRc: Int?,
        val isaRc: Int?,
        val navi: FidRead,
        val screen: FidRead,
        val canNavi: FidRead,
        val isa: FidRead,
        val ok: Boolean,
    ) {
        fun describe(): String =
            "navi rc=${rc(naviRc)} screen=${asFound ?: "na"} rc=${screenWord()} " +
                "ok=$ok canNavi rc=${rc(canNaviRc)} isa rc=${rc(isaRc)} via=$via " +
                "readback navi=$navi screen=$screen canNavi=$canNavi isa=$isa"

        internal fun screenWord(): String = when {
            screenDeferred -> "deferred"
            screenSkipped -> "skipped"
            else -> rc(screenRc)
        }
    }

    /** One periodic look: the status read, and the arm it triggered (null = it held). */
    data class Recheck(val status: Status, val rearm: ArmReport?)

    /** True from the first [arm] until [disarm]. */
    @Volatile var armed: Boolean = false
        private set

    /** Layout found before this session's first write; null = unknown, so never written. */
    @Volatile var asFound: Int? = null
        private set

    /** Where the product lines go; tests collect them. */
    internal var log: (String) -> Unit = { Log.i(TAG, it) }

    /** The loop's hook right before a disarm (a kept layout's retry included), while the status is
     *  still up: ways 2 and 3 blank their CAN fields and stop their family there
     *  ([HudWayChannels.close]). */
    internal var beforeDisarm: suspend () -> Unit = {}

    @Volatile private var outdatedLogged = false
    private var job: Job? = null
    private var rearms = 0
    private var lastRearm: Pair<String, String>? = null
    private var sameRearms = 0
    // Set once a deferred layout's retry ran with the cluster free; the next arm clears it.
    private var deferredDone = false

    suspend fun readStatus(): Status {
        val r = read(NAVI, SCREEN, CLUSTER)
        return Status(r[0], r[1], r[2])
    }

    /** Raises the status. The first call of a session reads and keeps the layout it will restore. */
    suspend fun arm(status: Status? = null): ArmReport {
        val s = status ?: readStatus()
        if (!armed) {
            // A layout kept by a session that never reached its disarm (process killed at ignition
            // off) is the real original: the car still holds our 3.
            asFound = prefs?.takeIf { it.contains(KEY_AS_FOUND) }?.getInt(KEY_AS_FOUND, 0)
                ?: s.screen.value?.takeIf { it in 0..MAX_LAYOUT }
            asFound?.let { prefs?.edit()?.putInt(KEY_AS_FOUND, it)?.apply() }
            armed = true
        }
        // On some firmwares the SDK call moves the layout itself (#198, Han L), so it stays out
        // while the cluster is fullscreen, like the raw layout write.
        var via = VIA_FID
        var naviRc: Int? = null
        if (s.cluster.value != CLUSTER_FULLSCREEN) {
            val sdk = sdkNaviStatus(NAVI_ARMED)
            if (sdk?.accepted == true) { via = VIA_SDK; naviRc = sdk.sdkReturn }
        }
        if (via == VIA_FID) naviRc = helper.writeStatus(NAVI.first, NAVI.second, NAVI_ARMED)
        val layout = layoutWritable(s)
        val screenRc = if (layout) helper.writeStatus(SCREEN.first, SCREEN.second, LAYOUT_NAVI) else null
        val canNaviRc = helper.writeStatus(CAN_NAVI.first, CAN_NAVI.second, 1)
        val isaRc = helper.writeStatus(ISA.first, ISA.second, 1)
        val rb = read(NAVI, SCREEN, CAN_NAVI, ISA)
        return ArmReport(via, naviRc, !layout, screenRc, canNaviRc, isaRc, rb[0], rb[1], rb[2], rb[3])
    }

    /** The periodic look: re-arms when NAVI_STATUS fell out of 2 / 621, or the layout is off 3
     *  where it may be written. */
    suspend fun recheck(): Recheck {
        val s = readStatus()
        return Recheck(s, if (needsRearm(s)) arm(s) else null)
    }

    /** NAVI_STATUS = 4, the layout back to what [arm] found, then canNavi and isa = 0 (OpenBYD's
     *  stop: their original values cannot be read, 0 is what the car runs with when nobody
     *  navigates). While the cluster is fullscreen neither the SDK call nor the layout write goes
     *  out: the layout is deferred, and [ok][DisarmReport.ok] counts the three writes alone. Ends
     *  the session either way; a layout that did not come back stays kept for the next start
     *  ([disarmLeftover]) or route to restore. */
    suspend fun disarm(): DisarmReport {
        val (screen, cluster) = read(SCREEN, CLUSTER)
        val fullscreen = cluster.value == CLUSTER_FULLSCREEN
        var via = VIA_FID
        var naviRc: Int? = null
        if (!fullscreen) {
            val sdk = sdkNaviStatus(NAVI_CLOSED)
            if (sdk?.accepted == true) { via = VIA_SDK; naviRc = sdk.sdkReturn }
        }
        if (via == VIA_FID) naviRc = helper.writeStatus(NAVI.first, NAVI.second, NAVI_CLOSED)
        val target = asFound
        val needed = target != null && screen.value != target
        val restore = needed && !fullscreen
        val screenRc = target?.takeIf { restore }?.let { helper.writeStatus(SCREEN.first, SCREEN.second, it) }
        val canNaviRc = helper.writeStatus(CAN_NAVI.first, CAN_NAVI.second, 0)
        val isaRc = helper.writeStatus(ISA.first, ISA.second, 0)
        val rb = read(NAVI, SCREEN, CAN_NAVI, ISA)
        val screenBack = target == null || rb[1].value == target
        if (screenBack) prefs?.edit()?.remove(KEY_AS_FOUND)?.apply()
        armed = false
        asFound = null
        val deferred = needed && fullscreen
        val ok = listOf(naviRc, canNaviRc, isaRc).all { it != null && it >= 0 } && (screenBack || deferred)
        return DisarmReport(
            via, naviRc, target, !restore, screenRc, deferred, canNaviRc, isaRc, rb[0], rb[1], rb[2], rb[3], ok,
        )
    }

    /**
     * What a session killed before its disarm left behind (process death at ignition off
     * mid-route): the kept layout in [prefs] says our values are still up. Undone once, the same
     * way as [disarm], when the HUD starts and no route is [guided]; a guided route arms and
     * disarms it itself. Null when there was nothing to undo or it was left to the route.
     */
    suspend fun disarmLeftover(guided: Boolean): DisarmReport? {
        if (prefs?.contains(KEY_AS_FOUND) != true || guided) return null
        return disarmKept(REASON_LEFTOVER)
    }

    /** The kept layout's disarm, logged with the [reason] that brought it. */
    private suspend fun disarmKept(reason: String): DisarmReport {
        asFound = prefs?.getInt(KEY_AS_FOUND, 0)
        val r = disarm()
        log("hud disarm: reason=$reason ${r.describe()}")
        traceDisarm("disarm-$reason", r)
        return r
    }

    /** Loop side of a layout deferred at a fullscreen cluster: looked at every [CHECK_PERIOD_MS]
     *  while no route is guided, silent (one cluster read) while the cluster stays fullscreen,
     *  then the disarm once. A retry that fails otherwise is not repeated until the next arm.
     *  The HUD check retries its own restore the same way; false = the layout still waits. */
    internal suspend fun retryDeferred(): Boolean {
        if (read(CLUSTER)[0].value == CLUSTER_FULLSCREEN) return false
        if (!disarmKept(REASON_DEFERRED).screenDeferred) deferredDone = true
        return deferredDone
    }

    private fun layoutWaits(): Boolean = !deferredDone && prefs?.contains(KEY_AS_FOUND) == true

    internal fun needsRearm(s: Status): Boolean = s.navi.value !in NAVI_HELD || layoutWritable(s)

    /** The layout is written only where it can be put back, is not 3 yet, and the cluster is
     *  not fullscreen. An unreadable layout is not "not 3": nothing is known about it. */
    private fun layoutWritable(s: Status): Boolean {
        val screen = s.screen.value ?: return false
        return asFound != null && screen != LAYOUT_NAVI && s.cluster.value != CLUSTER_FULLSCREEN
    }

    /** The SDK call; a null answer from a daemon that is alive means it predates the call. */
    private suspend fun sdkNaviStatus(status: Int): HudNaviReply? {
        val reply = helper.hudNaviStatus(status)
        if (reply == null && !outdatedLogged && helper.isAlive()) {
            outdatedLogged = true
            log("hud arm: helper daemon is outdated (no SDK call until it restarts), NAVI_STATUS written raw")
            Trace.event(TraceArea.HUD, "daemon-outdated")
        }
        return reply
    }

    /** Reads (dev, fid) pairs in one daemon round trip, getInt each. */
    internal suspend fun read(vararg fids: Pair<Int, Int>): List<FidRead> {
        val replies = helper.readBatch(fids.map { (dev, fid) -> BatchReadItem(tx = 5, dev = dev, fid = fid) })
        return fids.indices.map { i ->
            replies?.getOrNull(i)?.let { (status, value) -> FidRead(status, value) } ?: FidRead(null)
        }
    }

    /**
     * Product loop: arms when a route starts being guided, re-checks every [CHECK_PERIOD_MS],
     * disarms when guidance ends, and without a route puts back a layout the disarm had to defer
     * ([retryDeferred]). A failed or refused step is logged and retried on the next tick; it
     * never touches the frame loop, which runs on its own. [layoutOwned] false = someone else's
     * session holds the kept layout (the HUD check), so the loop leaves it alone.
     */
    fun start(scope: CoroutineScope, layoutOwned: () -> Boolean = { true }, guidanceActive: () -> Boolean) {
        if (job?.isActive == true) return
        job = scope.launch {
            var sinceCheckMs = 0L
            while (isActive) {
                val guided = runCatching { guidanceActive() }.getOrDefault(false)
                val owned = runCatching { layoutOwned() }.getOrDefault(false)
                val restartClock = runCatching { tick(guided, sinceCheckMs >= CHECK_PERIOD_MS, owned) }
                    .onFailure {
                        if (it is CancellationException) throw it
                        Log.w(TAG, "hud arm: step failed: ${it.javaClass.simpleName}")
                    }
                    .getOrDefault(true)
                delay(POLL_MS)
                sinceCheckMs = if (restartClock) POLL_MS else sinceCheckMs + POLL_MS
            }
        }
    }

    /** One loop step; true when it armed or re-checked, which restarts the check clock. */
    private suspend fun tick(guided: Boolean, checkDue: Boolean, owned: Boolean): Boolean = when {
        guided && !armed -> { onArm(arm()); true }
        guided && checkDue -> { onRecheck(recheck()); true }
        !guided && armed -> { runBeforeDisarm(); onDisarm(disarm()); false }
        !guided && checkDue && owned && layoutWaits() -> { runBeforeDisarm(); retryDeferred(); true }
        else -> false
    }

    /** Stops the loop; a session still armed is disarmed before this returns. */
    suspend fun stop() {
        job?.cancelAndJoin()
        job = null
        if (armed) {
            withContext(NonCancellable) {
                runBeforeDisarm()
                runCatching { onDisarm(disarm()) }
                    .onFailure { Log.w(TAG, "hud disarm: failed: ${it.javaClass.simpleName}") }
            }
        }
    }

    /** A failing hook never keeps the status up. */
    private suspend fun runBeforeDisarm() {
        runCatching { beforeDisarm() }.onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "hud disarm: cleanup before it failed: ${it.javaClass.simpleName}")
        }
    }

    private fun onArm(r: ArmReport) {
        deferredDone = false
        rearms = 0
        lastRearm = null
        sameRearms = 0
        log("hud arm: ${r.describe()} asfound=${asFound ?: "na"}")
        traceArm("arm", r)
    }

    /** Re-arms are logged collapsed: a line when the values that triggered one change. */
    private fun onRecheck(c: Recheck) {
        val r = c.rearm ?: return
        rearms++
        val key = c.status.navi.toString() to c.status.screen.toString()
        if (key == lastRearm) { sameRearms++; return }
        val collapsed = if (sameRearms > 0) " (after $sameRearms same)" else ""
        lastRearm = key
        sameRearms = 0
        log("hud re-arm: navi=${key.first} screen=${key.second}$collapsed")
        traceArm("rearm", r)
    }

    private fun onDisarm(r: DisarmReport) {
        log("hud disarm: ${r.describe()} rearms=$rearms")
        traceDisarm("disarm", r)
    }

    /** The trace twin of an arm line; the HUD check writes its own under another name. */
    internal fun traceArm(what: String, r: ArmReport) {
        Trace.event(
            TraceArea.HUD, what, "via" to r.via, "navi" to rc(r.naviRc),
            "screen" to if (r.screenSkipped) "skipped" else rc(r.screenRc),
            "can" to rc(r.canNaviRc), "isa" to rc(r.isaRc),
            "rb-navi" to r.navi.toString(), "rb-screen" to r.screen.toString(),
            "rb-can" to r.canNavi.toString(), "rb-isa" to r.isa.toString(),
        )
    }

    internal fun traceDisarm(what: String, r: DisarmReport) {
        Trace.event(
            TraceArea.HUD, what, "via" to r.via, "navi" to rc(r.naviRc), "screen" to (r.asFound ?: "na"),
            "screen-rc" to r.screenWord(), "ok" to r.ok, "rearms" to rearms,
            "can" to rc(r.canNaviRc), "isa" to rc(r.isaRc),
            "rb-navi" to r.navi.toString(), "rb-screen" to r.screen.toString(),
            "rb-can" to r.canNavi.toString(), "rb-isa" to r.isa.toString(),
        )
    }

    companion object {
        private const val TAG = "HudArming"

        /** (dev, fid) of every status this class reads or writes. */
        val NAVI = 1007 to 1138753594        // INSTRUMENT_SEND_NAVI_STATUS: 2 open, 4 closed
        val SCREEN = 1023 to 1276174357      // SET_NAVI_SCREEN_STATUS: HUD layout, 3 = navigation
        val CAN_NAVI = 1014 to 1083203624    // CAN_NAVI_STATUS
        val ISA = 1014 to 1262485592         // STATISTICS_ISA_MAP_STATUS
        val CLUSTER = 1007 to 1086337074     // cluster display mode, 4 = fullscreen

        const val NAVI_ARMED = 2
        const val NAVI_CLOSED = 4
        /** OpenBYD re-arms on anything else: 621 is the status the SOME/IP path reports. */
        val NAVI_HELD = setOf(NAVI_ARMED, 621)
        const val LAYOUT_NAVI = 3
        const val CLUSTER_FULLSCREEN = 4
        /** Upper bound of a layout worth putting back: an enum; 65535 and friends are sentinels. */
        const val MAX_LAYOUT = 10

        const val VIA_SDK = "sdk"
        const val VIA_FID = "fid"
        private const val REASON_LEFTOVER = "leftover"
        private const val REASON_DEFERRED = "deferred"

        const val POLL_MS = 1_000L
        const val CHECK_PERIOD_MS = 5_000L

        /** HUD prefs key of the layout found before an unfinished session. */
        const val KEY_AS_FOUND = "hud_layout_as_found"

        internal fun rc(value: Int?): String = value?.toString() ?: "na"
    }
}
