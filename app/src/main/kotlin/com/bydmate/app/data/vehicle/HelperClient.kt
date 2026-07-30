package com.bydmate.app.data.vehicle

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.Surface
import java.io.ByteArrayOutputStream
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One autoservice read request for HelperClient.readBatch: transact code (5=getInt, 7=getFloat bits), device, fid. */
data class BatchReadItem(val tx: Int, val dev: Int, val fid: Int)

/**
 * Windowing state of a running task as reported by TX_GET_TASK_STATE.
 * [taskId] == -1 means the package has no running task (other fields are 0 in that case).
 * [displayId] is the Android display the task lives on: 0 = main screen, 2 = cluster fission
 * display on Leopard 3. Defaults to 0 when talking to an old daemon that does not send the 7th
 * int — the "departed" branch in SplitSessionManager is gated on displayId != 0, so it
 * auto-disarms against old daemons without any other configuration.
 */
data class SplitTaskState(
    val taskId: Int,
    val windowingMode: Int,
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val displayId: Int = 0,
)

/** Result of the daemon's direct freeform launch (TX_LAUNCH_FREEFORM). */
enum class FreeformLaunchResult { OK, UNAVAILABLE, FAILED }

/**
 * Outcome of TX_RAISE_FREEFORM_TASK, distinguishing the two failure modes the plain Boolean
 * [HelperClient.raiseFreeformTask] collapses into `false` (W6-F1 FIX-A):
 *
 *  - [REFUSED] — the daemon replied with a non-accepted status: it handled the TX but the
 *    `am start` raise did not run cleanly.
 *  - [NO_REPLY] — no usable reply at all: the daemon does not know the TX (pre-Task-N build),
 *    the binder call timed out, or the binder was dead.
 *  - [RAISED] — the daemon confirmed the raise command ran without error.
 *
 * Declaration order is deliberate: the conservative outcome comes first, so any default-valued
 * or partially initialised instance degrades to "treat as failure" rather than to a false success.
 */
enum class RaiseOutcome { REFUSED, NO_REPLY, RAISED }

/**
 * Snapshot of the top root task on display 0 as reported by TX_GET_TOP_TASK (Q3 / F-3).
 * [pkg] is the package name (from topActivity/baseActivity ComponentName).
 * [windowingMode] and [activityType] come from WindowConfiguration.
 * [displayId] is the Android display the task lives on: 0 = main, 2 = cluster fission on L3.
 * Null from [HelperClient.getTopTask] when the daemon is unreachable, too old (pre-Q3 daemon
 * makes transact return false → null), or no running task is found.
 */
data class TopTaskInfo(
    val pkg: String,
    val taskId: Int,
    val windowingMode: Int,
    val activityType: Int,
    val displayId: Int,
)

/**
 * Result of [HelperClient.dumpFids]. Distinguishes three failure kinds so
 * callers can surface honest error messages.
 */
sealed class DumpFidsResult {
    /** Dump assembled successfully from all chunks. */
    data class Success(val dump: String) : DumpFidsResult()

    /** Binder transact could not be completed on the first chunk — daemon unreachable,
     *  timed out, rejected (old daemon pre-Q4), or dead. */
    object BinderAbsent : DumpFidsResult()

    /** Daemon returned a non-zero status code, or chunked assembly was aborted mid-way
     *  (transact failed after at least one successful chunk). [detail] is a short
     *  technical description (e.g. "status=-1 at chunk 0" or "transact failed at chunk 2"). */
    data class ReadError(val detail: String) : DumpFidsResult()
}

/**
 * Client for the in-vehicle helper daemon registered as the `bydmate_helper`
 * binder service (ServiceManager.getService + IBinder.transact).
 *
 * read()/write()/isAlive() return null/false on any failure: daemon not
 * registered, dead binder, transact rejected by the daemon's uid gate, or
 * an autoservice error. Callers must treat null/false as "channel
 * unavailable, retry later".
 *
 * The hidden-API exemption for android.os.ServiceManager is installed in
 * BYDMateApp.onCreate — do NOT add HiddenApiBypass here.
 */
interface HelperClient {
    suspend fun read(dev: Int, fid: Int, tx: Int = 5): Long?

    /**
     * Reads all [items] through the daemon in ONE binder round-trip (TX_READ_BATCH).
     * Returns (status, value) per item in request order — raw, no sentinel filtering
     * (callers apply SentinelDecoder exactly as they would to a single read).
     * Null on any failure: daemon unreachable, timeout, old daemon without batch
     * support, count mismatch, or items outside [1, MAX_BATCH_ITEMS]. Never partial.
     */
    suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>?
    suspend fun write(dev: Int, fid: Int, value: Int): Boolean
    /** Raw autoservice setInt status (1 real, 0 no-op, <0 error, null daemon unreachable). */
    suspend fun writeStatus(dev: Int, fid: Int, value: Int): Int?
    suspend fun isAlive(): Boolean

