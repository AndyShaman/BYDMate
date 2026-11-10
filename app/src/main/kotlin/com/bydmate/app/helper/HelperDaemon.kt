@file:JvmName("HelperDaemon")
package com.bydmate.app.helper

import com.bydmate.app.BuildConfig
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.vehicle.NativeAssistant
import com.bydmate.app.helper.push.FID_PUSH_DEVICE_CLASSES
import com.bydmate.app.helper.push.FidPushRegistry
import com.bydmate.app.helper.push.FidRecorder
import com.bydmate.app.helper.push.deviceInstance
import com.bydmate.app.helper.dtc.DtcDaemon
import com.bydmate.app.helper.dtc.writeDtcScanStatus
import com.bydmate.app.helper.offreport.OffReport
import com.bydmate.app.helper.offreport.readOffReportArm
import com.bydmate.app.helper.offreport.writeOffReportStatus
import com.bydmate.app.helper.push.PermissiveContext
import com.bydmate.app.helper.push.readRecStartRequest
import com.bydmate.app.helper.push.readSubscribeRequest
import com.bydmate.app.helper.push.writeRecStatus
import com.bydmate.app.helper.push.writeResultTable
import com.bydmate.app.helper.push.writeStatusTable
import com.bydmate.app.util.readSystemProperty
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Bundle
import android.view.Surface
import java.util.concurrent.ConcurrentHashMap
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import java.io.File
import java.io.RandomAccessFile
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.system.exitProcess

// Lock path on the device filesystem (writable by shell uid).
private const val LOCK_PATH = "/data/local/tmp/bydmate_helper.lock"

// SELinux domain of the running process; the daemon must land in the shell domain to be
// allowed to addService. Unreadable on some firmwares — never fatal.
private const val SELINUX_ATTR_PATH = "/proc/self/attr/current"

// Stack frames printed alongside a bootstrap failure — enough to name the rejecting
// framework call without burying the log.
private const val FAILURE_STACK_FRAMES = 4

// Guard against a pathological cause cycle while unwrapping reflection wrappers.
private const val MAX_CAUSE_HOPS = 4

// How often the broadcast transport re-announces the binder while no app process holds it.
private const val REANNOUNCE_INTERVAL_MS = 10_000L

/**
 * SELinux context of this process, or a marker when the attr file cannot be read.
 * [path] is a parameter so the JVM unit test can point it at a temp file.
 */
internal fun readSelinuxContext(path: String = SELINUX_ATTR_PATH): String =
    // The kernel NUL-terminates the attr value; drop it or the log line carries a stray byte.
    runCatching { File(path).readText().filter { it.code != 0 }.trim() }
        .getOrNull()?.takeIf { it.isNotEmpty() } ?: "(unavailable)"

/**
 * Renders a bootstrap failure together with its REAL cause.
 *
 * ServiceManager.addService is invoked reflectively, so the caught exception is an
 * InvocationTargetException whose own message is always null — printing just that message
 * is what left issue #64 ("ERR: addService null") undiagnosable. Unwraps the reflection
 * wrappers, then prints the unwrapped class + message plus the top stack frames.
 */
internal fun describeBootstrapFailure(t: Throwable, frames: Int = FAILURE_STACK_FRAMES): String {
    val root = unwrapReflectionCause(t)
    return buildString {
        append(root.javaClass.name)
        append(": ")
        append(root.message ?: "(no message)")
        if (root !== t) append(" [wrapped in ${t.javaClass.simpleName}]")
        root.stackTrace.take(frames).forEach { append("\n  at $it") }
    }
}

/** Peels InvocationTargetException / UndeclaredThrowableException down to the real throwable. */
private fun unwrapReflectionCause(t: Throwable): Throwable {
    var current = t
    var hops = 0
    while (hops < MAX_CAUSE_HOPS &&
        (current is InvocationTargetException || current is UndeclaredThrowableException)
    ) {
        current = current.cause ?: break
        hops++
    }
    return current
}

/**
 * Acquires an exclusive file lock on [path]. Returns a (FileChannel, FileLock) pair on
 * success, or null if the lock is already held (either by another process or by the same JVM).
 *
 * Callers must keep the returned pair live until the lock should be released — do NOT close
 * the channel while the lock must be held.
 *
 * Internal so the JVM unit test can reach it from the same package.
 */
internal fun acquireSingleOwnerLock(path: String): Pair<FileChannel, FileLock>? {
    return try {
        val channel = RandomAccessFile(path, "rw").channel
        val lock = channel.tryLock()   // null when another PROCESS holds the region lock
        if (lock == null) {
            runCatching { channel.close() }
            null
        } else {
            channel to lock
        }
    } catch (e: OverlappingFileLockException) {
        // Same JVM already holds the lock on this file.
        null
    } catch (e: Exception) {
        null
    }
}

/**
 * Shell-uid binder daemon entry point. Spawned by the app via:
 *   CLASSPATH=<apk> app_process /system/bin \
 *     --nice-name=bydmate_helper com.bydmate.app.helper.HelperDaemon <appUid> [<spawnToken>]
 *
 * Lifecycle:
 *   1. Parse expectedUid from args[0] and the optional spawn token from args[1].
 *   2. Acquire single-owner file lock — exits with ALREADY_RUNNING if held.
 *   3. Resolve autoservice IBinder reflectively.
 *   4. Register a Binder stub under SERVICE_NAME via ServiceManager.addService; on firmwares
 *      that refuse the registration to the shell domain (#64), fall back to handing the same
 *      Binder to the app in a broadcast (see [publishBinderByBroadcast]).
 *   5. Print READY via=<transport> and keepalive with Looper.loop().
 *
 * Hidden-API note: this daemon runs under app_process (tool context), NOT a normal
 * app process. The hidden-API enforcement layer is only active for app processes, so
 * plain reflection is fine here — do NOT use HiddenApiBypass in this file.
 */
