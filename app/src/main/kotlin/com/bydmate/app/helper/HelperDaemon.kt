@file:JvmName("HelperDaemon")
package com.bydmate.app.helper

import com.bydmate.app.BuildConfig
import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Binder
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
 *     --nice-name=bydmate_helper com.bydmate.app.helper.HelperDaemon <appUid>
 *
 * Lifecycle:
 *   1. Parse expectedUid from args[0].
 *   2. Acquire single-owner file lock — exits with ALREADY_RUNNING if held.
 *   3. Resolve autoservice IBinder reflectively.
 *   4. Register a Binder stub under SERVICE_NAME via ServiceManager.addService.
 *   5. Print READY and keepalive with Looper.loop().
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
    println(
        "BOOT uid=${android.os.Process.myUid()} pid=${android.os.Process.myPid()} " +
            "selinux=${readSelinuxContext()}"
    )
    System.out.flush()

    // Prepare the main looper BEFORE ActivityThread.systemMain (OpenBYD EntryPoint order),
    // then acquire a system Context for the projection DisplayManager calls.
    @Suppress("DEPRECATION")
    Looper.prepareMainLooper()
    val systemContext: Context? = acquireSystemContext()

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

    // Keeps created VirtualDisplays alive (their backing Surface comes from the app overlay).
    // Keyed by displayId so TX_RELEASE_VIRTUAL_DISPLAY can release the right one.
    val virtualDisplays = ConcurrentHashMap<Int, VirtualDisplay>()

    // Step 3: build our stub Binder.
    val helperBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            // Uid gate first — only our app may call.
            if (Binder.getCallingUid() != expectedUid) return false

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

                HelperBinderProtocol.TX_MOVE_TASK_TO_DISPLAY -> runCatching {
                    val taskId = data.readInt(); val displayId = data.readInt()
                    moveTaskToDisplayReflect(taskId, displayId)
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_TASK_BOUNDS -> runCatching {
                    val taskId = data.readInt()
                    val l = data.readInt(); val t = data.readInt(); val r = data.readInt(); val b = data.readInt()
                    setTaskBoundsReflect(taskId, l, t, r, b)
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_FOCUSED_TASK -> runCatching {
                    setFocusedTaskReflect(data.readInt())
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_TASK_WINDOWING_MODE -> runCatching {
                    val taskId = data.readInt(); val mode = data.readInt()
                    handleSetWindowingModeTx(
                        taskId, mode, readTrailingPaneType(data),
                        reflectSet = ::setTaskWindowingModeReflect,
                        resolveComponent = { packageForTask(taskId)?.let { resolveLaunchComponent(it) } },
                        shell = amShell,
                        getActivityType = { ti -> taskActivityType(ti) },
                        sleep = { Thread.sleep(it) },
                    )
                    reply?.writeInt(0); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_CREATE_VIRTUAL_DISPLAY -> runCatching {
                    val name = data.readString() ?: "BYDMate_VD"
                    val width = data.readInt(); val height = data.readInt()
                    val density = data.readInt(); val flags = data.readInt()
                    val surface = Surface.CREATOR.createFromParcel(data)
                    val ctx = systemContext
                    val id = if (ctx == null || !surface.isValid) -1
                            else createVirtualDisplay(ctx, virtualDisplays, name, width, height, density, surface, flags)
                    if (id > 0) { reply?.writeInt(0); reply?.writeInt(id) }
                    else { surface.release(); reply?.writeInt(-1); reply?.writeInt(0) }
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

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

                HelperBinderProtocol.TX_LAUNCH_AND_FORCE -> runCatching {
                    val pkg = data.readString() ?: ""
                    val displayId = data.readInt(); val width = data.readInt(); val height = data.readInt()
                    val ok = launchAndForce(pkg, displayId, width, height)
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_ENABLE_ACCESSIBILITY -> runCatching {
                    val ok = enableAccessibilityService()
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

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

                HelperBinderProtocol.TX_ENABLE_NOTIFICATION_LISTENER -> runCatching {
                    val ok = enableNotificationListener()
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

                HelperBinderProtocol.TX_SET_APP_HIDDEN -> runCatching {
                    val pkg = data.readString() ?: ""
                    val hidden = data.readInt()    // 1 = disable, 0 = enable
                    // Hardcoded — ONLY the native BYD voice assistant family. Fully reversible
                    // (pm enable) and touches no firmware. NOT a generic package-disable passthrough;
                    // the caller may only name the launcher package.
                    // Validate the flag daemon-side too — a privileged shell-uid op must not
                    // trust the caller. Only 0/1 are a defined state; reject anything else.
                    val ok = if (pkg == "com.byd.autovoice" && hidden in 0..1) {
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
                        // Siblings are hardcoded literals, never caller input. Success is gated on
                        // BOTH the launcher and the wake engine; TTS is output-only and best-effort.
                        val primaryOk = shExec("$cmd \"\$1\"", pkg).code == 0
                        val engineOk = shExec("$cmd \"\$1\"", "com.byd.autovoice.engine").code == 0
                        shExec("$cmd \"\$1\"", "com.byd.autovoice.tts")
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
                    val ok = setDisplayDensity(displayId, density)
                    reply?.writeInt(if (ok) 0 else -1); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

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

                HelperBinderProtocol.TX_LAUNCH_FREEFORM -> runCatching {
                    val pkg = data.readString() ?: ""
                    val displayId = data.readInt()
                    val l = data.readInt(); val t = data.readInt()
                    val r = data.readInt(); val b = data.readInt()
                    val status = launchFreeform(pkg, displayId, l, t, r, b, readTrailingPaneType(data))
                    reply?.writeInt(status); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

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
                    }
                    true
                }.getOrElse { reply?.writeInt(-1); true }

                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
    helperBinder.attachInterface(null, HelperBinderProtocol.DESCRIPTOR)

    // Step 4: register the stub with ServiceManager.
    try {
        smCls.getMethod("addService", String::class.java, IBinder::class.java)
            .invoke(null, HelperBinderProtocol.SERVICE_NAME, helperBinder)
    } catch (e: Exception) {
        System.err.println("ERR: addService ${describeBootstrapFailure(e)}")
        // exitProcess so the OS releases the file lock we hold; a bare return would
        // leave a hung lock-holding daemon and block every future spawn.
        exitProcess(4)
    }

    System.out.println("READY pid=${android.os.Process.myPid()}")
    System.out.flush()

    // Keepalive: Looper.loop() blocks this thread indefinitely so main() never returns
    // and the process stays alive. Incoming binder transactions are dispatched by the
    // binder threadpool that app_process starts via ProcessState::startThreadPool() in
    // AppRuntime::onStarted — Looper.loop() plays NO role in transaction dispatch here;
    // it is purely a blocking keepalive for the main thread.
    Looper.loop()
}

// Trailing activityType int: absent when the caller predates the split touch fix →
// legacy RECENTS. Values other than STANDARD are coerced to RECENTS (conservative).
internal fun readTrailingPaneType(data: Parcel): Int {
    val raw = if (data.dataAvail() > 0) data.readInt() else ACTIVITY_TYPE_RECENTS
    return if (raw == ACTIVITY_TYPE_STANDARD) ACTIVITY_TYPE_STANDARD else ACTIVITY_TYPE_RECENTS
}

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

/** Resolves IActivityTaskManager via ActivityTaskManager.getService() (hidden API, ok under app_process). */
private fun activityTaskManager(): Any =
    Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        ?: throw IllegalStateException("ActivityTaskManager.getService() returned null")

/** Walks the superclass chain for a declared field [name] (mirrors CarControlImpl.findField). */
private fun fieldByName(target: Any, name: String): java.lang.reflect.Field? {
    var cls: Class<*>? = target.javaClass
    while (cls != null) {
        try { return cls.getDeclaredField(name) } catch (e: NoSuchFieldException) { cls = cls.superclass }
    }
    return null
}

/**
 * Finds the running task id of [packageName]. Reconstructed from CarControlImpl.getTopActivityPackage:
 * iAtm.getTasks(maxNum, false, false) -> List<RunningTaskInfo>; match topActivity/baseActivity package;
 * read int field "taskId" (fallback "id"). Returns -1 when not found.
 * NOTE: field names validated on-car in Phase 2.
 */
private fun findTaskId(packageName: String): Int {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 100, false, false) as? List<*> ?: return -1
    for (task in tasks) {
        if (task == null) continue
        val pkg = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { fieldName ->
            fieldByName(task, fieldName)?.let { f ->
                f.isAccessible = true
                (f.get(task) as? android.content.ComponentName)?.packageName
            }
        }
        if (pkg == packageName) {
            val idField = fieldByName(task, "taskId") ?: fieldByName(task, "id") ?: continue
            idField.isAccessible = true
            return idField.getInt(task)
        }
    }
    return -1
}

/** Inverse of [findTaskId]: the package owning [taskId], or null when the task is not listed. */
private fun packageForTask(taskId: Int): String? = runCatching {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 100, false, false) as? List<*> ?: return@runCatching null
    for (task in tasks) {
        if (task == null) continue
        val idField = fieldByName(task, "taskId") ?: fieldByName(task, "id") ?: continue
        idField.isAccessible = true
        if (idField.getInt(task) != taskId) continue
        return@runCatching listOf("topActivity", "baseActivity").firstNotNullOfOrNull { fieldName ->
            fieldByName(task, fieldName)?.let { f ->
                f.isAccessible = true
                (f.get(task) as? android.content.ComponentName)?.packageName
            }
        }
    }
    null
}.getOrNull()

/**
 * Returns the package name of the foreground (first/top-of-stack) task, or null when no task
 * exists or reflection fails. Uses the same getTasks reflection as [findTaskId] / [findTaskState];
 * tasks[0] is the most-recently-used / currently focused task on Android recents ordering.
 * Mirrors TX_GET_VERSION pattern: caller writes "" on null so the client reads a defined value.
 */
private fun topTaskPackage(): String? = runCatching {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 1, false, false) as? List<*> ?: return@runCatching null
    val task = tasks.firstOrNull() ?: return@runCatching null
    listOf("topActivity", "baseActivity").firstNotNullOfOrNull { fieldName ->
        fieldByName(task, fieldName)?.let { f ->
            f.isAccessible = true
            (f.get(task) as? android.content.ComponentName)?.packageName
        }
    }
}.getOrNull()

/**
 * Snapshot of the top root task returned by TX_GET_TOP_TASK (Q3 / F-3 COVERED detection).
 * [displayId] mirrors the TaskInfo.displayId convention: 0 = main display, 2 = cluster fission.
 */
private data class TopTaskInfo(
    val pkg: String,
    val taskId: Int,
    val windowingMode: Int,
    val activityType: Int,
    val displayId: Int,
)

/**
 * Returns the windowing state of the first root task on the main display (displayId==0) among
 * the top-10 MRU tasks, or null when no matching task is found or any reflection step fails.
 * Used by TX_GET_TOP_TASK.
 *
 * Uses getTasks(10) rather than getTasks(1): getTasks(1) returns only the single MRU task across
 * ALL displays, so when the cluster projection app is at the foreground on display 2, a foreign
 * fullscreen on display 0 would be invisible to COVERED detection.
 *
 * Returns null when windowConfiguration is unavailable. A partial reflection failure must NOT be
 * converted to WINDOWING_FULLSCREEN+displayId=0, which would falsely satisfy foreignFullscreen
 * and tear down the split session.
 */
private fun topTaskInfo(): TopTaskInfo? = runCatching {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 10, false, false) as? List<*> ?: return@runCatching null
    // Pick the first task on the main display (displayId == 0).
    // C-4: if the displayId field is absent via reflection, skip the task rather than defaulting
    // to 0 (which would synthesise main-display presence and falsely trigger COVERED teardown).
    val task = tasks.firstOrNull { rawTask ->
        rawTask != null &&
        runCatching {
            fieldByName(rawTask, "displayId")?.let { f -> f.isAccessible = true; f.getInt(rawTask) == 0 } ?: false
        }.getOrDefault(false)
    } ?: return@runCatching null
    val pkg = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { name ->
        fieldByName(task, name)?.let { f -> f.isAccessible = true; (f.get(task) as? android.content.ComponentName)?.packageName }
    } ?: return@runCatching null
    val taskId = (fieldByName(task, "taskId") ?: fieldByName(task, "id"))
        ?.let { f -> f.isAccessible = true; f.getInt(task) } ?: -1
    // Require windowConfiguration: returning null on failure prevents synthesising FULLSCREEN@display0,
    // which would look like a foreign fullscreen app and falsely trigger COVERED teardown (Q3-1).
    val configField = fieldByName(task, "configuration") ?: return@runCatching null
    configField.isAccessible = true
    val cfg = configField.get(task) ?: return@runCatching null
    val winConfigField = fieldByName(cfg, "windowConfiguration") ?: return@runCatching null
    winConfigField.isAccessible = true
    val winConfig = winConfigField.get(cfg) ?: return@runCatching null
    // getWindowingMode throws → outer runCatching catches → getOrNull() returns null (safe).
    val windowingMode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as Int
    val activityType = runCatching {
        winConfig.javaClass.getMethod("getActivityType").invoke(winConfig) as Int
    }.getOrDefault(-1)
    val displayId = runCatching {
        fieldByName(task, "displayId")?.let { f -> f.isAccessible = true; f.getInt(task) } ?: 0
    }.getOrDefault(0)
    TopTaskInfo(pkg, taskId, windowingMode, activityType, displayId)
}.getOrNull()

/**
 * Windowing state (taskId, mode, bounds, displayId) for a running task; null when not found
 * or reflection fails. [displayId] is the Android display the task lives on: 0 = main screen,
 * 2 = cluster fission display on Leopard 3. Falls back to 0 when the field is absent on the ROM,
 * so an old app reading only the first 6 ints sees the same behavior as before this field was
 * added (feature auto-disarms because displayId == 0 == main display).
 */
private data class TaskWindowState(
    val taskId: Int, val windowingMode: Int,
    val left: Int, val top: Int, val right: Int, val bottom: Int,
    val displayId: Int,
)

/**
 * Finds [packageName]'s running task and reads its windowing mode + bounds from
 * task.configuration.windowConfiguration (hidden API, same access pattern as taskModeState).
 * Returns null when no matching task is running or reflection fails.
 * The caller interprets null as "no running task" (status 0, taskId -1).
 */
private fun findTaskState(packageName: String): TaskWindowState? = runCatching {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 100, false, false) as? List<*> ?: return@runCatching null
    for (task in tasks) {
        if (task == null) continue
        val pkg = listOf("topActivity", "baseActivity").firstNotNullOfOrNull { fieldName ->
            fieldByName(task, fieldName)?.let { f ->
                f.isAccessible = true
                (f.get(task) as? android.content.ComponentName)?.packageName
            }
        }
        if (pkg != packageName) continue
        val idField = fieldByName(task, "taskId") ?: fieldByName(task, "id") ?: continue
        idField.isAccessible = true
        val taskId = idField.getInt(task)
        val configField = fieldByName(task, "configuration") ?: return@runCatching null
        configField.isAccessible = true
        val config = configField.get(task) ?: return@runCatching null
        val winConfigField = fieldByName(config, "windowConfiguration") ?: return@runCatching null
        winConfigField.isAccessible = true
        val winConfig = winConfigField.get(config) ?: return@runCatching null
        val mode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as Int
        // getBounds() returns a live Rect; fall back to the backing field on ROMs that hide the getter.
        val bounds: android.graphics.Rect = runCatching {
            winConfig.javaClass.getMethod("getBounds").invoke(winConfig) as android.graphics.Rect
        }.getOrElse {
            fieldByName(winConfig, "mBounds")?.let { f -> f.isAccessible = true; f.get(winConfig) as? android.graphics.Rect }
                ?: android.graphics.Rect()
        }
        // displayId: TaskInfo.displayId (present since API 29); fall back to 0 (main display)
        // when absent or inaccessible so existing consumers behave identically on ROMs without it.
        // Wrapped in its own runCatching (like the bounds read above): an AccessibleObject or
        // IllegalAccessException from isAccessible/getInt must not propagate to the outer
        // runCatching, which would make findTaskState return null and trigger a spurious
        // PaneClosed storm on every watchdog tick.
        val displayId = runCatching {
            fieldByName(task, "displayId")?.let { f -> f.isAccessible = true; f.getInt(task) } ?: 0
        }.getOrDefault(0)
        return@runCatching TaskWindowState(taskId, mode, bounds.left, bounds.top, bounds.right, bounds.bottom, displayId)
    }
    null
}.getOrNull()

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

/**
 * Reads [taskId]'s live windowing mode + display id via the same getTasks reflection as
 * [findTaskId] (TaskInfo.configuration.windowConfiguration.getWindowingMode() + TaskInfo.displayId).
 * Returns null when the task is not in the list (detached/dead) or reflection fails — callers
 * treat null as "state unknown" and fall back to call-outcome semantics.
 */
private fun taskModeState(taskId: Int): TaskModeState? = runCatching {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 100, false, false) as? List<*> ?: return@runCatching null
    for (task in tasks) {
        if (task == null) continue
        val idField = fieldByName(task, "taskId") ?: fieldByName(task, "id") ?: continue
        idField.isAccessible = true
        if (idField.getInt(task) != taskId) continue
        val configField = fieldByName(task, "configuration") ?: return@runCatching null
        configField.isAccessible = true
        val config = configField.get(task) ?: return@runCatching null
        val winConfigField = fieldByName(config, "windowConfiguration") ?: return@runCatching null
        winConfigField.isAccessible = true
        val winConfig = winConfigField.get(config) ?: return@runCatching null
        val mode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as Int
        val display = fieldByName(task, "displayId")?.let { f -> f.isAccessible = true; f.getInt(task) } ?: -1
        return@runCatching TaskModeState(mode, display)
    }
    null
}.getOrNull()

/**
 * Returns the ATMS activityType of [taskId] (e.g. ACTIVITY_TYPE_STANDARD=1, ACTIVITY_TYPE_RECENTS=3),
 * or -1 when the task is not found or the reflection call fails. Uses the same RootTaskInfo
 * surface as [taskModeState] — WindowConfiguration.getActivityType() (AOSP 12 / API 31+).
 * Callers that need a "safe" default should treat -1 as "type unknown, take the conservative path".
 */
internal fun taskActivityType(taskId: Int): Int = runCatching {
    val iAtm = activityTaskManager()
    val getTasks = iAtm.javaClass.getMethod(
        "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
    )
    val tasks = getTasks.invoke(iAtm, 100, false, false) as? List<*> ?: return@runCatching -1
    for (task in tasks) {
        if (task == null) continue
        val idField = fieldByName(task, "taskId") ?: fieldByName(task, "id") ?: continue
        idField.isAccessible = true
        if (idField.getInt(task) != taskId) continue
        val configField = fieldByName(task, "configuration") ?: return@runCatching -1
        configField.isAccessible = true
        val config = configField.get(task) ?: return@runCatching -1
        val winConfigField = fieldByName(config, "windowConfiguration") ?: return@runCatching -1
        winConfigField.isAccessible = true
        val winConfig = winConfigField.get(config) ?: return@runCatching -1
        return@runCatching winConfig.javaClass.getMethod("getActivityType").invoke(winConfig) as Int
    }
    -1
}.getOrElse { e ->
    android.util.Log.w("bydmate_helper", "taskActivityType(task=$taskId) failed: ${e.message}")
    -1
}

/** moveRootTaskToDisplay(int,int) preferred, fallback moveTaskToDisplay(int,int). */
private fun moveTaskToDisplayReflect(taskId: Int, displayId: Int) {
    val iAtm = activityTaskManager()
    val m = iAtm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" && it.parameterTypes.size == 2 }
        ?: iAtm.javaClass.methods.firstOrNull { it.name == "moveTaskToDisplay" && it.parameterTypes.size == 2 }
        ?: throw NoSuchMethodException("moveTaskToDisplay")
    m.invoke(iAtm, taskId, displayId)
}

private fun setTaskWindowingModeReflect(taskId: Int, windowingMode: Int) {
    val iAtm = activityTaskManager()
    // toTop=false: focus is set separately (setFocusedRootTask), and toTop=true walks the
    // vendor moveToFront path on the ORIGINAL display before the reparent — the prime suspect
    // for the on-car throw during re-projection (2026-07-15).
    val result = iAtm.javaClass.getMethod("setTaskWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        .invoke(iAtm, taskId, windowingMode, false)
    // The legacy API returns boolean; false = rejected without an exception (e.g. lock-task).
    if (result == false) throw IllegalStateException("setTaskWindowingMode(task=$taskId, mode=$windowingMode) returned false")
}

private fun setTaskBoundsReflect(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
    val iAtm = activityTaskManager()
    val resizeTask = iAtm.javaClass.getMethod("resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType)
    val rect = if (left == 0 && top == 0 && right == 0 && bottom == 0) null else Rect(left, top, right, bottom)
    resizeTask.invoke(iAtm, taskId, rect, 1)
}

private fun setFocusedTaskReflect(taskId: Int) {
    val iAtm = activityTaskManager()
    iAtm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType).invoke(iAtm, taskId)
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

private class CmdResult(val code: Int, val stdout: String)

/**
 * Runs [script] under sh with [args] bound to positional params ($1, $2, …) so untrusted values
 * (e.g. an existing accessibility list read off the device) are passed as argv and NEVER re-parsed
 * by the shell — no injection, no quote-breakage. Returns ONLY stdout + exit code (stderr is sent
 * to /dev/null, so there is no second pipe to deadlock on and stdout can never be corrupted by an
 * stderr line). Use this when success/output matters and stderr must not reach the parser;
 * use [shExecMerged] when am/monkey error messages on stderr must be detected.
 */
private fun shExec(script: String, vararg args: String): CmdResult {
    val cmd = arrayListOf("sh", "-c", script, "sh")
    cmd.addAll(args)
    val process = ProcessBuilder(cmd)
        .redirectError(ProcessBuilder.Redirect.to(java.io.File("/dev/null")))
        .start()
    val out = process.inputStream.bufferedReader().use { it.readText().trim() }
    return CmdResult(process.waitFor(), out)
}

/**
 * Like [shExec] but merges stderr into stdout via [ProcessBuilder.redirectErrorStream] — use this
 * wherever the result is checked for "Error" or "Exception" text that am and monkey write to stderr.
 * (AOSP ActivityManagerShellCommand uses getErrPrintWriter for "Error:" lines and
 * "Exception occurred while executing '<cmd>'"; the single merged pipe cannot deadlock.)
 * Do NOT use for component resolution ([resolveLaunchComponent]): stderr must not reach the parser.
 */
internal fun shExecMerged(script: String, vararg args: String): String {
    val cmd = arrayListOf("sh", "-c", script, "sh")
    cmd.addAll(args)
    val process = ProcessBuilder(cmd)
        .redirectErrorStream(true)
        .start()
    val out = process.inputStream.bufferedReader().use { it.readText().trim() }
    process.waitFor()
    return out.ifEmpty { "OK" }
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
 * Testable core of the `wm density` op. [displayId] must be a NON-default display — the main
 * screen must never be rescaled by this daemon. [density] 0 = reset, otherwise sane wm bounds.
 * [exec] runs a shell script with positional args and returns its exit code.
 */
internal fun setDisplayDensityCore(displayId: Int, density: Int, exec: (String, List<String>) -> Int): Boolean {
    if (displayId !in 1..63) return false
    if (density != 0 && density !in 80..640) return false
    return if (density == 0) {
        exec("wm density reset -d \"\$1\"", listOf(displayId.toString())) == 0
    } else {
        exec("wm density \"\$1\" -d \"\$2\"", listOf(density.toString(), displayId.toString())) == 0
    }
}

private fun setDisplayDensity(displayId: Int, density: Int): Boolean =
    setDisplayDensityCore(displayId, density) { script, args ->
        shExec(script, *args.toTypedArray()).code
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

/**
 * Settings.Global whitelist for TX_PUT_GLOBAL_SETTING: the sentry-mode master switch and the
 * freeform windowing flag (direct cluster projection; the framework reads it once at boot).
 * Values are bounded to 0/1. Anything else is rejected before any shell command runs.
 */
internal fun globalSettingAllowed(key: String, value: Int): Boolean =
    key in setOf("sentrymode_enabled_switch", "enable_freeform_support") && value in 0..1

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
 * Resolves [packageName]'s launcher component via `cmd package resolve-activity`. Returns null
 * when nothing resolves or the package name is not a valid Android package name.
 * Package validation (strict [A-Za-z0-9_.] regex) runs before any shell command; the package
 * is then passed as a positional arg ($1) so it is never re-parsed by the shell.
 */
private fun resolveLaunchComponent(packageName: String): String? {
    if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return null
    val resolve = shExec("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER \"\$1\"", packageName)
    return resolve.stdout.lineSequence().firstOrNull { it.contains("/") && !it.startsWith("No ") }?.trim()
}

/**
 * Testable core of the three-strategy app launch. When [windowingMode] is non-null, the
 * am strategies carry `--windowingMode M --display D` so the task is born in the target
 * windowing mode (freeform cold-launch fix, on-car 2026-07-28 #151: trampoline apps like
 * YouTube settle inside the freeform task when the mode is applied at creation time rather
 * than flipped post-launch). The monkey fallback is always plain — monkey has no
 * windowing-mode flag. Package-name validation is the first gate; no shell command is
 * issued for an invalid name. [windowingMode] null is the plain path, identical to the
 * original launchApp behavior; [activityType] is then unused.
 */
internal fun launchAppCore(
    packageName: String,
    windowingMode: Int?,
    displayId: Int,
    activityType: Int,
    resolveComponent: (String) -> String?,
    shell: (String, List<String>) -> String,
): Boolean {
    if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return false
    // STANDARD is what `am start` assigns by default — only RECENTS needs the explicit flag.
    val typeFlag = if (activityType == ACTIVITY_TYPE_RECENTS) "--activityType $ACTIVITY_TYPE_RECENTS " else ""
    // displayId != 0 births the task on the target display: 2GIS/Qt dies on a cross-display move.
    val modePrefix = when {
        windowingMode != null -> "--windowingMode $windowingMode $typeFlag--display $displayId "
        displayId != 0 -> "--display $displayId "
        else -> ""
    }
    val component = resolveComponent(packageName)
    if (component != null) {
        // Component is passed as positional "$1" — never interpolated into the sh -c string.
        val r = shell("am start ${modePrefix}-n \"\$1\"", listOf(component))
        if (!r.contains("Error")) return true
    }
    val r2 = shell("am start ${modePrefix}-a android.intent.action.MAIN \"\$1\"", listOf(packageName))
    if (!r2.contains("Error")) return true
    val r3 = shell("monkey -p \"\$1\" -c android.intent.category.LAUNCHER 1", listOf(packageName))
    return !r3.contains("Error") && !r3.contains("error")
}

/**
 * Launches [packageName] via am/monkey strategies. Returns true if a launch command ran without an
 * obvious "Error". Mirrors CarControlImpl.launchApp (simplified to a boolean).
 */
private fun launchApp(packageName: String): Boolean =
    launchAppCore(packageName, null, 0, ACTIVITY_TYPE_STANDARD, ::resolveLaunchComponent, amShell)

/**
 * Testable core of the "raise existing freeform task" operation: issues the single
 * `am start --windowingMode 5 [--activityType 3] --display <displayId> -n <component>` command
 * that brings a running freeform task back to front without cold-launching it.
 *
 * Package regex-gated (same rule as [launchAppCore]). Returns false when the package is
 * invalid, the component cannot be resolved (app is not installed / no LAUNCHER activity),
 * or the shell command reports an error. Does NOT fall back to MAIN or monkey strategies —
 * those would open a second fullscreen task, collapsing the split.
 *
 * Note: `am start -n` WILL cold-launch a dead pane app (in freeform on the given display,
 * by virtue of the --windowingMode 5 flag); this is the intended recovery behavior when a
 * pane app was killed while the split session was active.
 *
 * The freeform placement itself is delegated to [ensureTypedFreeform] — the single owner of
 * the "a live freeform task must match its desired activityType" invariant, shared with
 * [setWindowingModeCompat]; [desiredActivityType] is the caller's choice (split panes:
 * STANDARD, cluster projection: RECENTS).
 * Its [IllegalStateException] is mapped back to this function's Boolean contract.
 *
 * NOTE: the defaults of [taskIdForPackage] / [getActivityType] report "no task / unknown type",
 * which disables the STANDARD handling. Production call sites must pass real implementations.
 *
 * Follows the launchAppCore/setWindowingModeCompat lambda-injection idiom so tests can
 * assert the exact shell command without spawning a real shell.
 */
internal fun raiseFreeformTaskCore(
    packageName: String,
    displayId: Int,
    desiredActivityType: Int,
    resolveComponent: (String) -> String?,
    shell: (String, List<String>) -> String,
    taskIdForPackage: (String) -> Int = { _ -> -1 },
    getActivityType: (Int) -> Int = { _ -> -1 },
    sleep: (Long) -> Unit = { _ -> },
): Boolean {
    if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return false
    val component = resolveComponent(packageName) ?: return false
    val taskId = taskIdForPackage(packageName)
    val activityType = if (taskId != -1) getActivityType(taskId) else -1
    return try {
        ensureTypedFreeform(taskId, activityType, desiredActivityType, displayId, component, shell, sleep)
        true
    } catch (e: IllegalStateException) {
        false
    }
}

/**
 * The single owner of the freeform typing invariant: **a live freeform task must match its
 * desired activityType.** `am start --activityType N` types a task only when it is CREATED — the
 * flag never re-types a live task (on-car 390: both panes read type=standard mode=freeform after
 * an in-place flip), so a live task of the wrong type has to be removed and recreated.
 * Both freeform entry points — [raiseFreeformTaskCore] and the freeform branch of
 * [setWindowingModeCompat] — go through here instead of each deciding for itself.
 *
 * [desiredActivityType] is the caller's choice: split panes ask for STANDARD (392), cluster
 * projection asks for RECENTS. Migration note: v3.9 created its panes as RECENTS, so on the
 * first 392 split the live panes are re-typed through the remove branch below.
 *
 * Three cases, by [liveActivityType]:
 * - Already the desired type: light path — the single `am start` with the freeform flags re-uses
 *   the existing task and applies mode+display to it (validated on-car; the task id survives).
 * - The other half of the STANDARD/RECENTS pair: `am stack remove` → settle → the same `am start`,
 *   which recreates the task with the desired type. The app PROCESS and its services survive the
 *   remove (only activities die), so media keeps playing and the navigator restores its own
 *   guidance session.
 * - No task ([taskId] == -1), unknown type (-1: reflection broken, task not found) or an exotic
 *   type (HOME/ASSISTANT/DREAM): the same single `am start`, which creates the task with the flags
 *   applied. Nothing is removed on a guess — killing activities blindly is worse than a mistyped
 *   task, and there may be no task at all.
 *
 * Throws [IllegalStateException] when the remove or the start fails. A swallowed remove failure
 * would let the relaunch deliver its intent to the still-alive task and report success with the
 * pane left un-retyped (codex pre-release audit 2026-07-16); "Exception occurred while
 * executing" is the am wording for an in-process throw, while a missing task prints nothing and
 * the relaunch then correctly creates a fresh task.
 */
internal fun ensureTypedFreeform(
    taskId: Int,
    liveActivityType: Int,
    desiredActivityType: Int,
    displayId: Int,
    component: String,
    shell: (String, List<String>) -> String,
    sleep: (Long) -> Unit,
) {
    // Only the STANDARD <-> RECENTS pair is ever retyped. Exotic live types (HOME/ASSISTANT/DREAM)
    // and the unknown -1 are left untouched: nothing this daemon places into freeform is supposed
    // to carry them, and removing such a task would be a behavior change on the RECENTS direction
    // that v3.9 never made (fleet safety).
    val retypable = liveActivityType == ACTIVITY_TYPE_STANDARD || liveActivityType == ACTIVITY_TYPE_RECENTS
    if (taskId != -1 && retypable && liveActivityType != desiredActivityType) {
        // taskId is an internal Int — safe to inline; no user/PM input involved.
        val removed = shell("am stack remove $taskId", emptyList())
        if (removed.contains("Error") || removed.contains("Exception")) {
            throw IllegalStateException("am stack remove failed: ${removed.take(200)}")
        }
        sleep(500L)
    }
    // STANDARD is the default type `am start` assigns at task creation — the flag is only
    // needed for RECENTS. Component is passed as positional "$1" — never interpolated.
    val typeFlag = if (desiredActivityType == ACTIVITY_TYPE_RECENTS) "--activityType $ACTIVITY_TYPE_RECENTS " else ""
    val out = shell("am start --windowingMode $WINDOWING_MODE_FREEFORM $typeFlag--display $displayId -n \"\$1\"", listOf(component))
    // Same failure wording as the remove above: am prints "Error:" for a rejected intent and
    // "Exception occurred while executing" for an in-process throw. Both mean the pane is not there.
    if (out.contains("Error") || out.contains("Exception")) {
        throw IllegalStateException("am start freeform failed: ${out.take(200)}")
    }
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
 * Finds [packageName]'s task id, launching the app and polling (16 x 500ms + settle pause)
 * when it is not running yet. Shared by launchAndForce and launchFreeform. Blocking.
 * [windowingMode] non-null (freeform path only) passes the mode and [activityType] to the launch
 * command so the task is born freeform with the right type; null (plain path, launchAndForce)
 * keeps the existing behavior and ignores [activityType].
 */
private fun resolveOrLaunchTask(
    packageName: String,
    windowingMode: Int? = null,
    displayId: Int = 0,
    activityType: Int,
): Int {
    var taskId = findTaskId(packageName)
    if (taskId <= 0) {
        launchAppCore(packageName, windowingMode, displayId, activityType, ::resolveLaunchComponent, amShell)
        var attempt = 1
        while (attempt < 16) {
            taskId = findTaskId(packageName)
            if (taskId > 0) break
            Thread.sleep(500L)
            attempt++
        }
        Thread.sleep(1500L)
    }
    return taskId
}

/**
 * Launches [packageName] on [displayId] and pins it there with a short persistence loop
 * (move -> bounds -> focus, x2). Returns true once redirection ran. Mirrors CarControlImpl.launchAndForce.
 * Blocking (Thread.sleep) — runs on a binder threadpool thread; the app side uses a 15s timeout.
 */
private fun launchAndForce(packageName: String, displayId: Int, width: Int, height: Int): Boolean {
    // Not-yet-running apps are born on the target display — 2GIS/Qt dies if moved there instead.
    val taskId = resolveOrLaunchTask(
        packageName, windowingMode = null, displayId = displayId, activityType = ACTIVITY_TYPE_STANDARD,
    )
    if (taskId <= 0) return false
    // Each redirect op is best-effort, mirroring CarControlImpl (every reflective call there returns
    // a status string and swallows its own exception). resizeTask in particular throws "not allowed"
    // on a fullscreen task — that must NOT abort the move/focus or bubble up as a launchAndForce
    // failure, otherwise the caller tears down the VirtualDisplay Navi was just moved onto before it
    // can render. The VD size (mini=640 / full=1280) already sets the geometry, so a resize failure
    // is harmless.
    repeat(2) {
        runCatching { moveTaskToDisplayReflect(taskId, displayId) }
        runCatching { setTaskBoundsReflect(taskId, 0, 0, width, height) }
        runCatching { setFocusedTaskReflect(taskId) }
        Thread.sleep(200L)
    }
    // App died during the move (2GIS/Qt) → relaunch ONCE born on the display; no retry loop.
    if (findTaskId(packageName) <= 0) {
        val rebornId = resolveOrLaunchTask(
            packageName, windowingMode = null, displayId = displayId, activityType = ACTIVITY_TYPE_STANDARD,
        )
        if (rebornId <= 0) return false
        runCatching { setTaskBoundsReflect(rebornId, 0, 0, width, height) }
        runCatching { setFocusedTaskReflect(rebornId) }
    }
    return true
}

/** Status codes for [launchFreeformCore] / TX_LAUNCH_FREEFORM. */
internal object FreeformResultCodes {
    const val OK = 0
    const val FAILED = -1
    const val UNAVAILABLE = -2
}

// WindowConfiguration windowing modes (android.app; hidden constants, stable since API 28).
internal const val WINDOWING_MODE_FULLSCREEN = 1
internal const val WINDOWING_MODE_FREEFORM = 5
// Who wants which type (392): split panes are STANDARD, cluster projection stays RECENTS.
// RECENTS suppresses the AOSP-12 freeform DecorCaption (hasWindowDecorCaption() is true only for
// activityType == STANDARD && windowingMode == FREEFORM), which is why v3.9 created panes as
// RECENTS — but recents-typed tasks nest as leaves under one shared root task, so their input
// shields are not cropped to the pane bounds and only the top pane receives touch (on-car
// 2026-07-29, ActivityRecordInputSink fullscreen; memory project_split_screen_wave.md). Panes
// therefore take the caption and keep their own root task; the cluster has a single pane and is
// unaffected, so it keeps RECENTS.
// IMPORTANT (on-car 390): `--activityType N` types a task only when the task is CREATED. Passing it
// to `am start` for an ALREADY LIVE task changes the windowing mode and nothing else. Re-typing a
// live task therefore means removing it and letting the relaunch create it (see
// [ensureTypedFreeform], the single owner of that rule).
internal const val ACTIVITY_TYPE_RECENTS = 3
internal const val ACTIVITY_TYPE_STANDARD = 1

/** Live (windowingMode, displayId) of a task as reported by ATMS; null = unknown. */
internal data class TaskModeState(val windowingMode: Int, val displayId: Int)

/**
 * Does this throwable chain look like "freeform windowing is not enabled on this boot"
 * (→ UNAVAILABLE: the app latches the reboot hint and falls back to the VD pipeline) as
 * opposed to a transient per-task failure (→ FAILED: fall back WITHOUT the reboot hint)?
 * Matches AOSP wordings ("freeform ... not supported/enabled/disabled") without pinning an
 * exact string the ROM may have reworded.
 */
internal fun isFreeformUnsupported(t: Throwable): Boolean =
    generateSequence(t) { c -> c.cause.takeIf { it !== c } }
        .take(5)
        .any { thr ->
            val msg = thr.message?.lowercase() ?: return@any false
            "freeform" in msg && ("support" in msg || "enabl" in msg || "disabl" in msg)
        }

/**
 * Extracted body of the TX_SET_TASK_WINDOWING_MODE handler (Q2-4 / anti-vacuity).
 * All side-effecting dependencies are injected so the handler logic can be unit-tested
 * independently of the binder dispatch machinery in main().
 *
 * Two callers send this TX: FULLSCREEN pull-back from ClusterProjectionManager, and FREEFORM
 * gentle flip from SplitSessionManager.forceStopIfNeeded (preserves the app process so music
 * keeps playing). display=0 is the correct target for both: fullscreen restores to the main
 * display; freeform panels on DiLink run on display 0.
 */
internal fun handleSetWindowingModeTx(
    taskId: Int,
    mode: Int,
    desiredActivityType: Int,
    reflectSet: (Int, Int) -> Unit,
    resolveComponent: () -> String?,
    shell: (String, List<String>) -> String,
    getActivityType: (Int) -> Int,
    sleep: (Long) -> Unit,
) = setWindowingModeCompat(
    taskId, mode, 0, desiredActivityType, reflectSet, resolveComponent, shell, getActivityType, sleep,
)

/**
 * Windowing-mode switch that survives ROMs without the binder API. AOSP S removed
 * IActivityTaskManager.setTaskWindowingMode and DiLink 5 did not restore it (on-car
 * NoSuchMethodException, 2026-07-15), so after that specific throw the shell ActivityStarter
 * path takes over: `am start --windowingMode 5 --display N -n <cmp>` applies mode+display to an
 * EXISTING task, keeping its task id (validated on-car). Freeform sticks to a task on this ROM —
 * the only way back to fullscreen is removing the stack and relaunching on the main display
 * (the navigator restores its own guidance session; the task id changes). Any other [reflectSet]
 * throw is rethrown untouched so [launchFreeformCore]'s classification still sees it.
 *
 * Q2 / F-2+F-6: [getActivityType] is queried before attempting [reflectSet] in the freeform
 * direction. [reflectSet] changes the windowing mode but preserves the activityType — so a task
 * of the wrong type would stay that way. When [getActivityType] returns anything other than
 * [desiredActivityType], [reflectSet] is skipped and the shell path is taken directly.
 *
 * The shell freeform path itself is [ensureTypedFreeform] — the single owner of the "a live
 * freeform task must match its desired activityType" invariant, shared with
 * [raiseFreeformTaskCore]; the type read here is handed to it. For the fullscreen direction the
 * type check is irrelevant ([skipReflect] stays false) and that branch is untouched.
 * Default [getActivityType] returns -1 (unknown) — the conservative path (skip reflectSet, plain
 * relaunch).
 * NOTE: omitting [getActivityType] silently disables the reflectSet fast path for the freeform
 * direction. Every production call site must pass a real implementation.
 */
internal fun setWindowingModeCompat(
    taskId: Int,
    windowingMode: Int,
    freeformDisplayId: Int,
    desiredActivityType: Int,
    reflectSet: (Int, Int) -> Unit,
    resolveComponent: () -> String?,
    shell: (String, List<String>) -> String,
    getActivityType: (Int) -> Int = { _ -> -1 },
    sleep: (Long) -> Unit,
) {
    // Skip reflectSet when placing into freeform and the task's activityType is not the desired
    // one. reflectSet changes windowingMode but preserves the existing activityType; the shell
    // path recreates the task with the right type. Unknown type (-1) never matches — conservative.
    val skipReflect: Boolean
    var freeformType = -1
    if (windowingMode == WINDOWING_MODE_FREEFORM) {
        val type = getActivityType(taskId)
        freeformType = type
        val branch = when {
            type == desiredActivityType -> "reflectSet (activityType matches desired=$desiredActivityType)"
            type == -1                  -> "shell coerce (activityType unknown, reflection broken or task not found; desired=$desiredActivityType)"
            else                        -> "shell coerce (activityType=$type, desired=$desiredActivityType)"
        }
        android.util.Log.d("bydmate_helper", "setWindowingModeCompat task=$taskId mode=$windowingMode → $branch")
        skipReflect = type != desiredActivityType
    } else {
        skipReflect = false
    }
    if (!skipReflect) {
        try {
            reflectSet(taskId, windowingMode)
            return
        } catch (e: NoSuchMethodException) {
            // fall through to the shell path
        }
    }
    val component = resolveComponent()
        ?: throw IllegalStateException("setWindowingModeCompat: no launcher component for task=$taskId")
    if (windowingMode == WINDOWING_MODE_FREEFORM) {
        // The typing invariant lives in one place for both freeform entry points.
        ensureTypedFreeform(taskId, freeformType, desiredActivityType, freeformDisplayId, component, shell, sleep)
    } else {
        // A swallowed remove failure would let the relaunch deliver its intent to the
        // still-alive freeform task and report success with the task stranded on the
        // cluster (codex pre-release audit 2026-07-16). "Exception occurred while
        // executing" is the am wording for an in-process throw; a missing task prints
        // nothing and the relaunch then creates a fresh task — the correct outcome.
        // taskId is an internal Int — safe to inline; no user/PM input involved.
        val removed = shell("am stack remove $taskId", emptyList())
        if (removed.contains("Error") || removed.contains("Exception")) {
            throw IllegalStateException("am stack remove failed: ${removed.take(200)}")
        }
        sleep(500L)
        val out = shell("am start --display 0 -n \"\$1\"", listOf(component))
        if (out.contains("Error")) throw IllegalStateException("am start fullscreen failed: ${out.take(200)}")
    }
}

/**
 * Testable core of the direct freeform launch. Idempotent against a task stranded mid-way by a
 * quickboot kill: [state] reads the task's live windowing mode + display, so an already-freeform
 * task of the [desiredActivityType] skips [setMode], a [setMode] throw is forgiven when the mode
 * landed anyway (relaunch race), and a [move] that throws "already there" is accepted when the
 * task sits on the target display. [getActivityType] guards that skip: a live task whose type
 * differs from [desiredActivityType] goes through [setMode] (and thus [ensureTypedFreeform]) to be
 * recreated with the right type, because the type is the caller's whole point — RECENTS for the
 * cluster, STANDARD for split panes. Unknown type (-1) skips the check, as does the default
 * [getActivityType]. [setMode] gets ONE bounded retry after a settle pause — a transient vendor throw
 * (e.g. racing the task's own relaunch) must not dump the launch into the VD fallback.
 * Availability probe: AOSP does NOT throw when freeform is off — Task.setWindowingMode silently
 * coerces the request to UNDEFINED — so the probe is the live state: no throw + state still not
 * freeform (silent no-op), or a throw matching [isFreeformUnsupported], reports UNAVAILABLE and
 * the app shows the reboot hint; any other throw is FAILED (plain VD fallback, no hint).
 * Success requires the FINAL live state to show freeform on [displayId]: moveRootTaskToDisplay
 * is void and may silently no-op, and the reparent itself can trigger a vendor relaunch that
 * coerces the mode back — a non-throwing move alone proves nothing. When state is unreadable,
 * call outcomes are trusted (legacy behavior). On failure after the switch, restore FULLSCREEN
 * (best-effort) so the task is not stranded as a tiny freeform window on its ORIGINAL display.
 * [bounds] and [focus] remain best-effort (mirroring launchAndForce), two passes with a settle
 * pause.
 *
 * Task identity: [setMode] may RECREATE the task instead of flipping it (a live task cannot be
 * re-typed in place — see [ensureTypedFreeform]), which gives it a NEW id and leaves
 * the incoming [taskId] pointing at a removed task. [resolveCurrentTaskId] is re-queried after
 * every [setMode] attempt and its answer is the single source of truth for the rest of the run:
 * state verification, the reparent, bounds, focus and the final confirmation all address the live
 * task. Default: keep the incoming id (a caller that cannot re-resolve keeps the legacy
 * behaviour); a resolver returning <= 0 (task momentarily invisible mid-relaunch) is ignored in
 * favour of the last known id.
 */
internal fun launchFreeformCore(
    taskId: Int,
    displayId: Int,
    left: Int, top: Int, right: Int, bottom: Int,
    desiredActivityType: Int,
    setMode: (Int, Int) -> Unit,
    move: (Int, Int) -> Unit,
    bounds: (Int, Int, Int, Int, Int) -> Unit,
    focus: (Int) -> Unit,
    state: (Int) -> TaskModeState? = { null },
    getActivityType: (Int) -> Int = { -1 },
    log: (String, Throwable?) -> Unit = { _, _ -> },
    resolveCurrentTaskId: () -> Int = { taskId },
    sleep: (Long) -> Unit,
): Int {
    if (taskId <= 0) return FreeformResultCodes.FAILED
    if (left < 0 || top < 0 || right <= left || bottom <= top) return FreeformResultCodes.FAILED
    // The one place that owns "which task id is current" (see KDoc).
    var liveTaskId = taskId
    fun refreshTaskId() {
        val resolved = runCatching { resolveCurrentTaskId() }.getOrNull() ?: return
        if (resolved > 0 && resolved != liveTaskId) {
            log("task recreated by setMode: $liveTaskId -> $resolved; retargeting", null)
            liveTaskId = resolved
        }
    }
    // A task that is already freeform is only "ready" when it also carries the desired
    // activityType: the retype happens inside [setMode], so accepting it on the windowing mode
    // alone would leave a split pane STANDARD on the cluster, or a v3.9 RECENTS leftover
    // un-migrated in a split (final review 2026-07-29). An unreadable type (-1) keeps the historic
    // skip — no blind retype.
    fun typeMatches(t: Int): Boolean {
        val live = runCatching { getActivityType(t) }.getOrNull() ?: return true
        return live == -1 || live == desiredActivityType
    }
    // Phase 1: ensure the task is in freeform (idempotent, one bounded retry, state-verified).
    var freeform = runCatching { state(liveTaskId) }.getOrNull()?.windowingMode == WINDOWING_MODE_FREEFORM &&
        typeMatches(liveTaskId)
    var lastThrown: Throwable? = null
    var silentNoOp = false
    var attempt = 0
    while (!freeform && attempt < 2) {
        attempt++
        lastThrown = try {
            setMode(liveTaskId, WINDOWING_MODE_FREEFORM)
            null
        } catch (t: Throwable) {
            t
        }
        lastThrown?.let { log("setTaskWindowingMode(task=$liveTaskId, FREEFORM) attempt $attempt threw", it) }
        // Before reading anything back: the task may now be a different one.
        refreshTaskId()
        val after = runCatching { state(liveTaskId) }.getOrNull()
        freeform = when {
            after != null -> after.windowingMode == WINDOWING_MODE_FREEFORM && typeMatches(liveTaskId)
            else -> lastThrown == null // state unknown: trust the call outcome (legacy behavior)
        }
        if (!freeform) {
            silentNoOp = lastThrown == null
            if (attempt < 2) sleep(250L)
        }
    }
    if (!freeform) {
        // The shell compat path applies mode AND display in one `am start`: when freeform is off
        // the mode is coerced away but the display move can still land — pull the task back to
        // the main display so it does not vanish onto the unwatched cluster (no-op for reflect).
        runCatching { move(liveTaskId, 0) }
        return when {
            silentNoOp -> FreeformResultCodes.UNAVAILABLE // AOSP coerces silently when freeform is off
            isFreeformUnsupported(lastThrown!!) -> FreeformResultCodes.UNAVAILABLE
            else -> FreeformResultCodes.FAILED
        }
    }
    // Phase 2: reparent to the target display and pin (move is retried; bounds/focus best-effort).
    var movedByCall = false
    repeat(2) {
        if (runCatching { move(liveTaskId, displayId) }.isSuccess) movedByCall = true
        runCatching { bounds(liveTaskId, left, top, right, bottom) }
        runCatching { focus(liveTaskId) }
        sleep(200L)
    }
    val final = runCatching { state(liveTaskId) }.getOrNull()
    val placed = when {
        final != null -> final.displayId == displayId && final.windowingMode == WINDOWING_MODE_FREEFORM
        else -> movedByCall
    }
    if (placed && !movedByCall) log("move(task=$liveTaskId) threw but task already on display $displayId; accepting", null)
    if (!placed) {
        // The task would be stranded as a tiny freeform window on the wrong display (or was
        // coerced back to fullscreen by the reparent). Restore fullscreen (best-effort) and
        // report FAILED so the client falls back to the VD pipeline.
        log("freeform placement not confirmed: final=$final movedByCall=$movedByCall; restoring fullscreen", null)
        runCatching { setMode(liveTaskId, WINDOWING_MODE_FULLSCREEN) }
        return FreeformResultCodes.FAILED
    }
    return FreeformResultCodes.OK
}

/**
 * Direct freeform launch used by cluster projection: find-or-launch [packageName], switch its
 * task to freeform, move it to [displayId], apply the window bounds and focus it. Blocking
 * (launch retry loop) — binder threadpool thread; the app side uses a 15s timeout.
 */
private fun launchFreeform(
    packageName: String,
    displayId: Int,
    left: Int, top: Int, right: Int, bottom: Int,
    activityType: Int,
): Int {
    val taskId = resolveOrLaunchTask(packageName, WINDOWING_MODE_FREEFORM, displayId, activityType)
    return launchFreeformCore(
        taskId, displayId, left, top, right, bottom, activityType,
        setMode = { t, m ->
            setWindowingModeCompat(
                t, m, displayId,
                desiredActivityType = activityType,
                reflectSet = ::setTaskWindowingModeReflect,
                resolveComponent = { resolveLaunchComponent(packageName) },
                shell = amShell,
                // Q2 / F-2+F-6: read live activityType so a task of the wrong type (left by a
                // prior fullscreen exit relaunch, or created by an older version) is recreated
                // with the desired one via the shell path.
                getActivityType = { ti -> taskActivityType(ti) },
            ) { Thread.sleep(it) }
        },
        move = ::moveTaskToDisplayReflect,
        bounds = ::setTaskBoundsReflect,
        focus = ::setFocusedTaskReflect,
        state = ::taskModeState,
        // Same live-type lookup the setMode lambda feeds to ensureTypedFreeform: an already
        // freeform task of the wrong type must not skip the retype.
        getActivityType = { ti -> taskActivityType(ti) },
        // android.util.Log reaches logcat from the app_process daemon; System.err goes nowhere.
        // Passing the throwable prints the full stack trace including the cause chain.
        log = { msg, t -> android.util.Log.w("bydmate_helper", msg, t) },
        // setMode recreates a STANDARD task (new id) — re-resolve by package, never by the old id.
        resolveCurrentTaskId = { findTaskState(packageName)?.taskId ?: -1 },
    ) { Thread.sleep(it) }
}