    /** Creates a VirtualDisplay backed by [surface]; returns its displayId (>0) or null. */
    suspend fun createVirtualDisplay(
        name: String, width: Int, height: Int, density: Int, flags: Int, surface: Surface,
    ): Int?
    suspend fun releaseVirtualDisplay(displayId: Int): Boolean
    suspend fun launchApp(packageName: String): Boolean
    /** Task id of [packageName]'s running task, or null if not running / channel unavailable. */
    suspend fun getTaskId(packageName: String): Int?
    suspend fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean
    suspend fun setTaskBounds(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean
    suspend fun setFocusedTask(taskId: Int): Boolean
    /** [activityType] applies to the freeform direction only (see [HelperBinderProtocol.PANE_TYPE_STANDARD]);
     *  the fullscreen direction ignores it, hence the legacy RECENTS default. */
    suspend fun setTaskWindowingMode(
        taskId: Int, windowingMode: Int, activityType: Int = HelperBinderProtocol.PANE_TYPE_RECENTS,
    ): Boolean
    /**
     * Narrow appops grant to our own package: SYSTEM_ALERT_WINDOW (draw the cluster overlay)
     * and PROJECT_MEDIA (third-party access to the fission screen-projection display, else
     * getDisplay(clusterId) returns null). Returns true only if both grants succeed.
     */
    suspend fun grantOverlayPermission(): Boolean

    /** Self-grant android.permission.READ_LOGS via the daemon (development permission) so the
     *  in-app log recorder sees the daemon's logcat lines. Effective on the next process start. */
    suspend fun grantReadLogs(): Boolean
    /** Launches [packageName] on [displayId] and pins it (move+bounds+focus loop). Long-running. */
    suspend fun launchAndForce(packageName: String, displayId: Int, width: Int, height: Int): Boolean
    /**
     * Enables our steering-wheel accessibility service via the daemon (force re-bind of our entry in
     * Settings.Secure enabled_accessibility_services, never clobbering other apps' services). DiLink
     * has no a11y settings UI, so this is how the star-control toggle self-enables key filtering.
     */
    suspend fun enableAccessibilityService(): Boolean

    /** Write [value] to Settings.Global [key] via `settings put global` under shell uid.
     *  Daemon-whitelisted to sentrymode_enabled_switch and enable_freeform_support. */
    suspend fun putGlobalSetting(key: String, value: Int): Boolean

    /** Disable ([hidden]=true) or re-enable the native BYD assistant family via `pm disable-user/enable`
     *  under shell uid. Daemon-whitelisted to com.byd.autovoice (+ .engine/.tts). Reversible. */
    suspend fun setAppHidden(packageName: String, hidden: Boolean): Boolean

    /**
     * Enables our MediaSessionListenerService stub via the daemon (force re-bind of our entry in
     * Settings.Secure enabled_notification_listeners, never clobbering other apps' listeners).
     * Grants MediaSessionManager.getActiveSessions() access, needed for real Yandex Music playback
     * control. Mirrors enableAccessibilityService.
     */
    suspend fun enableNotificationListenerAccess(): Boolean

    /** Powers the cluster compositor on/off via auto_container (Wave P). True on daemon status 0. */
    suspend fun setClusterContainerMode(on: Boolean): Boolean

    /** Enable or disable the Wi-Fi hotspot (TETHERING_WIFI) via the daemon (shell uid holds
     *  TETHER_PRIVILEGED). True on daemon status 0. Needs on-car validation. */
    suspend fun setHotspot(enable: Boolean): Boolean

    /**
     * Direct freeform launch: find-or-launch [packageName], switch its task to freeform,
     * move it to [displayId] with the given window bounds and focus it. UNAVAILABLE = the
     * freeform switch was rejected (enable_freeform_support not active yet; takes effect after
     * a head-unit reboot) — callers fall back to the VirtualDisplay pipeline. Long-running.
     *
     * [activityType] decides how the task is typed: [HelperBinderProtocol.PANE_TYPE_STANDARD]
     * for split panes (own root task → own input shield), [HelperBinderProtocol.PANE_TYPE_RECENTS]
     * for cluster projection (no freeform caption).
     */
    suspend fun launchFreeform(
        packageName: String, displayId: Int, left: Int, top: Int, right: Int, bottom: Int,
        activityType: Int,
    ): FreeformLaunchResult

    /** `wm density` override on a NON-default display via the daemon; [density] 0 = reset.
     *  Maps the projection scale regulator onto the real cluster display in direct mode. */
    suspend fun setDisplayDensity(displayId: Int, density: Int): Boolean

    /**
     * Reads the windowing state (taskId, windowingMode, bounds) for [packageName]'s running task.
     * Returns null when the daemon is unreachable or the call fails (status ≠ 0).
     * Returns a [SplitTaskState] with taskId == -1 when no task is running for that package.
     */
    suspend fun getTaskState(packageName: String): SplitTaskState?

    /**
     * Queries the BYD statusbar extension (IStatusBarService tx 82) to check whether
     * [packageName] is eligible for split-screen on this head unit.
     * Returns null when the channel is unavailable (daemon unreachable, exception in statusbar call).
     */
    suspend fun isAppSplitSupported(packageName: String): Boolean?

    /**
     * Force-stops [packageName] via IActivityManager.forceStopPackage (shell uid holds
     * FORCE_STOP_PACKAGES). Clears a stale fullscreen task before a freeform re-launch so the
     * daemon's resolveOrLaunchTask creates a fresh task that accepts freeform windowing.
     * Returns true on daemon status 0; false on failure or daemon unreachable (fail-soft).
     */
    suspend fun forceStop(packageName: String): Boolean

    /**
     * Version code the running daemon was compiled with (BuildConfig.VERSION_CODE, frozen at spawn
     * time since CLASSPATH is fixed to the APK that spawned it). Returns null when the daemon is
     * unreachable, the binder transact fails, or the daemon is too old to handle TX_GET_VERSION
     * (an old daemon makes transact return false, which propagates as null). Used by
     * HelperBootstrap to detect stale daemons after an APK update.
     */
    suspend fun daemonVersion(): Long?

    /**
     * Returns the package name of the currently foreground (top-of-stack) task, or null when the
     * daemon is unreachable, transact fails, or the daemon pre-dates TX_GET_TOP_PACKAGE (an old
     * daemon makes transact return false → null, which the media-key poll treats as "not
     * mediacenter → skip"). An empty string means no top task found. Used by the media-key
     * reroute guard to reliably detect when com.byd.mediacenter has actually surfaced, as opposed
     * to merely residing in recents with WINDOWING_MODE_FULLSCREEN (its background state).
     */
    suspend fun getTopTaskPackage(): String?

    /**
     * Non-blocking variant of [getTopTaskPackage]: uses [kotlinx.coroutines.sync.Mutex.tryLock]
     * instead of waiting. Returns null immediately when the HelperClient mutex is already held by
     * a concurrent call (e.g. [setFocusedTask] inside [SplitSessionManager.reAssertSplitZOrder]).
     * Used by the 10 Hz media-key poll tick so it never queues behind a 2 s Binder timeout.
     */
    suspend fun getTopTaskPackageOrSkip(): String?

    /**
     * Raises the pane task for [pkg] to front via the daemon's `am start --windowingMode 5
     * [--activityType 3] --display [displayId] -n <component>` path, relaunching it if its
     * activityType diverges from [activityType]. Returns true when the daemon confirms the
     * raise command ran without error.
     *
     * An old daemon without TX_RAISE_FREEFORM_TASK makes transact return false → returns false.
     * Callers (reAssertSplitZOrder) treat false as "fall back to setFocusedTask" so 386-era
     * behavior is preserved when talking to a pre-Task-N daemon.
     */
    suspend fun raiseFreeformTask(pkg: String, displayId: Int = 0, activityType: Int): Boolean

    /**
     * Same call as [raiseFreeformTask], but reports WHY a raise did not happen (W6-F1 FIX-A).
     * [SplitSessionManager]'s restart path needs the distinction: a daemon that refused the raise
     * and a daemon that never answered both mean "this task will not come to front", and handing
     * the same live pane task to [launchFreeform] would silently report success while the pane
     * stays behind the backdrop. See [RaiseOutcome].
     */
    suspend fun raiseFreeformTaskDetailed(pkg: String, displayId: Int = 0, activityType: Int): RaiseOutcome

    /**
     * Reflects all static int/long constants out of the BYD SDK classes
     * (android.hardware.bydauto.BYDAutoFeatureIds + BYDAutoConstants and their inner classes)
     * via the daemon and assembles them as sorted "ClassName.FIELD=value" lines joined with `\n`.
     *
     * Uses chunked TX_DUMP_FIDS transport (Q4) to avoid the binder transaction size limit.
     * Returns [DumpFidsResult.BinderAbsent] when the daemon is unreachable, timed out, or too
     * old (pre-Q4 daemon makes transact return false on the first chunk).
     * Returns [DumpFidsResult.ReadError] when the daemon signals a non-zero status code or when
     * assembly is aborted mid-way.
     * Returns [DumpFidsResult.Success] with an empty string on non-BYD firmware (classes absent).
     * The dump contains only SDK constant names/values: no VIN, no location, no personal data.
     */
    suspend fun dumpFids(): DumpFidsResult

    /**
     * Returns the windowing state of the top root task on the primary display via the daemon's
     * TX_GET_TOP_TASK handler (Q3 / F-3). Used by SplitSessionManager to detect when a foreign
     * fullscreen app has covered the active split session.
     *
     * Returns null when the daemon is unreachable, too old (pre-Q3 daemon makes transact return
     * false → null, so COVERED detection auto-disarms against old daemons), or no task is found.
     */
    suspend fun getTopTask(): TopTaskInfo?
}

@Singleton
open class HelperClientImpl @Inject constructor() : HelperClient {
    private val mutex = Mutex()
    @Volatile private var cached: IBinder? = null