fun main(args: Array<String>) {
    val expectedUid = args.getOrNull(0)?.toIntOrNull() ?: run {
        System.err.println("ERR: usage: HelperDaemon <appUid>")
        // exitProcess (not return): app_process keeps the binder threadpool's
        // non-daemon threads alive, so a bare `return` from main() would hang the JVM.
        exitProcess(2)
    }
    // Spawn token of THIS spawn — echoed back to the app in the broadcast fallback so it can
    // tell our Binder from anything else that reaches the exported receiver. Absent when an
    // older app version did the spawn (then only the addService path is available).
    val spawnToken = spawnTokenFrom(args)

    // Step 1: single-owner lock — prevents duplicate daemons.
    val lockPair = acquireSingleOwnerLock(LOCK_PATH)
    if (lockPair == null) {
        println("ALREADY_RUNNING")
        // Clean exit: another owner already holds the service. Must exitProcess
        // rather than return so this spawn does not linger as a zombie process.
        exitProcess(0)
    }
    // lockChannel and lockHandle stay referenced past Looper.loop() because the
    // stack frame is never unwound (loop() blocks forever). The lock stays live.
    @Suppress("UNUSED_VARIABLE") val lockChannel = lockPair.first
    @Suppress("UNUSED_VARIABLE") val lockHandle = lockPair.second

    // Identity of the spawned process. addService is only permitted from the shell domain,
    // so a wrong uid or SELinux context explains a registration refusal on its own (#64).
    // The token itself never reaches the log — it authenticates the broadcast fallback, and the
    // daemon log is world-readable on the device. Only its presence is diagnostic.
    println(
        "BOOT uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} " +
            "selinux=${readSelinuxContext()} token=${if (spawnToken != null) "set" else "absent"}"
    )
    System.out.flush()

    // Prepare the main looper BEFORE ActivityThread.systemMain (OpenBYD EntryPoint order),
    // then acquire a system Context for the projection DisplayManager calls.
    @Suppress("DEPRECATION")
    Looper.prepareMainLooper()
    val systemContext: Context? = acquireSystemContext()
    FidPushRegistry.context = systemContext?.let { PermissiveContext(it) }
    // The diagnostic recorder builds its own device instances and needs the same Context.
    FidRecorder.context = FidPushRegistry.context
    logPushContext(systemContext)

    // Step 2: resolve autoservice Binder.
    val smCls = Class.forName("android.os.ServiceManager")
    val svc: IBinder = smCls.getMethod("getService", String::class.java)
        .invoke(null, "autoservice") as? IBinder
        ?: run {
            System.err.println("ERR: autoservice not found")
            // exitProcess so the OS releases the file lock we already hold above;
            // a bare return would leave a hung daemon holding the lock forever.
            exitProcess(3)
        }
    val autoIface: String = svc.interfaceDescriptor ?: ""
    // The power-off report seeds its listener with a direct getInt of the power fids.
    OffReport.reader = { dev, fid ->
        autoserviceTransact(svc, autoIface, 5, dev, fid, 0, writeValue = false)
            .takeIf { (status, _) -> status == 0 }?.second
    }

    // Keeps created VirtualDisplays alive (their backing Surface comes from the app overlay).
    // Keyed by displayId so TX_RELEASE_VIRTUAL_DISPLAY can release the right one.
    val virtualDisplays = ConcurrentHashMap<Int, VirtualDisplay>()

    // Step 3: build our stub Binder.
    val helperBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Uid gate first — only our app may call.
            if (Binder.getCallingUid() != expectedUid) return false

            // IBinder.getInterfaceDescriptor() on the app side is a plain INTERFACE_TRANSACTION
            // with NO interface token in the parcel, so it must be answered by the base class
            // BEFORE enforceInterface — otherwise the broadcast-delivered binder (#64/#148) is
            // rejected as descriptor_mismatch (field log 2026-08-25, storm-1986). Name lookups via
            // ServiceManager never asked for the descriptor, which is why DiLink 5 never noticed.
            if (code == IBinder.INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags)

            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)

            return when (code) {
                HelperBinderProtocol.TX_PING -> runCatching {
                    reply?.writeInt(0)
                    true
                }.getOrElse {
                    reply?.writeInt(-1); reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_READ -> runCatching {
                    val tx = data.readInt()
                    val dev = data.readInt()
                    val fid = data.readInt()
                    val (status, retInt) = autoserviceTransact(svc, autoIface, tx, dev, fid, 0, writeValue = false)
                    reply?.writeInt(status)
                    reply?.writeInt(retInt)
                    true
                }.getOrElse {
                    reply?.writeInt(-1); reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_READ_BATCH -> runCatching {
                    readBatchIntoReply(data, reply) { tx, dev, fid ->
                        autoserviceTransact(svc, autoIface, tx, dev, fid, 0, writeValue = false)
                    }
                    true
                }.getOrElse {
                    reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_WRITE -> runCatching {
                    val dev = data.readInt()
                    val fid = data.readInt()
                    val value = data.readInt()
                    val (status, retInt) = autoserviceTransact(svc, autoIface, 6, dev, fid, value, writeValue = true)
                    reply?.writeInt(status)
                    reply?.writeInt(retInt)
                    true
                }.getOrElse {
                    reply?.writeInt(-1); reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_GET_TASK_ID -> runCatching {
                    val pkg = data.readString() ?: ""
                    val taskId = findTaskId(pkg)
                    reply?.writeInt(0); reply?.writeInt(taskId)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_MOVE_TASK_TO_DISPLAY -> {
                    var logTaskId = -1
                    var logDisplayId = -1
                    runCatching {
                        val taskId = data.readInt(); logTaskId = taskId
                        val displayId = data.readInt(); logDisplayId = displayId
                        moveTaskToDisplayReflect(taskId, displayId)
                        reply?.writeInt(0); reply?.writeInt(0)
                        true
                    }.getOrElse { t ->
                        android.util.Log.w(
                            "bydmate_helper", "TX_MOVE_TASK_TO_DISPLAY failed task=$logTaskId display=$logDisplayId", t)
                        reply?.writeInt(-1); reply?.writeInt(0); true
                    }
                }

                HelperBinderProtocol.TX_SET_TASK_BOUNDS -> runCatching {
                    val taskId = data.readInt()
                    val l = data.readInt(); val t = data.readInt(); val r = data.readInt(); val b = data.readInt()
                    setTaskBoundsReflect(taskId, l, t, r, b)
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_FOCUSED_TASK -> {
                    var logTaskId = -1
                    runCatching {
                        val taskId = data.readInt(); logTaskId = taskId
                        setFocusedTaskReflect(taskId)
                        reply?.writeInt(0); reply?.writeInt(0)
                        true
                    }.getOrElse { t ->
                        android.util.Log.w("bydmate_helper", "TX_SET_FOCUSED_TASK failed task=$logTaskId", t)
                        reply?.writeInt(-1); reply?.writeInt(0); true
                    }
                }

                HelperBinderProtocol.TX_SET_TASK_WINDOWING_MODE -> runCatching {
                    val taskId = data.readInt(); val mode = data.readInt()
                    handleSetWindowingModeTx(
                        taskId, mode, readTrailingPaneType(data),
                        reflectSet = ::setTaskWindowingModeReflect,
                        resolveComponent = { packageForTask(taskId)?.let { resolveLaunchComponent(it) } },
                        shell = amShell,
                        getActivityType = { ti -> taskActivityType(ti) },
                        sleep = { Thread.sleep(it) },
                        // Enables the non-destructive freeform probe before a retype remove.
                        stateOf = { ti -> taskModeState(ti) },
                    )
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_CREATE_VIRTUAL_DISPLAY -> {
                    var logFlags = 0; var logWidth = 0; var logHeight = 0
                    runCatching {
                        val name = data.readString() ?: "BYDMate_VD"
                        val width = data.readInt(); val height = data.readInt()
                        val density = data.readInt(); val flags = data.readInt()
                        logFlags = flags; logWidth = width; logHeight = height
                        val surface = Surface.CREATOR.createFromParcel(data)
                        val ctx = systemContext
                        val id = if (ctx == null || !surface.isValid) -1
                                else createVirtualDisplay(ctx, virtualDisplays, name, width, height, density, surface, flags)
                        if (id > 0) { reply?.writeInt(0); reply?.writeInt(id) }
                        else { surface.release(); reply?.writeInt(-1); reply?.writeInt(0) }
                        true
                    }.getOrElse { t ->
                        // The app asks for a PUBLIC display first and falls back to private flags on
                        // refusal: that SecurityException is expected, one line instead of a stack.
                        if (t is SecurityException) {
                            android.util.Log.w("bydmate_helper", "TX_CREATE_VIRTUAL_DISPLAY: SecurityException " +
                                "flags=$logFlags ${logWidth}x$logHeight (expected for PUBLIC; app falls back to private flags)")
                        } else {
                            android.util.Log.w("bydmate_helper", "TX_CREATE_VIRTUAL_DISPLAY failed flags=$logFlags ${logWidth}x$logHeight", t)
                        }
                        reply?.writeInt(-1); reply?.writeInt(0); true
                    }
                }

                HelperBinderProtocol.TX_RELEASE_VIRTUAL_DISPLAY -> runCatching {
                    val displayId = data.readInt()
                    val vd = virtualDisplays.remove(displayId)
                    vd?.release()
                    reply?.writeInt(if (vd != null) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_LAUNCH_APP -> runCatching {
                    val ok = launchApp(data.readString() ?: "")
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_GRANT_OVERLAY_PERMISSION -> runCatching {
                    // Narrow, hardcoded — NOT a generic shell passthrough. Two appops:
                    // SYSTEM_ALERT_WINDOW lets us draw the cluster overlay; PROJECT_MEDIA gates
                    // third-party access to the fission screen-projection display (without it
                    // DisplayManager.getDisplay(clusterId) returns null in our process). Mirrors
                    // OpenBYD AppConstants two-grant.
                    val pkg = HelperBinderProtocol.APP_PACKAGE
                    val r1 = shExec("appops set \"\$1\" SYSTEM_ALERT_WINDOW allow", pkg)
                    val r2 = shExec("appops set \"\$1\" PROJECT_MEDIA allow", pkg)
                    val ok = r1.code == 0 && r2.code == 0
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_GRANT_READ_LOGS -> runCatching {
                    // Narrow, hardcoded target — NOT a generic pm passthrough. READ_LOGS is a
                    // development permission (grantable via pm grant); it maps to the "log" gid,
                    // which the app process picks up on its NEXT start (gids are set at fork).
                    val r = shExec("pm grant \"\$1\" android.permission.READ_LOGS", HelperBinderProtocol.APP_PACKAGE)
                    reply?.writeInt(if (r.code == 0) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_HOTSPOT -> runCatching {
                    val enable = data.readInt() != 0
                    val ctx = systemContext
                    val ok = if (ctx != null) setHotspot(ctx, enable) else false
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_SCREEN_BACKLIGHT -> {
                    val on = runCatching { data.readInt() != 0 }.getOrDefault(true)
                    val result = setScreenBacklight(on, android.os.SystemClock.uptimeMillis()) {
                        systemContext?.getSystemService(Context.POWER_SERVICE) ?: error("no system Context")
                    }
                    reply?.writeInt(result.outcome); reply?.writeInt(result.status)
                    reply?.writeString(result.detail)
                    true
                }

                HelperBinderProtocol.TX_SET_AUTO_VOICE -> {
                    val active = runCatching { data.readInt() != 0 }.getOrDefault(false)
                    val result = setAutoVoiceActive(active, { readSystemProperty(EXT_AMP_TYPE_PROP) }, ::audioServiceProxy)
                    reply?.writeInt(result.outcome); reply?.writeString(result.detail)
                    true
                }

                HelperBinderProtocol.TX_LAUNCH_AND_FORCE -> runCatching {
                    val pkg = data.readString() ?: ""
                    val displayId = data.readInt(); val width = data.readInt(); val height = data.readInt()
                    val ok = launchAndForce(pkg, displayId, width, height)
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_ENABLE_ACCESSIBILITY -> runCatching {
                    val ok = enableAccessibilityService()
                    // Only reached when the app found the service NOT running: snapshot the
                    // framework's own view so a field log names the exact state (#a11y DiLink 4).
                    // Off the binder thread: a slow dumpsys must not delay the reply (the client
                    // holds its mutex and a 15 s budget; a late reply would read as reassert=false).
                    logA11yFrameworkStateAsync(ok)
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_RECOVER_ACCESSIBILITY -> runCatching {
                    val ok = recoverAccessibilityService()
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_CLUSTER_DISPLAY_DIAG -> runCatching {
                    // Reply immediately: the snapshot runs 8-9 shell commands (up to 4 s each) on
                    // its own thread, so the binder thread and the app's HelperClient mutex are
                    // never held behind a slow dumpsys. Rate-limited, not once-only: the user
                    // usually retries after turning log recording on, and `logcat -c` at recording
                    // start wipes an earlier snapshot.
                    // Inside the rate window the cached snapshot is re-emitted instead, so a
                    // fresh recording session still gets the full cdiag block.
                    val now = android.os.SystemClock.elapsedRealtime()
                    val started = synchronized(clusterDiagLock) {
                        val running = clusterDiagThread?.isAlive == true
                        if (running) { clusterDiagReplayWhenDone = true; true }
                        else if (now - clusterDiagLastMs < CLUSTER_DIAG_MIN_INTERVAL_MS) {
                            val cached = clusterDiagCache
                            if (cached != null) {
                                android.util.Log.i("bydmate_helper", "cdiag: replaying cached snapshot (${cached.size} lines)")
                                cached.forEach { android.util.Log.i("bydmate_helper", it) }
                            }
                            cached != null
                        } else {
                            clusterDiagLastMs = now
                            clusterDiagThread = Thread({
                                val lines = runCatching { logClusterDisplayDiag() }.getOrNull()
                                val replay = synchronized(clusterDiagLock) {
                                    if (lines != null) clusterDiagCache = lines
                                    clusterDiagReplayWhenDone.also { clusterDiagReplayWhenDone = false }
                                }
                                // A request arrived mid-collection (typically: recording just
                                // started and wiped logcat) - emit the whole block once more.
                                if (replay && lines != null) {
                                    android.util.Log.i("bydmate_helper", "cdiag: replaying snapshot after collection (${lines.size} lines)")
                                    lines.forEach { android.util.Log.i("bydmate_helper", it) }
                                }
                            }, "bydmate-cdiag").apply { isDaemon = true; start() }
                            true
                        }
                    }
                    reply?.writeInt(if (started) 0 else 1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_LIST_DISPLAYS -> runCatching {
                    // Synchronous, unlike TX_CLUSTER_DISPLAY_DIAG: the caller is a projection
                    // attempt that cannot continue without the answer. One bounded dumpsys
                    // (~100-300 ms), read-only.
                    // 256 KiB, not the snapshot default: the "Logical Displays" section this
                    // parses comes AFTER the device list, so a dump cut at 64 KiB on a rich
                    // firmware would report no display at all.
                    val devices = ClusterDisplayDiag.parseDisplayDevices(
                        shExecBounded("dumpsys display", maxBytes = 256 * 1024))
                    android.util.Log.i("bydmate_helper", "TX_LIST_DISPLAYS: ${devices.size} displays " +
                        devices.joinToString { "${it.id}:\"${it.name}\" ${it.width}x${it.height}" }.take(300))
                    reply?.writeInt(0)
                    reply?.writeInt(devices.size)
                    devices.forEach { d ->
                        reply?.writeInt(d.id)
                        reply?.writeString(d.name)
                        reply?.writeInt(d.width)
                        reply?.writeInt(d.height)
                        reply?.writeInt(d.densityDpi)
                        reply?.writeString(d.ownerPkg ?: "")
                        reply?.writeInt(d.ownerUid)
                        reply?.writeString(d.flags.joinToString(","))
                    }
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_CLUSTER_WM_DIAG -> runCatching {
                    // Read-only readback for the direct-mode scale question: what WindowManager
                    // itself holds per display, and what configuration the projected activity last
                    // reported. Bounded like every other diagnostic dumpsys; `activity activities`
                    // gets 512 KiB because the ActivityRecord entries come late in that dump.
                    val pkg = data.readString() ?: ""
                    val displays = ClusterDisplayDiag.wmDisplayLines(
                        shExecBounded("dumpsys window displays", maxBytes = 256 * 1024))
                    val taskConfig = ClusterDisplayDiag.taskConfigLines(
                        shExecBounded("dumpsys activity activities", maxBytes = 512 * 1024), pkg)
                    android.util.Log.i("bydmate_helper", "TX_CLUSTER_WM_DIAG pkg=$pkg " +
                        "displays=${displays.size} task=${taskConfig.joinToString("; ").take(200)}")
                    reply?.writeInt(0)
                    reply?.writeString(displays.joinToString("\n"))
                    reply?.writeString(taskConfig.joinToString("\n"))
                    true
                }.getOrElse {
                    reply?.writeInt(-1); reply?.writeString(""); reply?.writeString(""); true
                }

                HelperBinderProtocol.TX_PUT_GLOBAL_SETTING -> runCatching {
                    val key = data.readString() ?: ""
                    val value = data.readInt()
                    // Hardcoded whitelist (globalSettingAllowed) — shell uid holds
                    // WRITE_SECURE_SETTINGS, so `settings put global` sticks; this is the
                    // privilege boundary and must not trust the caller, so both key AND value
                    // are bounded there. NOT a generic settings passthrough.
                    val ok = if (globalSettingAllowed(key, value)) {
                        shExec("settings put global \"\$1\" \"\$2\"", key, value.toString()).code == 0
                    } else false
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_GET_GLOBAL_SETTING -> runCatching {
                    val value = readGlobalSetting(data.readString() ?: "")
                    reply?.writeInt(if (value != null) 0 else -1); reply?.writeInt(value ?: 0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_ENABLE_NOTIFICATION_LISTENER -> runCatching {
                    val ok = enableNotificationListener()
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_DISALLOW_NOTIFICATION_LISTENER -> runCatching {
                    // Our own listener only: the component is the protocol constant, nothing from
                    // the caller. Revokes the approval so the following allow makes NMS bind anew.
                    val result = shExec("cmd notification disallow_listener \"\$1\"",
                        HelperBinderProtocol.NOTIFICATION_LISTENER_COMPONENT)
                    android.util.Log.i("bydmate_helper", "TX_DISALLOW_NOTIFICATION_LISTENER code=${result.code}")
                    reply?.writeInt(if (result.code == 0) 0 else -1)
                    true
                }.getOrElse { reply?.writeInt(-1); true }

                HelperBinderProtocol.TX_SET_APP_HIDDEN -> runCatching {
                    val pkg = data.readString() ?: ""
                    val hidden = data.readInt()    // 1 = disable, 0 = enable
                    // Hardcoded — ONLY the native BYD voice assistant family. Fully reversible
                    // (pm enable) and touches no firmware. NOT a generic package-disable passthrough;
                    // the caller may only name the launcher package or com.byd.vrassistant.
                    // Validate the flag daemon-side too — a privileged shell-uid op must not
                    // trust the caller. Only 0/1 are a defined state; reject anything else.
                    val ok = if (pkg == "com.byd.vrassistant" && hidden in 0..1) {
                        // The stock voice UI of Android 10 head units (DiLink 3.0), a single
                        // package; the app asks for it only there and only when it is installed.
                        // Same disable-user / enable pair as below, reversible.
                        val cmd = if (hidden == 1) "pm disable-user --user 0" else "pm enable"
                        shExec("$cmd \"\$1\"", pkg).code == 0
                    } else if (pkg == NativeAssistant.AUTOVOICE && hidden in 0..1) {
                        // `pm disable-user --user 0` force-stops the package and disables its
                        // components so the framework stops routing the steering voice button to it.
                        // `pm hide` left the already-running system assistant alive — the wheel
                        // button still woke it. The competitor uses disable-user and suppresses the
                        // assistant 100%; reversed with `pm enable`.
                        val cmd = if (hidden == 1) "pm disable-user --user 0" else "pm enable"
                        // The native assistant ships as a package FAMILY on Leopard 3: the launcher
                        // (com.byd.autovoice), the wake/recognition engine (.engine) that actually
                        // services the wheel mic button, and TTS output (.tts). Disabling only the
                        // launcher leaves the wheel button live, so we disable the whole family.
                        // Siblings come from NativeAssistant.AUTOVOICE_FAMILY, never caller input.
                        // Success is gated on BOTH the launcher and the wake engine; TTS is
                        // output-only and best-effort.
                        val primaryOk = shExec("$cmd \"\$1\"", pkg).code == 0
                        val engineOk = shExec("$cmd \"\$1\"", NativeAssistant.AUTOVOICE_ENGINE).code == 0
                        shExec("$cmd \"\$1\"", NativeAssistant.AUTOVOICE_TTS)
                        primaryOk && engineOk
                    } else false
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_CLUSTER_MODE -> {
                    val on = data.readInt() != 0
                    // Cluster compositor power (validated on Leopard 3, 2026-07-06): cmd 16 turns the
                    // compositor on; 18 then, after a beat, 0 turns it off. The whole off-sequence runs
                    // inside this one transaction so the client never has to sequence or sleep. The
                    // whitelist below is the ONLY set of auto_container commands this daemon will issue.
                    val ok = if (on) {
                        autoContainerCall(16)
                    } else {
                        val detached = autoContainerCall(18)
                        Thread.sleep(1000L)
                        autoContainerCall(0) && detached
                    }
                    reply?.writeInt(if (ok) 0 else -1)
                    reply?.writeInt(0)
                    true
                }

                HelperBinderProtocol.TX_SET_DISPLAY_DENSITY -> runCatching {
                    val displayId = data.readInt()
                    val density = data.readInt()
                    val result = setDisplayDensity(displayId, density)
                    reply?.writeInt(if (result.ok) 0 else -1); reply?.writeInt(0)
                    // 3rd field (additive): the `wm density -d <id>` readback. Old clients read the
                    // two ints and stop; parcel reads are sequential, so nothing shifts for them.
                    reply?.writeString(result.readback)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); reply?.writeString(""); true }

                HelperBinderProtocol.TX_GET_TASK_STATE -> runCatching {
                    val pkg = data.readString() ?: ""
                    val state = findTaskState(pkg)
                    reply?.writeInt(0)                           // status = ok
                    reply?.writeInt(state?.taskId ?: -1)         // -1 = no running task
                    reply?.writeInt(state?.windowingMode ?: 0)
                    reply?.writeInt(state?.left ?: 0)
                    reply?.writeInt(state?.top ?: 0)
                    reply?.writeInt(state?.right ?: 0)
                    reply?.writeInt(state?.bottom ?: 0)
                    // 7th int (additive): displayId. Old clients read only 6 ints and ignore the
                    // trailing value (parcel reads are sequential). New client against an old daemon
                    // sees no 7th int and defaults to 0 (main display; feature auto-disarms).
                    reply?.writeInt(state?.displayId ?: 0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_APP_SPLIT_SUPPORTED -> runCatching {
                    val pkg = data.readString() ?: ""
                    val result = queryStatusBarSplitSupport(pkg)
                    if (result < 0) {
                        reply?.writeInt(-1); reply?.writeInt(0)
                    } else {
                        reply?.writeInt(0); reply?.writeInt(result)
                    }
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_LAUNCH_FREEFORM -> {
                    // Parcel reads can throw too, so the log operands are captured as they arrive.
                    var logPkg = ""
                    var logDisplayId = -1
                    runCatching {
                        val pkg = data.readString() ?: ""
                        logPkg = pkg
                        val displayId = data.readInt()
                        logDisplayId = displayId
                        val l = data.readInt(); val t = data.readInt()
                        val r = data.readInt(); val b = data.readInt()
                        val status = launchFreeform(pkg, displayId, l, t, r, b, readTrailingPaneType(data))
                        reply?.writeInt(status); reply?.writeInt(0)
                        true
                    }.getOrElse { t ->
                        android.util.Log.w(
                            "bydmate_helper", "TX_LAUNCH_FREEFORM failed pkg=$logPkg display=$logDisplayId", t)
                        reply?.writeInt(-1); reply?.writeInt(0); true
                    }
                }

                HelperBinderProtocol.TX_FORCE_STOP -> runCatching {
                    val pkg = data.readString() ?: ""
                    forceStopPackage(pkg)
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_GET_VERSION -> runCatching {
                    // VERSION_CODE is frozen at spawn time: the daemon's CLASSPATH is fixed to the
                    // APK that spawned it, so this reflects the exact app version the daemon carries.
                    reply?.writeInt(0); reply?.writeInt(BuildConfig.VERSION_CODE)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_GET_TOP_PACKAGE -> runCatching {
                    val pkg = topTaskPackage() ?: ""
                    reply?.writeInt(0); reply?.writeString(pkg)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeString(""); true }

                HelperBinderProtocol.TX_RAISE_FREEFORM_TASK -> runCatching {
                    val pkg = data.readString() ?: ""
                    val displayId = data.readInt()
                    val ok = raiseFreeformTaskCore(
                        pkg, displayId, readTrailingPaneType(data), ::resolveLaunchComponent, amShell,
                        taskIdForPackage = { p -> findTaskState(p)?.taskId ?: -1 },
                        getActivityType = ::taskActivityType,
                        sleep = { ms -> Thread.sleep(ms) },
                        // Enables the non-destructive freeform probe before a retype remove.
                        stateOf = { ti -> taskModeState(ti) },
                    )
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_DUMP_FIDS -> runCatching {
                    val offset = data.readInt()
                    val dump: String = if (offset == 0) {
                        dumpFidsCore(classResolver = { runCatching { Class.forName(it) }.getOrNull() })
                            .also { dumpFidsCache = it }
                    } else {
                        dumpFidsCache
                            ?: dumpFidsCore(classResolver = { runCatching { Class.forName(it) }.getOrNull() })
                                .also { dumpFidsCache = it }
                    }
                    val dumpBytes = dump.toByteArray(Charsets.UTF_8)
                    val chunk = dumpFidsChunkBytes(dumpBytes, offset)
                    reply?.writeInt(0)
                    reply?.writeInt(dumpBytes.size)
                    reply?.writeByteArray(chunk)
                    true
                }.getOrElse { e ->
                    android.util.Log.e("bydmate_helper", "TX_DUMP_FIDS chunked: error", e)
                    // Write full protocol reply so the client reads status=-1 and returns ReadError,
                    // not BinderAbsent. Without totalLength+byteArray the client sees dataAvail()<8
                    // and maps the call to BinderAbsent ("демон недоступен"), which is a lie (Q4-1).
                    reply?.writeInt(-1); reply?.writeInt(0); reply?.writeByteArray(ByteArray(0))
                    true
                }

                HelperBinderProtocol.TX_GET_TOP_TASK -> runCatching {
                    val info = topTaskInfo()
                    if (info == null) {
                        reply?.writeInt(-1)
                    } else {
                        reply?.writeInt(0)
                        reply?.writeString(info.pkg)
                        reply?.writeInt(info.taskId)
                        reply?.writeInt(info.windowingMode)
                        reply?.writeInt(info.activityType)
                        reply?.writeInt(info.displayId)
                        // Last: a client that predates the field stops reading before it.
                        reply?.writeInt(info.visible)
                    }
                    true
                }.getOrElse { reply?.writeInt(-1); true }

                HelperBinderProtocol.TX_SPLIT37_ENTER -> runCatching {
                    val iAtm = activityTaskManager()
                    val enter = splitMethod(iAtm, "enterSplitMode", 0)
                    if (enter == null) {
                        android.util.Log.w("bydmate_helper", "TX_SPLIT37_ENTER: enterSplitMode absent")
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_UNSUPPORTED); reply?.writeInt(-1)
                    } else {
                        enter.invoke(iAtm)
                        // Read the area mode BEFORE the first write: a throw here must land in
                        // getOrElse with an untouched reply, not append a second status to it.
                        val areaMode = screenAreaMode(iAtm)
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_OK)
                        reply?.writeInt(areaMode)
                    }
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_ENTER failed", e)
                    reply?.writeInt(HelperBinderProtocol.SPLIT37_FAILED); reply?.writeInt(-1); true
                }

                HelperBinderProtocol.TX_SPLIT37_AREA_INFO -> runCatching {
                    val iAtm = activityTaskManager()
                    val byAreaId = splitMethod(iAtm, "getRootTaskIdByAreaId", 1)
                    if (byAreaId == null) {
                        android.util.Log.w("bydmate_helper", "TX_SPLIT37_AREA_INFO: getRootTaskIdByAreaId absent")
                        writeAreaInfoFailure(reply, HelperBinderProtocol.SPLIT37_UNSUPPORTED)
                    } else {
                        val areaMode = screenAreaMode(iAtm)
                        // One getAllRootTaskInfos() call per request: the watchdog polls this verb
                        // once a second, and all three areas are matched against the same snapshot.
                        val infos = rootTaskInfosSnapshot(iAtm)
                        val areas = SPLIT37_AREA_IDS.map { areaId ->
                            val rootTaskId = byAreaId.invoke(iAtm, areaId) as? Int ?: -1
                            // A root without readable bounds is reported as absent (id -1): the engine
                            // must not resize a pane to a rect it never read.
                            val bounds = if (rootTaskId > 0 && infos != null) rootTaskBounds(infos, rootTaskId) else null
                            if (bounds == null) intArrayOf(-1, 0, 0, 0, 0)
                            else intArrayOf(rootTaskId, bounds.left, bounds.top, bounds.right, bounds.bottom)
                        }
                        // Everything is read BEFORE the first write: a throw mid-reflection must land
                        // in getOrElse with an untouched reply, not leave a half-written one that the
                        // client would parse as real geometry.
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_OK)
                        reply?.writeInt(areaMode)
                        areas.forEach { area -> area.forEach { reply?.writeInt(it) } }
                    }
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_AREA_INFO failed", e)
                    writeAreaInfoFailure(reply, HelperBinderProtocol.SPLIT37_FAILED); true
                }

                HelperBinderProtocol.TX_SPLIT37_MOVE_TASK -> runCatching {
                    val taskId = data.readInt(); val rootTaskId = data.readInt()
                    val l = data.readInt(); val t = data.readInt(); val r = data.readInt(); val b = data.readInt()
                    val toTop = readTrailingToTop(data)
                    val status = split37MoveTaskCore(
                        taskId, rootTaskId, l, t, r, b, toTop, amShell, ::setTaskBoundsReflect,
                    )
                    reply?.writeInt(status); reply?.writeInt(0)
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_MOVE_TASK failed", e)
                    reply?.writeInt(HelperBinderProtocol.SPLIT37_FAILED); reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_SPLIT37_TASK_AREA -> runCatching {
                    val taskId = data.readInt()
                    val iAtm = activityTaskManager()
                    val forTask = splitMethod(iAtm, "getTaskAreaIdForMulti", 1)
                    if (forTask == null) {
                        android.util.Log.w("bydmate_helper", "TX_SPLIT37_TASK_AREA: getTaskAreaIdForMulti absent")
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_UNSUPPORTED); reply?.writeInt(-1)
                    } else {
                        // Invoke before the first write — see TX_SPLIT37_ENTER.
                        val areaId = forTask.invoke(iAtm, taskId) as? Int ?: -1
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_OK)
                        reply?.writeInt(areaId)
                    }
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_TASK_AREA failed", e)
                    reply?.writeInt(HelperBinderProtocol.SPLIT37_FAILED); reply?.writeInt(-1); true
                }

                HelperBinderProtocol.TX_SPLIT37_SWAP -> runCatching {
                    val iAtm = activityTaskManager()
                    // Exact vendor spelling, capital S — not a typo.
                    val swap = splitMethod(iAtm, "SwapSplitPosition", 0)
                    if (swap == null) {
                        android.util.Log.w("bydmate_helper", "TX_SPLIT37_SWAP: SwapSplitPosition absent")
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_UNSUPPORTED)
                    } else {
                        swap.invoke(iAtm)
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_OK)
                    }
                    reply?.writeInt(0)
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_SWAP failed", e)
                    reply?.writeInt(HelperBinderProtocol.SPLIT37_FAILED); reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_SPLIT37_CHANGE_MODE -> runCatching {
                    val mode = data.readInt()
                    val iAtm = activityTaskManager()
                    val change = splitMethod(iAtm, "changeSplitScreenMode", 1)
                    if (change == null) {
                        android.util.Log.w("bydmate_helper", "TX_SPLIT37_CHANGE_MODE: changeSplitScreenMode absent")
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_UNSUPPORTED); reply?.writeInt(-1)
                    } else {
                        change.invoke(iAtm, mode)
                        // Read the area mode BEFORE the first write — see TX_SPLIT37_ENTER.
                        val areaMode = screenAreaMode(iAtm)
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_OK)
                        reply?.writeInt(areaMode)
                    }
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_CHANGE_MODE failed", e)
                    reply?.writeInt(HelperBinderProtocol.SPLIT37_FAILED); reply?.writeInt(-1); true
                }

                HelperBinderProtocol.TX_SPLIT37_ROOT_STATE -> runCatching {
                    val rootTaskId = data.readInt(); val taskId = data.readInt()
                    val iAtm = activityTaskManager()
                    val infos = rootTaskInfosSnapshot(iAtm)
                    if (infos == null) {
                        android.util.Log.w("bydmate_helper", "TX_SPLIT37_ROOT_STATE: getAllRootTaskInfos absent")
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_UNSUPPORTED)
                    } else {
                        // Read before the first write — see TX_SPLIT37_ENTER.
                        val reading = split37RootStateReading(infos, rootTaskId, atmGetTasks(iAtm, 100), taskId)
                        reply?.writeInt(HelperBinderProtocol.SPLIT37_OK)
                        reply?.let { writeSplit37RootState(it, reading) }
                    }
                    true
                }.getOrElse { e ->
                    android.util.Log.w("bydmate_helper", "TX_SPLIT37_ROOT_STATE failed", e)
                    reply?.writeInt(HelperBinderProtocol.SPLIT37_FAILED); true
                }

                HelperBinderProtocol.TX_REGISTER_CLIENT -> runCatching {
                    registerHelperClient(data.readStrongBinder())
                    reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); true }

                HelperBinderProtocol.TX_PUSH_SUBSCRIBE -> runCatching {
                    val callback = data.readStrongBinder()
                    val subs = readSubscribeRequest(data)
                    val results = FidPushRegistry.subscribe(subs, callback, Binder.getCallingUid())
                    reply?.writeInt(0)
                    reply?.let { writeResultTable(it, results) }
                    true
                }.getOrElse { e ->
                    android.util.Log.w("FidPush", "TX_PUSH_SUBSCRIBE failed", e)
                    reply?.writeInt(-1); reply?.writeInt(0); true
                }

                HelperBinderProtocol.TX_PUSH_UNSUBSCRIBE -> runCatching {
                    reply?.writeInt(0)
                    reply?.writeInt(FidPushRegistry.unsubscribe())
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_PUSH_STATUS -> runCatching {
                    reply?.writeInt(0)
                    reply?.let { writeStatusTable(it, FidPushRegistry.status()) }
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_REC_START,
                HelperBinderProtocol.TX_REC_STOP,
                HelperBinderProtocol.TX_REC_STATUS -> handleRecorderTransact(code, data, reply)

                HelperBinderProtocol.TX_OFFREPORT_ARM,
                HelperBinderProtocol.TX_OFFREPORT_DISARM,
                HelperBinderProtocol.TX_OFFREPORT_STATUS -> handleOffReportTransact(code, data, reply)

                HelperBinderProtocol.TX_HUD_NAVI_STATUS,
                HelperBinderProtocol.TX_HUD_SDK,
                HelperBinderProtocol.TX_WRITE_BUFFER -> handleHudTransact(code, data, reply, svc, autoIface)

                HelperBinderProtocol.TX_DTC_SCAN_START,
                HelperBinderProtocol.TX_DTC_SCAN_STATUS,
                HelperBinderProtocol.TX_DTC_ERASE_START -> handleDtcScanTransact(code, data, reply, svc, autoIface)

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
    helperBinder.attachInterface(null, HelperBinderProtocol.DESCRIPTOR)

    // Step 4: register the stub with ServiceManager, or publish it by broadcast when the
    // firmware refuses the registration to the shell domain (#64 / #148).
    var transport = "servicemanager"
    try {
        smCls.getMethod("addService", String::class.java, IBinder::class.java)
            .invoke(null, HelperBinderProtocol.SERVICE_NAME, helperBinder)
    } catch (e: Exception) {
        System.err.println("ERR: addService ${describeBootstrapFailure(e)}")
        when (decideRegistrationFallback(systemContext != null, spawnToken != null)) {
            RegistrationFallback.EXIT_NO_CONTEXT -> {
                System.err.println("ERR: broadcast fallback unavailable: no system context")
                // exitProcess so the OS releases the file lock we hold; a bare return would
                // leave a hung lock-holding daemon and block every future spawn.
                exitProcess(4)
            }
            RegistrationFallback.EXIT_NO_TOKEN -> {
                System.err.println("ERR: broadcast fallback unavailable: no spawn token")
                exitProcess(4)
            }
            RegistrationFallback.BROADCAST -> try {
                publishBinderByBroadcast(systemContext!!, helperBinder, spawnToken!!)
                transport = HelperBinderHolder.TRANSPORT_BROADCAST
                // One delivery is not enough: the app process is recreated (service restart, task
                // removed, memory pressure) far more often than the daemon, and a recreated process
                // has no binder. Keep announcing until a client registers (#64/#148).
                scheduleReannounce(systemContext!!, helperBinder, spawnToken!!)
            } catch (be: Exception) {
                System.err.println("ERR: broadcast ${describeBootstrapFailure(be)}")
                exitProcess(5)
            }
        }
    }

    System.out.println("READY via=$transport pid=${android.os.Process.myPid()}")
    System.out.flush()

    // Keepalive: Looper.loop() blocks this thread indefinitely so main() never returns
    // and the process stays alive. Incoming binder transactions are dispatched by the
    // binder threadpool that app_process starts via ProcessState::startThreadPool() in
    // AppRuntime::onStarted — Looper.loop() plays NO role in transaction dispatch here;
    // it is purely a blocking keepalive for the main thread.
    Looper.loop()
}

/**
 * Spawn token passed as args[1]. Absent (or blank) when an app version older than the
 * broadcast fallback did the spawn — then only the addService path can deliver the Binder.
 */
internal fun spawnTokenFrom(args: Array<String>): String? =
    args.getOrNull(1)?.takeIf { it.isNotBlank() }

/** What to do after ServiceManager.addService refused us — see [decideRegistrationFallback]. */
internal enum class RegistrationFallback {
    /** No system Context: nothing can send a broadcast from this process. */
    EXIT_NO_CONTEXT,
    /** No spawn token: the app could not authenticate the intent, so we must not send one. */
    EXIT_NO_TOKEN,
    /** Hand the Binder to the app in a broadcast. */
    BROADCAST,
}

/**
 * The whole decision after a refused addService, split out from the Android-dependent sender
 * so it can be unit-tested. Both preconditions are hard: without a Context there is no
 * sendBroadcast, and without a token the app has no way to tell our intent from a forged one
 * (the receiver is exported — the sender is the shell uid, not our own process).
 */
internal fun decideRegistrationFallback(hasSystemContext: Boolean, hasToken: Boolean): RegistrationFallback =
    when {
        !hasSystemContext -> RegistrationFallback.EXIT_NO_CONTEXT
        !hasToken -> RegistrationFallback.EXIT_NO_TOKEN
        else -> RegistrationFallback.BROADCAST
    }

/**
 * Hands [binder] to the app in an explicit broadcast — the D+ (aps_diplus) recipe for firmwares
 * where the shell domain may transact with autoservice but may not register a service name.
 *
 * Explicit component + package so no other app can receive it, and on the FIRST delivery
 * FLAG_INCLUDE_STOPPED_PACKAGES so a force-stopped app (the state right after an update) is still
 * woken. The token lets the app authenticate the sender; version and pid are diagnostics for the dump.
 */
private fun publishBinderByBroadcast(
    ctx: Context,
    binder: IBinder,
    token: String,
    includeStopped: Boolean = true,
) {
    val extras = Bundle().apply {
        putBinder(HelperBinderProtocol.KEY_BINDER, binder)
        putString(HelperBinderProtocol.KEY_TOKEN, token)
        putLong(HelperBinderProtocol.KEY_VERSION, BuildConfig.VERSION_CODE.toLong())
        putInt(HelperBinderProtocol.KEY_PID, android.os.Process.myPid())
    }
    val intent = Intent(HelperBinderProtocol.ACTION_BINDER)
        .setComponent(ComponentName(HelperBinderProtocol.APP_PACKAGE, HelperBinderProtocol.RECEIVER_CLASS))
        .setPackage(HelperBinderProtocol.APP_PACKAGE)
        .addFlags(binderIntentFlags(includeStopped))
        .putExtra(HelperBinderProtocol.EXTRA_BUNDLE, extras)
    ctx.sendBroadcast(intent)
}

/** App-side binder registered over TX_REGISTER_CLIENT; null while no app process holds ours. */
@Volatile private var helperClientBinder: IBinder? = null

/** Re-announce broadcasts sent since the last client registration — diagnostic counter only. */
@Volatile private var reannounceCount = 0

/** Re-arms the re-announce timer once the registered client dies; null on the addService path. */
@Volatile private var resumeReannounce: (() -> Unit)? = null

/**
 * Whether the binder still has to be announced. Only the broadcast transport needs it at all —
 * on firmwares where addService worked the app re-looks the daemon up by name after any restart.
 */
internal fun shouldReannounce(transport: String, clientRegistered: Boolean): Boolean =
    transport == HelperBinderHolder.TRANSPORT_BROADCAST && !clientRegistered

/**
 * Repeats [publishBinderByBroadcast] every [REANNOUNCE_INTERVAL_MS] while no app process has
 * registered. Uncapped on purpose: one explicit intent per 10 s costs nothing, and it is
 * self-limiting — a running app registers within seconds of receiving the binder.
 */
private fun scheduleReannounce(ctx: Context, binder: IBinder, token: String) {
    val handler = android.os.Handler(Looper.getMainLooper())
    val tick = object : Runnable {
        override fun run() {
            if (!shouldReannounce(HelperBinderHolder.TRANSPORT_BROADCAST, helperClientBinder != null)) return
            // includeStopped = false: the user force-stopping the app kills our client binder and
            // resumes this timer — waking the app back up would override that stop. A process
            // restarted normally still receives it.
            runCatching { publishBinderByBroadcast(ctx, binder, token, includeStopped = false) }
            reannounceCount++
            println("REANNOUNCE n=$reannounceCount")
            System.out.flush()
            handler.postDelayed(this, REANNOUNCE_INTERVAL_MS)
        }
    }
    resumeReannounce = {
        // removeCallbacks first: a register+die in the same interval could otherwise leave two
        // chains posting in parallel.
        handler.removeCallbacks(tick)
        handler.postDelayed(tick, REANNOUNCE_INTERVAL_MS)
    }
    handler.postDelayed(tick, REANNOUNCE_INTERVAL_MS)
}

/** TX_REGISTER_CLIENT body: remember the app's binder and stop announcing until it dies. */
private fun registerHelperClient(client: IBinder?) {
    if (client == null) return
    helperClientBinder = client
    reannounceCount = 0
    println("CLIENT registered")
    System.out.flush()
    runCatching {
        client.linkToDeath({
            helperClientBinder = null
            println("CLIENT died")
            System.out.flush()
            resumeReannounce?.invoke()
        }, 0)
    }.onFailure {
        // Already dead between the transact and the link — that is no client at all.
        helperClientBinder = null
        resumeReannounce?.invoke()
    }
}

/**
 * Intent flags of the binder broadcast. Only the first delivery may wake a stopped package: every
 * re-announce that did so would restart an app the user just force-stopped.
 */
internal fun binderIntentFlags(includeStopped: Boolean): Int =
    if (includeStopped) Intent.FLAG_INCLUDE_STOPPED_PACKAGES else 0

// Trailing activityType int: absent when the caller predates the split touch fix →
// legacy RECENTS. Values other than STANDARD are coerced to RECENTS (conservative).
internal fun readTrailingPaneType(data: Parcel): Int {
    val raw = if (data.dataAvail() > 0) data.readInt() else ACTIVITY_TYPE_RECENTS
    return if (raw == ACTIVITY_TYPE_STANDARD) ACTIVITY_TYPE_STANDARD else ACTIVITY_TYPE_RECENTS
}

/**
 * Trailing toTop int of TX_SPLIT37_MOVE_TASK: absent when the caller predates the bounce (A-2) →
 * onTop, the behavior every move had before it.
 */
internal fun readTrailingToTop(data: Parcel): Boolean =
    if (data.dataAvail() > 0) data.readInt() != 0 else true

/**
 * Performs a single autoservice Binder transact and returns (status, retInt).
 *
 * [tx] is the transact code (5=getInt, 6=setInt, 7=getDouble raw int, 9=getBuffer).
 * [writeValue] controls whether [value] is written into the parcel (true for setInt/tx=6 only).
 *
 * Semantics match the proven logic from the TCP daemon:
 *   avail >= 4 → status = reply.readInt() else -999
 *   avail >= 8 → retInt = reply.readInt() else 0
 */
private fun autoserviceTransact(
    svc: IBinder,
    autoIface: String,
    tx: Int,
    dev: Int,
    fid: Int,
    value: Int,
    writeValue: Boolean
): Pair<Int, Int> {
    val data2 = Parcel.obtain()
    val reply2 = Parcel.obtain()
    return try {
        data2.writeInterfaceToken(autoIface)
        data2.writeInt(dev)
        data2.writeInt(fid)
        if (writeValue) data2.writeInt(value)
        svc.transact(tx, data2, reply2, 0)
        val avail = reply2.dataAvail()
        val status = if (avail >= 4) reply2.readInt() else -999
        val retInt = if (avail >= 8) reply2.readInt() else 0
        status to retInt
    } finally {
        data2.recycle()
        reply2.recycle()
    }
}

/**
 * Buffer sibling of [autoserviceTransact]: autoservice setBuffer (tx 14), whose parcel carries a
 * byte array instead of an int. [bytes] goes out exactly as given; the instrument's road-name
 * field wants UTF-16LE without a BOM, and that encoding is the caller's business.
 *
 * Same (status, retInt) semantics as [autoserviceTransact].
 */
private fun autoserviceTransactBuffer(
    svc: IBinder,
    autoIface: String,
    dev: Int,
    fid: Int,
    bytes: ByteArray
): Pair<Int, Int> {
    val data2 = Parcel.obtain()
    val reply2 = Parcel.obtain()
    return try {
        data2.writeInterfaceToken(autoIface)
        data2.writeInt(dev)
        data2.writeInt(fid)
        data2.writeByteArray(bytes)
        svc.transact(14, data2, reply2, 0)
        val avail = reply2.dataAvail()
        val status = if (avail >= 4) reply2.readInt() else -999
        val retInt = if (avail >= 8) reply2.readInt() else 0
        status to retInt
    } finally {
        data2.recycle()
        reply2.recycle()
    }
}

/**
 * The HUD verbs, out of line like the recorder's: the SDK navigation status call, way 3's SDK
 * calls and the setBuffer write. Every reply starts with a status int; a failure costs this call only.
 */
private fun handleHudTransact(code: Int, data: Parcel, reply: Parcel?, svc: IBinder, autoIface: String): Boolean {
    if (code == HelperBinderProtocol.TX_HUD_NAVI_STATUS) {
        val status = runCatching { data.readInt() }.getOrDefault(HelperBinderProtocol.HUD_NAVI_PROBE)
        val (outcome, sdkReturn) = sendAutoNaviStatusOutcome(status) {
            deviceInstance(FID_PUSH_DEVICE_CLASSES.getValue(HUD_NAVI_DEVICE))
        }
        reply?.writeInt(outcome)
        reply?.writeInt(sdkReturn)
        return true
    }
    if (code == HelperBinderProtocol.TX_HUD_SDK) {
        val (outcome, sdkReturn) = runCatching { readHudSdkInvocation(data) }.fold(
            onSuccess = { call ->
                call?.let { hudSdkOutcome(it) { deviceInstance(FID_PUSH_DEVICE_CLASSES.getValue(HUD_NAVI_DEVICE)) } }
                    ?: (HelperBinderProtocol.HUD_NAVI_ABSENT to 0)
            },
            onFailure = { HelperBinderProtocol.HUD_NAVI_THREW to 0 },
        )
        reply?.writeInt(outcome)
        reply?.writeInt(sdkReturn)
        return true
    }
    runCatching {
        val dev = data.readInt()
        val fid = data.readInt()
        val bytes = data.createByteArray() ?: ByteArray(0)
        val (status, _) = autoserviceTransactBuffer(svc, autoIface, dev, fid, bytes)
        reply?.writeInt(status)
        reply?.writeInt(0)
    }.onFailure {
        reply?.setDataSize(0)
        reply?.writeInt(-1); reply?.writeInt(0)
    }
    return true
}

/** The instrument device, owner of sendAutoNaviStatus. */
private const val HUD_NAVI_DEVICE = 1007

/**
 * TX_HUD_NAVI_STATUS body: `sendAutoNaviStatus(int)` on the device [device] hands back, looked up
 * by reflection because the SDK class exists only on the head unit. Returns (outcome, sdkReturn):
 * [HelperBinderProtocol.HUD_NAVI_PROBE] only reports whether the method exists. A throw (no
 * system Context, the device refusing, the SDK's own SecurityException) is logged by class name
 * and reported, never propagated: the app then writes the fid raw.
 */
internal fun sendAutoNaviStatusOutcome(status: Int, device: () -> Any): Pair<Int, Int> {
    val target = runCatching(device).getOrElse { e ->
        android.util.Log.w(HUD_NAVI_TAG, "sendAutoNaviStatus: no device: ${unwrapReflectionCause(e).javaClass.simpleName}")
        return HelperBinderProtocol.HUD_NAVI_THREW to 0
    }
    val method = runCatching {
        target.javaClass.getMethod("sendAutoNaviStatus", Int::class.javaPrimitiveType)
    }.getOrNull() ?: return HelperBinderProtocol.HUD_NAVI_ABSENT to 0
    if (status == HelperBinderProtocol.HUD_NAVI_PROBE) return HelperBinderProtocol.HUD_NAVI_CALLED to 0
    return runCatching { HelperBinderProtocol.HUD_NAVI_CALLED to (method.invoke(target, status) as? Int ?: 0) }
        .getOrElse { e ->
            // The SDK's message is its own verdict ("[setInt] permission deny!"), no user data.
            val root = unwrapReflectionCause(e)
            android.util.Log.w(HUD_NAVI_TAG, "sendAutoNaviStatus($status) threw ${root.javaClass.simpleName}: ${root.message}")
            HelperBinderProtocol.HUD_NAVI_THREW to 0
        }
}

private const val HUD_NAVI_TAG = "HudArming"

/** One TX_HUD_SDK call: the instrument method's name, its parameter types and the arguments. */
internal class HudSdkInvocation(val method: String, val types: Array<Class<*>>, val args: Array<Any>) {
    companion object {
        private val INT = Int::class.javaPrimitiveType!!
        fun guidance(icon: Int, distance: Int) = HudSdkInvocation("sendSimpleGuidanceInfo", arrayOf(INT, INT), arrayOf(icon, distance))
        fun pathName(name: String) = HudSdkInvocation("sendNextPathName", arrayOf(String::class.java), arrayOf(name))
        fun restRoute(hours: Int, minutes: Int, mileage: Long) = HudSdkInvocation(
            "sendRestRouteInfo", arrayOf(INT, INT, Long::class.javaPrimitiveType!!), arrayOf(hours, minutes, mileage),
        )
    }
}

/** TX_HUD_SDK request (see HudSdkCall.writeTo); null for a method this daemon does not know. */
internal fun readHudSdkInvocation(data: Parcel): HudSdkInvocation? = when (data.readInt()) {
    HelperBinderProtocol.HUD_SDK_GUIDANCE -> HudSdkInvocation.guidance(data.readInt(), data.readInt())
    HelperBinderProtocol.HUD_SDK_PATH_NAME -> HudSdkInvocation.pathName(data.readString().orEmpty())
    HelperBinderProtocol.HUD_SDK_REST_ROUTE -> HudSdkInvocation.restRoute(data.readInt(), data.readInt(), data.readLong())
    else -> null
}

/**
 * TX_HUD_SDK body: [call] on the device [device] hands back, looked up by reflection like
 * [sendAutoNaviStatusOutcome]. Returns (outcome, sdkReturn); a throw is logged by class name only
 * (the road name is user data, the SDK may echo it) and reported, never propagated.
 */
@Suppress("SpreadOperator") // two or three arguments per call
internal fun hudSdkOutcome(call: HudSdkInvocation, device: () -> Any): Pair<Int, Int> {
    val target = runCatching(device).getOrElse { e ->
        android.util.Log.w(HUD_SDK_TAG, "${call.method}: no device: ${unwrapReflectionCause(e).javaClass.simpleName}")
        return HelperBinderProtocol.HUD_NAVI_THREW to 0
    }
    val method = runCatching { target.javaClass.getMethod(call.method, *call.types) }.getOrNull()
        ?: return HelperBinderProtocol.HUD_NAVI_ABSENT to 0
    return runCatching { HelperBinderProtocol.HUD_NAVI_CALLED to (method.invoke(target, *call.args) as? Int ?: 0) }
        .getOrElse { e ->
            android.util.Log.w(HUD_SDK_TAG, "${call.method} threw ${unwrapReflectionCause(e).javaClass.simpleName}")
            HelperBinderProtocol.HUD_NAVI_THREW to 0
        }
}

private const val HUD_SDK_TAG = "HudWayChannels"

/**
 * TX_READ_BATCH body. Reads `count` then count × (tx, dev, fid) triples from [data],
 * invokes [doTransact] per triple, writes `count` then count × (status, value) pairs
 * to [reply]. A throwing [doTransact] yields (-998, 0) for that item and the batch
 * continues — one bad fid must not poison the other 57. Invalid count → single 0.
 */
internal fun readBatchIntoReply(
    data: Parcel,
    reply: Parcel?,
    doTransact: (tx: Int, dev: Int, fid: Int) -> Pair<Int, Int>
) {
    val n = data.readInt()
    if (n < 1 || n > HelperBinderProtocol.MAX_BATCH_ITEMS) {
        reply?.writeInt(0)
        return
    }
    val triples = IntArray(n * 3)
    for (i in 0 until n * 3) triples[i] = data.readInt()
    reply?.writeInt(n)
    for (i in 0 until n) {
        val (status, retInt) = runCatching {
            doTransact(triples[i * 3], triples[i * 3 + 1], triples[i * 3 + 2])
        }.getOrElse { -998 to 0 }
        reply?.writeInt(status)
        reply?.writeInt(retInt)
    }
}

/**
 * Force-stops [packageName] via IActivityManager.forceStopPackage(pkg, userId=0).
 * Shell uid holds FORCE_STOP_PACKAGES. Fail-soft: any reflection failure throws (caller wraps
 * in runCatching). Used before freeform re-launch to clear a stale fullscreen invisible task
 * that resists windowing-mode changes (on-car: pressing Home leaves the app in fullscreen/invisible
 * state; am start --windowingMode 5 cannot coerce it; only force-stop + fresh start succeeds).
 *
 * Hidden-API note: daemon runs under app_process — reflection without HiddenApiBypass is fine.
 */
private fun forceStopPackage(packageName: String) {
    val amCls = Class.forName("android.app.ActivityManager")
    val iAm = amCls.getMethod("getService").invoke(null)
        ?: throw IllegalStateException("ActivityManager.getService() returned null")
    iAm.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
        .invoke(iAm, packageName, 0)
}

/**
 * Queries IStatusBarService.isAppSuportSplit (BYD extension, transaction code 82) for [packageName].
 * Shell uid holds STATUS_BAR, so this transact is permitted on DiLink head units.
 * Returns 0 or 1 on success; -1 when the statusbar service is unavailable or the call throws.
 */
private fun queryStatusBarSplitSupport(packageName: String): Int = runCatching {
    val smCls = Class.forName("android.os.ServiceManager")
    val statusBar: IBinder = smCls.getMethod("getService", String::class.java)
        .invoke(null, "statusbar") as? IBinder
        ?: return@runCatching -1
    val req = Parcel.obtain()
    val rep = Parcel.obtain()
    try {
        req.writeInterfaceToken("com.android.internal.statusbar.IStatusBarService")
        req.writeString(packageName)
        // transact returns false when the service does not handle this code (BYD extension absent).
        // An empty reply in that case would let readException/readInt silently return 0 → wrong false.
        if (!statusBar.transact(82, req, rep, 0)) return@runCatching -1
        rep.readException()
        rep.readInt()
    } finally {
        req.recycle()
        rep.recycle()
    }
}.getOrElse { -1 }

private fun logPushContext(ctx: Context?) {
    android.util.Log.i("FidPush", "system context for device classes: ${if (ctx != null) "ok" else "MISSING"}")
}

/**
 * Acquires a system Context via ActivityThread, exactly as OpenBYD's EntryPoint does.
 * Returns null on failure — read paths that need it degrade to an error status rather
 * than crashing the daemon (autoservice read/write do NOT need a Context and keep working).
 *
 * Reflection (no HiddenApiBypass): the daemon runs under app_process, where hidden-API
 * enforcement is inactive.
 */
private fun acquireSystemContext(): Context? = try {
    val atCls = Class.forName("android.app.ActivityThread")
    val activityThread = atCls.getMethod("systemMain").invoke(null)
    atCls.getMethod("getSystemContext").invoke(activityThread) as? Context
} catch (e: Throwable) {
    System.err.println("WARN: systemContext unavailable: ${e.message}")
    null
}

/**
 * Creates a VirtualDisplay backed by [surface]. Uses a com.android.shell package Context so the
 * shell uid's privilege applies to the requested [flags] (incl. TRUSTED / SYSTEM_DECORATIONS).
 * Returns the new displayId (>0) on success, or -1 (releasing any invalid display). Mirrors
 * CarControlImpl.createVirtualDisplay.
 */
private fun createVirtualDisplay(
    ctx: Context,
    store: ConcurrentHashMap<Int, VirtualDisplay>,
    name: String, width: Int, height: Int, density: Int, surface: Surface, flags: Int,
): Int {
    val shellCtx = ctx.createPackageContext("com.android.shell", 0)
    val dm = shellCtx.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    val vd = dm.createVirtualDisplay(name, width, height, density, surface, flags) ?: return -1
    val id = vd.display?.displayId ?: -1
    if (id <= 0) { vd.release(); return -1 }
    store[id] = vd
    return id
}

/** Snapshot rate limit (see TX_CLUSTER_DISPLAY_DIAG): one at a time, at most one per minute. */
private val clusterDiagLock = Any()
private var clusterDiagThread: Thread? = null
private var clusterDiagLastMs = Long.MIN_VALUE / 2
private var clusterDiagCache: List<String>? = null
private var clusterDiagReplayWhenDone = false
private const val CLUSTER_DIAG_MIN_INTERVAL_MS = 60_000L

/**
 * Read-only cluster-display snapshot for cars where the projection display never resolves
 * (DiLink 3/4, issue #182). Collection only: props, the DisplayManager and SurfaceFlinger display
 * lists, the projection services and which SurfaceControl methods are visible under shell uid.
 * Nothing is invoked or written — no auto_container command, no SurfaceControl call, no settings.
 * Every command is individually guarded so one failure still leaves the rest of the snapshot in
 * the log, under the daemon tag the app's log recorder already captures.
 */
private fun logClusterDisplayDiag(): List<String> {
    val tag = "bydmate_helper"
    val emitted = ArrayList<String>()
    var cmds = 0
    var errors = 0
    var timeouts = 0
    fun run(script: String, timeoutMs: Long = 4000L, maxBytes: Int = 64 * 1024): String {
        cmds++
        val out = runCatching { shExecBounded(script, timeoutMs, maxBytes) }
            .getOrElse { errors++; "" }
        if (out.contains("[timeout ")) timeouts++
        return out
    }
    fun emit(line: String) {
        val full = "cdiag: " + line.take(300)
        emitted += full
        android.util.Log.i(tag, full)
    }

    val propScript = ClusterDisplayDiag.PROP_KEYS.joinToString("; ") { "echo $it=$(getprop $it)" }
    emit(ClusterDisplayDiag.propsLine(run(propScript)) + " uid=${android.os.Process.myUid()}")
    emit(ClusterDisplayDiag.bydPropsLine(run("getprop | grep '^\\[ro\\.byd\\.'")))
    emit(ClusterDisplayDiag.servicesLine(
        run("service list | grep -i container"),
        run("service check auto_container"),
        run("service check AutoContainer"),
        run("ls /dev/graphics 2>/dev/null | tr '\\n' ' '"),
    ))

    val rawDisplay = run("dumpsys display")
    ClusterDisplayDiag.displaySummaries(rawDisplay).forEach { emit("display: $it") }
    val displays = ClusterDisplayDiag.displayLines(rawDisplay)
    displays.kept.forEach { emit("display: $it") }
    if (displays.dropped > 0) emit("display: +${displays.dropped} more matched lines dropped")

    var sf = run("dumpsys SurfaceFlinger --displays")
    if (ClusterDisplayDiag.surfaceFlingerFallbackNeeded(sf)) sf = run("dumpsys SurfaceFlinger")
    val sfLines = ClusterDisplayDiag.surfaceFlingerLines(sf)
    sfLines.kept.forEach { emit("sf: $it") }
    if (sfLines.dropped > 0) emit("sf: +${sfLines.dropped} more matched lines dropped")

    // Reflective visibility only: we report which SurfaceControl entry points this firmware has,
    // and never call any of them. Hidden-API filtering can throw on some builds, hence the guard.
    val probed = listOf(
        "createDisplay", "destroyDisplay", "setDisplaySurface", "setDisplayProjection",
        "setDisplayLayerStack", "getPhysicalDisplayIds", "getPhysicalDisplayToken", "getBuiltInDisplay",
    )
    runCatching {
        val names = Class.forName("android.view.SurfaceControl").declaredMethods.map { it.name }.toSet()
        val (present, missing) = probed.partition { it in names }
        emit("surfacecontrol present=$present missing=$missing")
    }.onFailure { emit("surfacecontrol unavailable: ${it.javaClass.simpleName}") }

    emit("done cmds=$cmds errors=$errors timeouts=$timeouts")
    return emitted
}

/** Single named gateway: pins all am/monkey prod callers to the merged-stream runner.
 *  Extracting a val (not an inline lambda at each site) means the wiring is detectable
 *  by tests — reverting any site back to shExec would break [ShExecMergedTest.amShell]. */
internal val amShell: (String, List<String>) -> String =
    { script, args -> shExecMerged(script, *args.toTypedArray()) }

/** The single gateway for auto_container: hard whitelist {16, 18, 0}, device id fixed
 *  at 1000. Anything else is a programming error, not a runtime input. */
private fun autoContainerCall(cmd: Int): Boolean =
    autoContainerCall(cmd) { script, arg -> shExec(script, arg).code }

/** Testable core: [exec] runs a shell script with one positional arg and returns its exit code. */
internal fun autoContainerCall(cmd: Int, exec: (String, String) -> Int): Boolean {
    require(cmd == 16 || cmd == 18 || cmd == 0) { "auto_container cmd $cmd not whitelisted" }
    if (exec("service call auto_container 2 i32 1000 i32 \"$1\" s16 \"\"", cmd.toString()) == 0) return true
    // DiLink 3 registers the cluster compositor under a CamelCase service name
    // (hint from @klimuts, PR #77). Leopard 3 / DiLink 5 answers on snake_case above,
    // and `service call` exits non-zero on a missing service, so this second attempt
    // never runs on platforms where the snake_case name works.
    return exec("service call AutoContainer 2 i32 1000 i32 \"$1\" s16 \"\"", cmd.toString()) == 0
}

/**
 * Outcome of the `wm density` op: [ok] is the exit status of the set/reset command, [readback] is
 * what `wm density -d <id>` printed right after it (Android 10: `Physical density: N` plus
 * `Override density: M` when an override is in force), flattened onto one line. Empty when the
 * command was rejected before running or the readback printed nothing.
 */
internal data class DensityCommandResult(val ok: Boolean, val readback: String)

/**
 * Testable core of the `wm density` op. [displayId] must be a NON-default display — the main
 * screen must never be rescaled by this daemon. [density] 0 = reset, otherwise sane wm bounds.
 * [exec] runs a shell script with positional args and returns its exit code plus merged output.
 *
 * The set is always followed by a readback: on DiLink 4.0 the set returns rc=0 while the cluster
 * picture does not change, and only `wm density -d <id>` says whether WindowManager took the
 * override at all.
 */
internal fun setDisplayDensityCore(
    displayId: Int, density: Int, exec: (String, List<String>) -> CmdResult,
): DensityCommandResult {
    if (displayId !in 1..63) return DensityCommandResult(false, "")
    if (density != 0 && density !in 80..640) return DensityCommandResult(false, "")
    val set = if (density == 0) {
        exec("wm density reset -d \"\$1\"", listOf(displayId.toString()))
    } else {
        exec("wm density \"\$1\" -d \"\$2\"", listOf(density.toString(), displayId.toString()))
    }
    val label = if (density == 0) "reset" else density.toString()
    android.util.Log.i("bydmate_helper", "wm density set display=$displayId density=$label " +
        "rc=${set.code} out=\"${flattenOutput(set.stdout, 120, " / ")}\"")
    val readback = flattenOutput(
        exec("wm density -d \"\$1\"", listOf(displayId.toString())).stdout, 160, "; ")
    android.util.Log.i("bydmate_helper", "wm density readback display=$displayId: $readback")
    return DensityCommandResult(set.code == 0, readback)
}

/** Command output onto one log line: newlines become [separator], the result is capped at [max]. */
private fun flattenOutput(raw: String, max: Int, separator: String): String =
    raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(separator).take(max)

private fun setDisplayDensity(displayId: Int, density: Int): DensityCommandResult =
    setDisplayDensityCore(displayId, density) { script, args ->
        shExecMergedResult(script, *args.toTypedArray())
    }

/**
 * Enables our steering-wheel accessibility service so it starts filtering steering-wheel keys.
 * DiLink has no a11y settings UI, so the user cannot toggle it; we do it under shell uid via the
 * `settings` binary (the in-process Settings.Secure ContentResolver does not stick for an
 * app_process-spawned daemon).
 *
 * Force re-bind, mirroring OpenBYD AccessibilitySetupHelper.buildEnableCommands: write the list
 * WITHOUT our component, pause, then write it back WITH our component, then accessibility_enabled=1.
 * A plain append does NOT make the framework bind a service that is already listed but not running
 * (the common case after an APK reinstall/upgrade) — the remove+re-add is what triggers the bind.
 * Read-modify-write preserves other apps' services (our component is the only one we ever touch).
 * App-scoped, reversible Secure settings only; touches nothing on the vehicle (no autoservice/CAN).
 */
private fun enableAccessibilityService(): Boolean {
    val component = HelperBinderProtocol.ACCESSIBILITY_SERVICE_COMPONENT
    val target = canonicalComponent(component)
    // Abort on a failed READ — never write a guessed/garbled list back, that would clobber others.
    val current = readSecure("enabled_accessibility_services") ?: return false
    // Strip EVERY spelling of our component. The short ("pkg/.Cls") and full ("pkg/pkg.Cls") forms
    // both canonicalise to the same ComponentName, so a literal-string filter would leave the other
    // form behind. The framework would then see no change to the enabled SET and never re-bind a
    // crashed service — the exact failure that kept star control dead after a reboot.
    val others = current.split(':').filter { it.isNotEmpty() && canonicalComponent(it) != target }
    // $1 = the list, passed as argv (not interpolated) — safe even if an existing entry is odd.
    if (shExec("settings put secure enabled_accessibility_services \"\$1\"", others.joinToString(":")).code != 0) return false
    Thread.sleep(200L)  // let the framework observe the removal before we re-add (OpenBYD uses 0.2s)
    if (shExec("settings put secure enabled_accessibility_services \"\$1\"", (others + component).joinToString(":")).code != 0) return false
    if (shExec("settings put secure accessibility_enabled 1").code != 0) return false
    // Verify read-back: our component is now listed AND accessibility is enabled.
    val after = (readSecure("enabled_accessibility_services") ?: return false).split(':').filter { it.isNotEmpty() }
    return after.any { canonicalComponent(it) == target } && readSecure("accessibility_enabled") == "1"
}

/**
 * Recovers the a11y service on Android 10 after the firmware's quickboot force-stop parked our
 * component in AccessibilityManagerService's UserState.mBindingServices (AOSP Q: no
 * ACTION_PACKAGE_RESTARTED broadcast, so AMS re-binds into the dying package and the bind never
 * completes; updateServicesLocked skips such components forever). A settings rewrite cannot leave
 * that state — only PackageMonitor.onHandleForceStop clears it, which
 * IActivityManager.forceStopPackage triggers. So: force-stop ourselves, re-enable, restart.
 *
 * The app is killed by step 1, so it never sees the reply; the daemon survives (shell uid, own
 * process) and finishes the sequence. Kept dumb and logged — the SDK gate lives in the app.
 */
private fun recoverAccessibilityService(): Boolean {
    val tag = "bydmate_helper"
    val pkg = HelperBinderProtocol.APP_PACKAGE
    android.util.Log.w(tag, "a11y recover: force-stopping $pkg (AOSP Q stuck binding)")
    try {
        forceStopPackage(pkg)
    } catch (e: Throwable) {
        android.util.Log.w(tag, "a11y recover: force-stop failed: ${e.javaClass.simpleName}: ${e.message}")
        return false
    }
    Thread.sleep(700L)  // let AccessibilityManagerService process onHandleForceStop
    // The app is dead from here on, so the restart goes out FIRST and does not depend on anything
    // else in this function: a hung `settings` must not leave TrackingService stopped until boot.
    // TrackingService is not exported, so shell uid cannot start it directly; BootReceiver is
    // (BOOT_COMPLETED needs it) and its WorkManager chain starts the service. Explicit component +
    // include-stopped-packages: the force-stop just put the package into the stopped state.
    // --receiver-foreground: a plain shell broadcast goes to the background queue, behind other
    // apps' BOOT_COMPLETED receivers after a quick boot; Atto 3 logs showed 11-44 s from the
    // force-stop to BootReceiver, with the widget and key service gone for that long.
    var am = ""
    var restarted = false
    for (attempt in 1..2) {
        am = runCatching {
            amShell(
                "am broadcast --include-stopped-packages --receiver-foreground -a com.bydmate.app.action.RECOVER_START -n \"\$1\"",
                listOf("$pkg/com.bydmate.app.service.BootReceiver"),
            )
        }.getOrElse { "broadcast failed: ${it.javaClass.simpleName}: ${it.message}" }
        restarted = am.contains("Broadcast completed")
        if (restarted) break
        android.util.Log.w(tag, "a11y recover: restart broadcast attempt $attempt failed: ${am.take(200)}")
        Thread.sleep(1000L)
    }
    // Re-assert the a11y setting: onHandleForceStop removed us from the enabled set as well.
    val ok = try {
        enableAccessibilityService()
    } catch (e: Throwable) {
        android.util.Log.w(tag, "a11y recover: re-assert failed: ${e.javaClass.simpleName}: ${e.message}")
        false
    }
    android.util.Log.i(tag, "a11y recover: reassert=$ok restart=$restarted am=${am.take(200)}")
    logA11yFrameworkStateAsync(ok)  // diagnostics only
    return ok && restarted
}

/**
 * Logs the framework's own accessibility bookkeeping after a re-assert, under the daemon tag the
 * app's log recorder already captures. `dumpsys accessibility` prints the AOSP UserState sets
 * (Bound / Enabled / Binding services) on one line each; `dumpsys activity services` shows whether
 * ActivityManager still holds a ServiceRecord + connection for our key filter. Field evidence for
 * the Android 10 "stuck in mBindingServices" case that a settings rewrite cannot leave.
 * Read-only: two dumpsys calls, output trimmed to a handful of lines.
 */
private fun logA11yFrameworkStateAsync(reassertOk: Boolean) {
    val t = Thread({ runCatching { logA11yFrameworkState(reassertOk) } }, "bydmate-a11ydiag")
    t.isDaemon = true
    t.start()
}

/** Diagnostics only; every dumpsys is time- and size-bounded so it can never wedge the daemon. */
private fun logA11yFrameworkState(reassertOk: Boolean) {
    val tag = "bydmate_helper"
    val a11y = shExecBounded("dumpsys accessibility").lines()
    val keep = a11y.filter { l ->
        l.contains("User state[") || l.contains("services:{") || l.contains("bydmate", ignoreCase = true)
    }.take(12)
    android.util.Log.i(tag, "a11y state after reassert ok=$reassertOk: sdk=${android.os.Build.VERSION.SDK_INT} lines=${a11y.size}")
    keep.forEach { android.util.Log.i(tag, "a11y: " + it.trim().take(300)) }
    val pid = shExecBounded("pidof com.bydmate.app")
    val stopped = shExecBounded("dumpsys package com.bydmate.app").lines()
        .firstOrNull { it.contains("stopped=", ignoreCase = true) }?.trim()?.take(200)
    android.util.Log.i(tag, "pkg: pid=${pid.ifEmpty { "none" }} $stopped")
    val am = shExecBounded("dumpsys activity services com.bydmate.app").lines()
    val start = am.indexOfFirst { it.contains("SteeringWheelKeyService") }
    if (start < 0) {
        android.util.Log.i(tag, "am: no ServiceRecord for SteeringWheelKeyService (lines=${am.size})")
        return
    }
    am.drop(start).take(30).forEach { android.util.Log.i(tag, "am: " + it.trim().take(300)) }
}

/**
 * Self-grants notification-listener access for our MediaSessionListenerService stub.
 *
 * Primary: `cmd notification allow_listener <component>` — the official NMS API that writes to
 * the canonical approved list inside NotificationManagerService. On some firmwares (Sea Lion 06/07)
 * NMS reconciles enabled_notification_listeners from that internal list on every Settings change,
 * so a raw settings-put entry is stripped immediately; cmd allow_listener is the only path that
 * sticks (donor-verified). Verified by reading back the secure setting.
 *
 * Fallback: remove-wait-readd settings-put cycle — for firmwares that predate `cmd notification`.
 * NMS's Settings observer only re-binds listeners when the string actually changes value, so
 * re-adding a component already present would silently no-op after a crash.
 * Read-modify-write preserves other apps' listeners (our component is the only one we ever touch).
 *
 * App-scoped, reversible Secure setting only; touches nothing on the vehicle (no autoservice/CAN).
 */
private fun enableNotificationListener(): Boolean {
    val component = HelperBinderProtocol.NOTIFICATION_LISTENER_COMPONENT
    val target = canonicalComponent(component)

    // Primary path: cmd notification allow_listener writes to NMS's canonical approved list.
    // On firmwares where NMS reconciles the raw secure setting from that list, raw settings-put
    // entries are stripped on the next reconciliation cycle; this path is immune to that.
    // Readback is gated on exit code == 0: a stale raw entry in the setting could otherwise
    // false-positive the check when the canonical NMS grant is absent (the exact reconciliation
    // case), causing the function to return true before the fallback ever runs.
    val cmdResult = shExec("cmd notification allow_listener \"\$1\"", component)
    if (cmdResult.code == 0) {
        val afterCmd = (readSecure("enabled_notification_listeners") ?: "").split(':').filter { it.isNotEmpty() }
        if (afterCmd.any { canonicalComponent(it) == target }) return true
    }

    // Fallback: remove-wait-readd settings-put for firmwares without `cmd notification`.
    val current = readSecure("enabled_notification_listeners") ?: return false
    val others = current.split(':').filter { it.isNotEmpty() && canonicalComponent(it) != target }
    if (shExec("settings put secure enabled_notification_listeners \"\$1\"", others.joinToString(":")).code != 0) return false
    Thread.sleep(200L)
    if (shExec("settings put secure enabled_notification_listeners \"\$1\"", (others + component).joinToString(":")).code != 0) return false
    val after = (readSecure("enabled_notification_listeners") ?: return false).split(':').filter { it.isNotEmpty() }
    return after.any { canonicalComponent(it) == target }
}

/**
 * Enables or disables the Wi-Fi hotspot (TETHERING_WIFI = 0) via reflection.
 *
 * Ported from verified autovoice InterconnectionApiImpl (methods f()/l()). Requires
 * TETHER_PRIVILEGED which the shell uid holds. Needs on-car validation.
 *
 * Primary: android.net.BydTetheringInterface (hidden framework class on DiLink head units).
 * Legacy fallback: ConnectivityManager.startTethering/stopTethering via reflection
 * (deprecated API present on older builds).
 */
private fun setHotspot(ctx: Context, enable: Boolean): Boolean {
    return try {
        // Primary path: BydTetheringInterface (hidden framework class, not in SDK).
        val bydCls = Class.forName("android.net.BydTetheringInterface")
        val bydInstance = bydCls.getMethod("getInstance", Context::class.java).invoke(null, ctx)
        if (enable) {
            // TetheringManager.TetheringRequest.Builder(0 = TETHERING_WIFI).build()
            val builderCls = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
            val builder = builderCls.getConstructor(Int::class.javaPrimitiveType).newInstance(0)
            val request = builderCls.getMethod("build").invoke(builder)
            val requestCls = Class.forName("android.net.TetheringManager\$TetheringRequest")
            val callbackCls = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
            // Build a no-op Proxy matching the verified autovoice pattern (inner class a in
            // InterconnectionApiImpl.java): logging-only onTetheringStarted/onTetheringFailed.
            // Passing null risks an NPE inside the BYD framework wrapper on the callback dispatch.
            val noOpCallback = java.lang.reflect.Proxy.newProxyInstance(
                callbackCls.classLoader,
                arrayOf(callbackCls)
            ) { _, _, _ -> null }
            // startTethering(TetheringRequest, Executor, StartTetheringCallback).
            // Autovoice passes (Executor) null here, but strict AOSP TetheringManager rejects a
            // null executor; a direct executor satisfies both a strict forwarder and a tolerant
            // BYD wrapper, and only ever runs the no-op callback dispatch.
            val directExecutor = java.util.concurrent.Executor { it.run() }
            bydCls.getMethod("startTethering", requestCls,
                java.util.concurrent.Executor::class.java, callbackCls)
                .invoke(bydInstance, request, directExecutor, noOpCallback)
        } else {
            // stopTethering(int type) — 0 = TETHERING_WIFI
            bydCls.getMethod("stopTethering", Int::class.javaPrimitiveType)
                .invoke(bydInstance, 0)
        }
        true
    } catch (primary: Throwable) {
        System.err.println("WARN: BydTetheringInterface failed, trying CM legacy: ${primary.message}")
        // Legacy fallback: ConnectivityManager.startTethering/stopTethering via reflection.
        try {
            val cm = ctx.getSystemService("connectivity") ?: return false
            if (enable) {
                val callbackCls = Class.forName("android.net.ConnectivityManager\$OnStartTetheringCallback")
                // OnStartTetheringCallback is an abstract CLASS (not an interface), so Proxy
                // cannot help. Autovoice used dexmaker to subclass it, which is unavailable here.
                // Stock AOSP ConnectivityManager.startTethering begins with
                // Objects.requireNonNull(callback, "OnStartTetheringCallback cannot be null."),
                // so this call will always throw on stock builds. Kept only as a last-resort
                // attempt for non-AOSP ROM variants that tolerate a null callback; accepted risk.
                cm.javaClass.getDeclaredMethod("startTethering",
                    Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, callbackCls)
                    .invoke(cm, 0, false, null)
            } else {
                val m = cm.javaClass.getDeclaredMethod("stopTethering", Int::class.javaPrimitiveType)
                m.isAccessible = true
                m.invoke(cm, 0)
            }
            true
        } catch (legacy: Throwable) {
            System.err.println("WARN: CM hotspot legacy fallback failed: ${legacy.message}")
            false
        }
    }
}

private val GLOBAL_SETTING_KEYS = setOf("sentrymode_enabled_switch", "enable_freeform_support", "auto_time_zone")

/**
 * Settings.Global whitelist for TX_PUT_GLOBAL_SETTING: the sentry-mode master switch, the
 * freeform windowing flag (direct cluster projection; the framework reads it once at boot) and
 * the automatic time zone switch (the user's time zone lock, #379: 0 = network zone ignored).
 * Values are bounded to 0/1. Anything else is rejected before any shell command runs.
 */
internal fun globalSettingAllowed(key: String, value: Int): Boolean =
    key in GLOBAL_SETTING_KEYS && value in 0..1

/** Keys [HelperBinderProtocol.TX_GET_GLOBAL_SETTING] may read back: the write whitelist's keys. */
internal fun globalSettingReadable(key: String): Boolean =
    key in GLOBAL_SETTING_KEYS

/** How long the read below waits for `settings get global` before it gives up on it. */
internal const val GLOBAL_SETTING_READ_TIMEOUT_MS = 2000L

/** A settings value is a handful of bytes; anything longer is not one and is not worth buffering. */
private const val GLOBAL_SETTING_READ_MAX_BYTES = 256

/**
 * Read side of [globalSettingAllowed]: the same narrow key set, so this stays a sentry/freeform
 * channel and not a generic settings passthrough. The shell call is bounded — this runs on the
 * binder thread the app blocks on, and a `settings` that never returns would pin it (and the
 * helper mutex behind it) forever. Not whitelisted, not a number, or the bound expired -> null,
 * and the caller refuses the toggle instead of guessing a state.
 */
internal fun readGlobalSetting(key: String): Int? {
    if (!globalSettingReadable(key)) return null
    val out = runCatching {
        shExecBounded("settings get global \"\$1\"", GLOBAL_SETTING_READ_TIMEOUT_MS, GLOBAL_SETTING_READ_MAX_BYTES, key)
    }.getOrElse { return null }
    return parseGlobalSettingValue(out)
}

/** First line of `settings get global` as an Int; "null", noise or a `[timeout …]` marker -> null. */
internal fun parseGlobalSettingValue(output: String): Int? =
    output.lineSequence().firstOrNull()?.trim()?.toIntOrNull()

/**
 * Expands a flattened component string to a canonical `pkg/fully.qualified.Class`, mirroring
 * ComponentName.unflattenFromString's leading-dot rule (`pkg/.A.B` -> `pkg/pkg.A.B`). Lets the short
 * and full spellings of one service compare equal. Returns the input unchanged when it has no '/'.
 */
internal fun canonicalComponent(flattened: String): String {
    val sep = flattened.indexOf('/')
    if (sep < 0) return flattened
    val pkg = flattened.substring(0, sep)
    val cls = flattened.substring(sep + 1)
    val fqcn = if (cls.startsWith(".")) pkg + cls else cls
    return "$pkg/$fqcn"
}

/**
 * Reads a secure setting via the `settings` binary: "" when unset (`settings get` prints "null"),
 * or null on a non-zero exit so callers can abort instead of acting on a bad read.
 */
private fun readSecure(key: String): String? {
    val r = shExec("settings get secure \"\$1\"", key)
    if (r.code != 0) return null
    return if (r.stdout == "null") "" else r.stdout
}

/**
 * Reflects all static int/long constants out of [android.hardware.bydauto.BYDAutoFeatureIds]
 * and [android.hardware.bydauto.BYDAutoConstants] (and all their `declaredClasses`) and
 * returns them as sorted "ClassName.FIELD_NAME=value" lines joined with `\n`.
 *
 * A class absent from the firmware is silently skipped — empty section, no error. The
 * [classResolver] is wrapped in [runCatching] so it may either return null or throw
 * (e.g. [ClassNotFoundException] from the production `Class.forName` path) — both are treated
 * as "class not present, skip". This makes the function safe on non-BYD ROMs and on firmwares
 * where only one of the two SDK classes exists.
 *
 * Each class in [toScan] is guarded by its own [runCatching] with a local line buffer: a class
 * whose `<clinit>` triggers [ExceptionInInitializerError] or whose field type is unresolvable
 * ([NoClassDefFoundError] from [Class.declaredFields]) only loses its own section; the rest of
 * the dump is unaffected. `getDeclaredClasses()` itself is also guarded for the same reason.
 *
 * The field filter excludes `$`-prefixed names (Kotlin compiler synthetics such as `$stable`).
 * Real SDK constants never start with `$`; the filter is a no-op for Java-compiled BYD SDK classes
 * but prevents noise when the resolver is fed Kotlin test fixtures.
 *
 * Plain reflection only — no HiddenApiBypass, in line with the file-level comment. Under
 * app_process, hidden-API enforcement is inactive, so Class.forName works for BYD SDK classes.
 *
 * [classResolver] is injectable for tests: feed synthetic classes without the real BYD SDK.
 */
internal fun dumpFidsCore(classResolver: (String) -> Class<*>?): String {
    val targets = listOf(
        "android.hardware.bydauto.BYDAutoFeatureIds",
        "android.hardware.bydauto.BYDAutoConstants",
    )
    val lines = mutableListOf<String>()
    for (className in targets) {
        val rootClass = runCatching { classResolver(className) }.getOrNull() ?: continue
        val toScan = mutableListOf(rootClass)
        toScan.addAll(
            runCatching { rootClass.declaredClasses }.getOrElse {
                android.util.Log.w("bydmate_helper", "dumpFids: declaredClasses failed for ${rootClass.name}", it)
                emptyArray()
            }
        )
        for (cls in toScan) {
            val classLines = runCatching {
                val prefix = cls.simpleName.ifEmpty { cls.name }
                cls.declaredFields
                    .filter {
                        java.lang.reflect.Modifier.isStatic(it.modifiers) &&
                            !it.name.startsWith("$") &&
                            (it.type == Int::class.javaPrimitiveType ||
                                it.type == Long::class.javaPrimitiveType)
                    }
                    .map { field ->
                        field.isAccessible = true
                        "$prefix.${field.name}=${field.get(null)}"
                    }
            }.getOrElse {
                android.util.Log.w("bydmate_helper", "dumpFids: skipping ${cls.name}", it)
                emptyList()
            }
            lines += classLines
        }
    }
    return lines.sorted().joinToString("\n")
}

/**
 * Cache for the last TX_DUMP_FIDS result. Built on offset==0 and reused for subsequent
 * offsets within the same chunked sequence. Volatile: the binder thread pool may dispatch
 * concurrent calls (rare in practice, safe because the dump is deterministic reflection).
 */
@Volatile private var dumpFidsCache: String? = null

/**
 * Extracts a single chunk of [chunkMax] bytes from [dumpUtf8Bytes] starting at [offset].
 * Returns an empty ByteArray when [offset] >= [dumpUtf8Bytes].size (past the end).
 * Exposed as `internal` so the test suite can exercise chunking independently of reflection.
 */
internal fun dumpFidsChunkBytes(
    dumpUtf8Bytes: ByteArray,
    offset: Int,
    chunkMax: Int = HelperBinderProtocol.DUMP_CHUNK_MAX,
): ByteArray {
    if (offset >= dumpUtf8Bytes.size) return ByteArray(0)
    val end = minOf(offset + chunkMax, dumpUtf8Bytes.size)
    return dumpUtf8Bytes.copyOfRange(offset, end)
}

/**
 * The three diagnostic-recorder verbs, out of line so onTransact keeps its own branch count:
 * start over the requested devices (empty = all), stop, and read the live counters back.
 * Every reply starts with a status int, so a failure is never read as an empty recording.
 */
private fun handleRecorderTransact(code: Int, data: Parcel, reply: Parcel?): Boolean {
    when (code) {
        HelperBinderProtocol.TX_REC_START -> runCatching {
            val devices = readRecStartRequest(data)
            val result = FidRecorder.start(devices)
            reply?.writeInt(0)
            reply?.writeInt(result.devices)
            reply?.writeInt(result.registered)
            reply?.writeString(result.error)
        }.onFailure { e ->
            android.util.Log.w("FidRec", "TX_REC_START failed", e)
            reply?.writeInt(-1); reply?.writeInt(0); reply?.writeInt(0); reply?.writeString("")
        }

        HelperBinderProtocol.TX_REC_STOP -> runCatching {
            val stopped = FidRecorder.stop()
            reply?.writeInt(0)
            reply?.writeInt(stopped)
        }.onFailure { e ->
            android.util.Log.w("FidRec", "TX_REC_STOP failed", e)
            reply?.writeInt(-1); reply?.writeInt(0)
        }

        else -> runCatching {
            val status = FidRecorder.status()
            reply?.writeInt(0)
            reply?.let { writeRecStatus(it, status) }
        }.onFailure { e ->
            android.util.Log.w("FidRec", "TX_REC_STATUS failed", e)
            reply?.writeInt(-1)
        }
    }
    return true
}

/**
 * The three power-off report verbs, out of line like the recorder's. Every reply starts with a
 * status int; a failure here costs this call only, never the daemon.
 */
private fun handleOffReportTransact(code: Int, data: Parcel, reply: Parcel?): Boolean {
    runCatching {
        when (code) {
            HelperBinderProtocol.TX_OFFREPORT_ARM -> {
                val request = readOffReportArm(data)
                if (request != null) OffReport.arm(request)
                reply?.writeInt(if (request != null) 0 else -1)
            }
            HelperBinderProtocol.TX_OFFREPORT_DISARM -> {
                // Non-zero while a pending file survived the delete: the app keeps asking.
                reply?.writeInt(if (OffReport.disarm()) 0 else 1)
            }
            else -> {
                val status = OffReport.status(data.readString().orEmpty())
                reply?.writeInt(0)
                reply?.let { writeOffReportStatus(it, status) }
            }
        }
    }.onFailure { e ->
        // The class name only: a message could carry what the request held.
        android.util.Log.w("OffReport", "offreport: tx=$code failed: ${e.javaClass.simpleName}")
        reply?.setDataSize(0)
        reply?.writeInt(-1)
    }
    return true
}

/**
 * The manual fault-code scan and erase verbs. START only hands the job to its own thread and
 * answers; the gear (getInt, tx 5) and, for the erase, the speed (getFloat, tx 7) are read live at
 * the addresses the app resolved, before every request. A failure here costs this call only.
 */
private fun handleDtcScanTransact(code: Int, data: Parcel, reply: Parcel?, svc: IBinder, autoIface: String): Boolean {
    runCatching {
        when (code) {
            HelperBinderProtocol.TX_DTC_SCAN_START -> {
                val gearDev = data.readInt()
                val gearFid = data.readInt()
                val status = DtcDaemon.scanner.start { readDtcGear(svc, autoIface, gearDev, gearFid) }
                android.util.Log.i("DtcScan", "start request gear=$gearDev/$gearFid reply=$status")
                reply?.writeInt(status)
            }
            HelperBinderProtocol.TX_DTC_ERASE_START -> {
                val gearDev = data.readInt()
                val gearFid = data.readInt()
                val speedDev = data.readInt()
                val speedFid = data.readInt()
                val addrs = data.createIntArray() ?: IntArray(0)
                DtcDaemon.speed = { readDtcSpeed(svc, autoIface, speedDev, speedFid) }
                val status = DtcDaemon.scanner.startErase(addrs) { readDtcGear(svc, autoIface, gearDev, gearFid) }
                android.util.Log.i(
                    "DtcScan",
                    "erase request gear=$gearDev/$gearFid speed=$speedDev/$speedFid " +
                        "addrs=${addrs.joinToString(",") { "0x%X".format(it) }} reply=$status",
                )
                reply?.writeInt(status)
            }
            else -> reply?.let { writeDtcScanStatus(it, DtcDaemon.scanner.status()) }
        }
    }.onFailure { e ->
        android.util.Log.w("DtcScan", "tx=$code failed: ${e.javaClass.simpleName}")
        reply?.setDataSize(0)
        reply?.writeInt(-1)
    }
    return true
}

private fun readDtcGear(svc: IBinder, autoIface: String, dev: Int, fid: Int): Int? =
    autoserviceTransact(svc, autoIface, 5, dev, fid, 0, writeValue = false)
        .takeIf { (status, _) -> status == 0 }?.second

/** Speed in whole km/h rounded away from 0 (any movement reads >= 1); null when unreadable or a sentinel. */
private fun readDtcSpeed(svc: IBinder, autoIface: String, dev: Int, fid: Int): Int? =
    autoserviceTransact(svc, autoIface, 7, dev, fid, 0, writeValue = false)
        .takeIf { (status, _) -> status == 0 }
        ?.let { (_, bits) -> SentinelDecoder.parseFloatFromShellInt(bits) }
        ?.let { kotlin.math.ceil(kotlin.math.abs(it)).toInt() }