    /** Intermediate holder for one TX_DUMP_FIDS chunk reply (chunked transport, Q4). */
    private data class DumpChunkReply(val status: Int, val totalLength: Int, val bytes: ByteArray?)

    override suspend fun read(dev: Int, fid: Int, tx: Int): Long? =
        transact(HelperBinderProtocol.TX_READ) { it.writeInt(tx); it.writeInt(dev); it.writeInt(fid) }
            ?.let { (status, value) -> if (readAccepted(status)) value.toLong() else null }

    override suspend fun readBatch(items: List<BatchReadItem>): List<Pair<Int, Int>>? {
        if (items.isEmpty() || items.size > HelperBinderProtocol.MAX_BATCH_ITEMS) return null
        return transactParsed(
            HelperBinderProtocol.TX_READ_BATCH,
            writeArgs = { p ->
                p.writeInt(items.size)
                items.forEach { p.writeInt(it.tx); p.writeInt(it.dev); p.writeInt(it.fid) }
            },
            timeoutMs = BATCH_TIMEOUT_MS,
        ) { reply ->
            if (reply.dataAvail() < 4) return@transactParsed null
            val n = reply.readInt()
            if (n != items.size || reply.dataAvail() < n * 8) return@transactParsed null
            List(n) { reply.readInt() to reply.readInt() }
        }
    }

    override suspend fun writeStatus(dev: Int, fid: Int, value: Int): Int? {
        val status = transact(HelperBinderProtocol.TX_WRITE) {
            it.writeInt(dev); it.writeInt(fid); it.writeInt(value)
        }?.first
        // status forwarded from the autoservice setInt return code: 1 = real action,
        // 0 = accepted no-op (fid ineffective on this trim), <0 = error, null =
        // daemon unreachable. INFO so a "green" automation that physically did
        // nothing (no-op) is distinguishable from one that actually moved the actuator.
        Log.i(TAG, "write dev=$dev fid=$fid value=$value status=$status accepted=${status != null && writeAccepted(status)}")
        return status
    }

    override suspend fun write(dev: Int, fid: Int, value: Int): Boolean =
        writeStatus(dev, fid, value)?.let { writeAccepted(it) } ?: false

    override suspend fun isAlive(): Boolean =
        transact(HelperBinderProtocol.TX_PING) { }
            ?.let { (status, _) -> readAccepted(status) } ?: false

    override suspend fun createVirtualDisplay(
        name: String, width: Int, height: Int, density: Int, flags: Int, surface: Surface,
    ): Int? = transactParsed(HelperBinderProtocol.TX_CREATE_VIRTUAL_DISPLAY, { d ->
        d.writeString(name); d.writeInt(width); d.writeInt(height); d.writeInt(density); d.writeInt(flags)
        surface.writeToParcel(d, 0)
    }) { reply ->
        val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed null
        val id = if (reply.dataAvail() >= 4) reply.readInt() else -1
        if (readAccepted(status) && id > 0) id else null
    }

    override suspend fun releaseVirtualDisplay(displayId: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_RELEASE_VIRTUAL_DISPLAY) { it.writeInt(displayId) }

    override suspend fun launchApp(packageName: String): Boolean =
        statusOk(HelperBinderProtocol.TX_LAUNCH_APP) { it.writeString(packageName) }

    override suspend fun getTaskId(packageName: String): Int? =
        transact(HelperBinderProtocol.TX_GET_TASK_ID) { it.writeString(packageName) }
            ?.let { (status, value) -> if (readAccepted(status) && value > 0) value else null }

    override suspend fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_MOVE_TASK_TO_DISPLAY) { it.writeInt(taskId); it.writeInt(displayId) }

    override suspend fun setTaskBounds(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_TASK_BOUNDS) {
            it.writeInt(taskId); it.writeInt(left); it.writeInt(top); it.writeInt(right); it.writeInt(bottom)
        }

    override suspend fun setFocusedTask(taskId: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_FOCUSED_TASK) { it.writeInt(taskId) }

    // FORCE_TIMEOUT_MS, not the default 2s: on ROMs without the binder API (DiLink 5 removed
    // setTaskWindowingMode with AOSP S) the daemon restores fullscreen by removing the stack and
    // relaunching via `am start` with a settle pause — well over the 2s default.
    override suspend fun setTaskWindowingMode(taskId: Int, windowingMode: Int, activityType: Int): Boolean =
        transactParsed(HelperBinderProtocol.TX_SET_TASK_WINDOWING_MODE, {
            it.writeInt(taskId); it.writeInt(windowingMode); it.writeInt(activityType)
        }, timeoutMs = FORCE_TIMEOUT_MS) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed false
            readAccepted(status)
        } ?: false

    override suspend fun grantOverlayPermission(): Boolean =
        statusOk(HelperBinderProtocol.TX_GRANT_OVERLAY_PERMISSION) { }

    override suspend fun grantReadLogs(): Boolean =
        statusOk(HelperBinderProtocol.TX_GRANT_READ_LOGS) { }

    override suspend fun launchAndForce(packageName: String, displayId: Int, width: Int, height: Int): Boolean =
        transactParsed(HelperBinderProtocol.TX_LAUNCH_AND_FORCE, { d ->
            d.writeString(packageName); d.writeInt(displayId); d.writeInt(width); d.writeInt(height)
        }, timeoutMs = FORCE_TIMEOUT_MS) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed false
            readAccepted(status)
        } ?: false

    // FORCE_TIMEOUT_MS, not the default 2s: the daemon does a 200ms re-bind pause plus several
    // `settings` process spawns, which can outrun REQ_TIMEOUT_MS on a cold device. status 0 = ok.
    override suspend fun enableAccessibilityService(): Boolean =
        transactParsed(HelperBinderProtocol.TX_ENABLE_ACCESSIBILITY, { }, timeoutMs = FORCE_TIMEOUT_MS) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed false
            status == 0
        } ?: false

    override suspend fun putGlobalSetting(key: String, value: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_PUT_GLOBAL_SETTING) {
            it.writeString(key); it.writeInt(value)
        }

    override suspend fun setClusterContainerMode(on: Boolean): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_CLUSTER_MODE) { it.writeInt(if (on) 1 else 0) }

    override suspend fun setHotspot(enable: Boolean): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_HOTSPOT) { it.writeInt(if (enable) 1 else 0) }

    // FORCE_TIMEOUT_MS, not the default 2s: mirrors launchAndForce (launch retry loop in the
    // daemon can take up to ~9.5s on a cold start before the pin loop even begins).
    override suspend fun launchFreeform(
        packageName: String, displayId: Int, left: Int, top: Int, right: Int, bottom: Int,
        activityType: Int,
    ): FreeformLaunchResult =
        transactParsed(HelperBinderProtocol.TX_LAUNCH_FREEFORM, { d ->
            d.writeString(packageName); d.writeInt(displayId)
            d.writeInt(left); d.writeInt(top); d.writeInt(right); d.writeInt(bottom)
            d.writeInt(activityType)
        }, timeoutMs = FORCE_TIMEOUT_MS) { reply ->
            if (reply.dataAvail() < 4) return@transactParsed FreeformLaunchResult.FAILED
            when (reply.readInt()) {
                0 -> FreeformLaunchResult.OK
                -2 -> FreeformLaunchResult.UNAVAILABLE
                else -> FreeformLaunchResult.FAILED
            }
        } ?: FreeformLaunchResult.FAILED

    override suspend fun setDisplayDensity(displayId: Int, density: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_DISPLAY_DENSITY) {
            it.writeInt(displayId); it.writeInt(density)
        }

    override suspend fun getTaskState(packageName: String): SplitTaskState? =
        transactParsed(HelperBinderProtocol.TX_GET_TASK_STATE, { it.writeString(packageName) }) { reply ->
            if (reply.dataAvail() < 4) return@transactParsed null
            val status = reply.readInt()
            if (status != 0) return@transactParsed null
            // Expect taskId + windowingMode + l + t + r + b = 6 ints = 24 bytes.
            if (reply.dataAvail() < 24) return@transactParsed null
            val taskId = reply.readInt()
            val windowingMode = reply.readInt()
            val left = reply.readInt()
            val top = reply.readInt()
            val right = reply.readInt()
            val bottom = reply.readInt()
            // 7th int (additive, Task M): displayId. Old daemon sends only 6 ints;
            // when no bytes remain we default to 0 (main display) so the "departed"
            // branch in SplitSessionManager auto-disarms against old daemons.
            val displayId = if (reply.dataAvail() >= 4) reply.readInt() else 0
            SplitTaskState(taskId, windowingMode, left, top, right, bottom, displayId)
        }

    override suspend fun isAppSplitSupported(packageName: String): Boolean? =
        transactParsed(HelperBinderProtocol.TX_APP_SPLIT_SUPPORTED, { it.writeString(packageName) }) { reply ->
            if (reply.dataAvail() < 4) return@transactParsed null
            val status = reply.readInt()
            if (status != 0) return@transactParsed null
            if (reply.dataAvail() < 4) return@transactParsed null
            reply.readInt() != 0
        }

    override suspend fun forceStop(packageName: String): Boolean =
        statusOk(HelperBinderProtocol.TX_FORCE_STOP) { it.writeString(packageName) }

    override suspend fun daemonVersion(): Long? =
        transact(HelperBinderProtocol.TX_GET_VERSION) { }
            ?.let { (status, value) -> if (readAccepted(status)) value.toLong() else null }

    override suspend fun getTopTaskPackage(): String? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(REQ_TIMEOUT_MS) {
                mutex.withLock { transactTopPackageBodyUnlocked() }
            }
        }

    override suspend fun getTopTaskPackageOrSkip(): String? {
        if (!mutex.tryLock()) return null
        return try {
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(REQ_TIMEOUT_MS) { transactTopPackageBodyUnlocked() }
            }
        } finally {
            mutex.unlock()
        }
    }

    /**
     * Shared transact body for [TX_GET_TOP_PACKAGE][HelperBinderProtocol.TX_GET_TOP_PACKAGE].
     * Assumes [mutex] is already held by the caller — call from inside [getTopTaskPackage]'s
     * [mutex.withLock] or [getTopTaskPackageOrSkip]'s [mutex.tryLock]/finally block.
     * Returns the top package name, empty string (no top task), or null on any failure.
     */
    private fun transactTopPackageBodyUnlocked(): String? {
        for (attempt in 0..1) {
            val binder = ensureBinder() ?: return null
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(HelperBinderProtocol.DESCRIPTOR)
                if (!binder.transact(HelperBinderProtocol.TX_GET_TOP_PACKAGE, data, reply, 0)) return null
                if (reply.dataAvail() < 4) return null
                if (reply.readInt() != 0) return null
                return reply.readString()
            } catch (e: DeadObjectException) {
                cached = null
                if (attempt == 1) { Log.w(TAG, "binder dead after retry: ${e.message}"); return null }
            } catch (e: Exception) {
                Log.w(TAG, "transact failed: ${e.message}"); return null
            } finally {
                data.recycle(); reply.recycle()
            }
        }
        return null
    }

    // RAISE_TIMEOUT_MS, not the default 2s: the daemon side spawns two processes
    // (cmd package resolve-activity + am start) — each can outrun REQ_TIMEOUT_MS on cold
    // DiLink hardware. 4.5 s covers both comfortably; FORCE_TIMEOUT_MS (15 s) is wrong
    // here because this call runs inside the session mutex on every reroute.
    override suspend fun raiseFreeformTask(pkg: String, displayId: Int, activityType: Int): Boolean =
        raiseFreeformTaskDetailed(pkg, displayId, activityType) == RaiseOutcome.RAISED

    // transactParsed already separates the two failure modes: null = no usable reply (unknown TX
    // on an old daemon, timeout, dead binder), false = the daemon replied with a rejected status.
    // The plain Boolean API collapses both into false; this variant keeps them apart (FIX-A).
    override suspend fun raiseFreeformTaskDetailed(pkg: String, displayId: Int, activityType: Int): RaiseOutcome {
        val accepted: Boolean? = transactParsed(HelperBinderProtocol.TX_RAISE_FREEFORM_TASK, {
            it.writeString(pkg); it.writeInt(displayId); it.writeInt(activityType)
        }, timeoutMs = RAISE_TIMEOUT_MS) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed false
            readAccepted(status)
        }
        return when (accepted) {
            true -> RaiseOutcome.RAISED
            false -> RaiseOutcome.REFUSED
            null -> RaiseOutcome.NO_REPLY
        }
    }

    override suspend fun dumpFids(): DumpFidsResult {
        val assembler = ByteArrayOutputStream()
        var offset = 0
        var totalLength = -1
        var chunkIndex = 0
        while (true) {
            val chunkReply: DumpChunkReply? = transactParsed(
                HelperBinderProtocol.TX_DUMP_FIDS,
                { it.writeInt(offset) },
                timeoutMs = DUMP_FIDS_TIMEOUT_MS,
            ) { p ->
                if (p.dataAvail() < 8) return@transactParsed null
                // C-3: wrap readInt + createByteArray in runCatching. An old-format daemon writes
                // writeString (not a byte array) for TX_DUMP_FIDS; Robolectric strict-mode Parcel
                // throws when readInt() encounters a String item, propagating through transactParsed's
                // generic catch-all → null → BinderAbsent at chunkIndex=0. By catching here we
                // return a sentinel ReadError status (-998) instead of null, so the BinderAbsent
                // branch is never taken. On real Android (raw bytes), readInt just reads 4 bytes
                // regardless of encoding, so the outer runCatching is a no-op in production.
                runCatching {
                    val statusField = p.readInt()
                    val totalLengthField = p.readInt()
                    val bytes = runCatching { p.createByteArray() }.getOrNull()
                    DumpChunkReply(statusField, totalLengthField, bytes)
                }.getOrElse { DumpChunkReply(-998, 0, null) }
            }
            when {
                chunkReply == null ->
                    return if (chunkIndex == 0) DumpFidsResult.BinderAbsent
                    else DumpFidsResult.ReadError("transact failed at chunk $chunkIndex (assembled $offset/$totalLength bytes)")
                chunkReply.status != 0 ->
                    return DumpFidsResult.ReadError("status=${chunkReply.status} at chunk $chunkIndex")
                else -> {
                    if (totalLength < 0) totalLength = chunkReply.totalLength
                    // null bytes with status=0 means the payload was malformed (e.g. old-format
                    // daemon whose string could not be parsed as a byte array, or createByteArray
                    // returned null for an oversized length). Treat as ReadError: 65 no-progress
                    // loops until the guard fires is wasteful; totalLength==0 would give
                    // Success("") — a false positive empty-dump result. (C-3-R1)
                    if (chunkReply.bytes == null) return DumpFidsResult.ReadError(
                        "malformed reply at chunk $chunkIndex: null byte array with status=0")
                    val b = chunkReply.bytes
                    assembler.write(b)
                    offset += b.size
                    chunkIndex++
                    if (totalLength >= 0 && offset >= totalLength) break
                    if (chunkIndex > MAX_DUMP_CHUNKS) {
                        return DumpFidsResult.ReadError("loop guard: assembled $offset/$totalLength bytes after $chunkIndex chunks")
                    }
                }
            }
        }
        return DumpFidsResult.Success(assembler.toByteArray().toString(Charsets.UTF_8))
    }

    override suspend fun getTopTask(): TopTaskInfo? =
        transactParsed(HelperBinderProtocol.TX_GET_TOP_TASK, { }) { reply ->
            if (reply.dataAvail() < 4) return@transactParsed null
            val status = reply.readInt()
            if (status != 0) return@transactParsed null
            val pkg = reply.readString() ?: return@transactParsed null
            // taskId + windowingMode + activityType + displayId = 4 ints = 16 bytes
            if (reply.dataAvail() < 16) return@transactParsed null
            val taskId = reply.readInt()
            val windowingMode = reply.readInt()
            val activityType = reply.readInt()
            val displayId = reply.readInt()
            TopTaskInfo(pkg, taskId, windowingMode, activityType, displayId)
        }

    override suspend fun setAppHidden(packageName: String, hidden: Boolean): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_APP_HIDDEN) {
            it.writeString(packageName); it.writeInt(if (hidden) 1 else 0)
        }

    // FORCE_TIMEOUT_MS, not the default 2s: mirrors enableAccessibilityService (200ms re-bind
    // pause plus several `settings` process spawns). status 0 = ok.
    override suspend fun enableNotificationListenerAccess(): Boolean =
        transactParsed(HelperBinderProtocol.TX_ENABLE_NOTIFICATION_LISTENER, { }, timeoutMs = FORCE_TIMEOUT_MS) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed false
            status == 0
        } ?: false

    /** (status,value) reply; true iff status == 0. Shared by the boolean projection ops. */
    private suspend fun statusOk(code: Int, writeArgs: (Parcel) -> Unit): Boolean =
        transact(code, writeArgs)?.let { (status, _) -> readAccepted(status) } ?: false

    /** Returns the parsed reply or null on any failure. Retries ONCE on a dead cached binder. */
    private suspend fun <T> transactParsed(
        code: Int,
        writeArgs: (Parcel) -> Unit,
        timeoutMs: Long = REQ_TIMEOUT_MS,
        parse: (Parcel) -> T?,
    ): T? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
                mutex.withLock {
                    repeat(2) { attempt ->
                        val binder = ensureBinder() ?: return@withLock null
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(HelperBinderProtocol.DESCRIPTOR)
                            writeArgs(data)
                            val ok = binder.transact(code, data, reply, 0)
                            if (!ok) return@withLock null           // uid gate rejected / not handled
                            return@withLock parse(reply)
                        } catch (e: DeadObjectException) {
                            cached = null                            // stale binder; loop re-resolves once
                            if (attempt == 1) { Log.w(TAG, "binder dead after retry: ${e.message}"); return@withLock null }
                        } catch (e: Exception) {
                            Log.w(TAG, "transact failed: ${e.message}"); return@withLock null
                        } finally {
                            data.recycle(); reply.recycle()
                        }
                    }
                    null
                }
            }
        }

    /** (status, value) wrapper used by read/write/ping. */
    private suspend fun transact(code: Int, writeArgs: (Parcel) -> Unit): Pair<Int, Int>? =
        transactParsed(code, writeArgs) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed null
            val value = if (reply.dataAvail() >= 4) reply.readInt() else 0
            status to value
        }

    private fun ensureBinder(): IBinder? {
        cached?.takeIf { it.isBinderAlive }?.let { return it }
        return resolveBinder()?.also { cached = it }
    }

    /** Production: look the daemon up by name. Overridable so tests can inject a fake IBinder. */
    internal open fun resolveBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        (sm.getMethod("getService", String::class.java)
            .invoke(null, HelperBinderProtocol.SERVICE_NAME) as? IBinder)
    } catch (e: Exception) {
        Log.w(TAG, "getService failed: ${e.message}"); null
    }

    companion object {
        /**
         * The daemon forwards the raw autoservice transact return code in status.
         * Validated on Leopard 3 2026-05-28:
         *   setInt real action → status = 1
         *   setInt no-op       → status = 0
         *   getInt success     → status = 0 (value follows)
         *   error / no reply   → status < 0 (-1 daemon exception, -999 no reply data)
         *
         * A write is accepted on status >= 0 (using == 0 marked every real action
         * as a failure). A read is accepted only on status == 0.
         * See HelperClientStatusTest.
         */
        internal fun writeAccepted(status: Int): Boolean = status >= 0
        internal fun readAccepted(status: Int): Boolean = status == 0

        private const val TAG = "HelperClient"
        private const val REQ_TIMEOUT_MS = 2000L
        private const val FORCE_TIMEOUT_MS = 15000L
        /** TX_READ_BATCH budget: 58 in-process transacts typically take ~100 ms; 5 s is a 50× margin. */
        const val BATCH_TIMEOUT_MS = 5_000L
        /** TX_RAISE_FREEFORM_TASK budget: two shell spawns (resolve-activity + am start).
         *  4.5 s comfortably covers ~2 × 1.5 s worst-case on cold DiLink hardware, while
         *  staying well below the reroute reaction budget. FORCE_TIMEOUT_MS (15 s) would be
         *  wrong since this call runs inside the session mutex on every media-key reroute. */
        private const val RAISE_TIMEOUT_MS = 4_500L
        /** TX_DUMP_FIDS budget: daemon loads SDK classes + iterates all declared fields + marshals
         *  the joined string. 4.5 s matches RAISE_TIMEOUT_MS (same cold-DiLink baseline); no mutex
         *  is held during this call so the budget can be generous. Applied PER chunk transact. */
        private const val DUMP_FIDS_TIMEOUT_MS = 4_500L

        /** Safety cap on the number of TX_DUMP_FIDS loop iterations (chunks). 64 × 64 KiB = 4 MiB,
         *  far above any realistic SDK catalog size; guards against a misbehaving daemon. */
        private const val MAX_DUMP_CHUNKS = 64
    }
}
