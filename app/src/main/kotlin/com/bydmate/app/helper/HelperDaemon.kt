@file:JvmName("HelperDaemon")
package com.bydmate.app.helper

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
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import kotlin.system.exitProcess

// Lock path on the device filesystem (writable by shell uid).
private const val LOCK_PATH = "/data/local/tmp/bydmate_helper.lock"

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
                    // The compat shell path relaunches by component, so the package is resolved
                    // from the task id. Clients only send FULLSCREEN through this TX (pull-back);
                    // the freeform display id is irrelevant here, hence 0.
                    setWindowingModeCompat(
                        taskId, mode, 0,
                        ::setTaskWindowingModeReflect,
                        { packageForTask(taskId)?.let { resolveLaunchComponent(it) } },
                        ::execShell,
                    ) { Thread.sleep(it) }
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

                HelperBinderProtocol.TX_LAUNCH_FREEFORM -> runCatching {
                    val pkg = data.readString() ?: ""
                    val displayId = data.readInt()
                    val l = data.readInt(); val t = data.readInt()
                    val r = data.readInt(); val b = data.readInt()
                    val status = launchFreeform(pkg, displayId, l, t, r, b)
                    reply?.writeInt(status); reply?.writeInt(0)
                    true
                }.getOrElse { reply?.writeInt(-1); reply?.writeInt(0); true }

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
        System.err.println("ERR: addService ${e.message}")
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

/** Runs a shell command (shell uid) and returns combined stdout/stderr. Mirrors CarControlImpl.exec. */
private fun execShell(command: String): String {
    val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
    val out = process.inputStream.bufferedReader().use { it.readText().trim() }
    val err = process.errorStream.bufferedReader().use { it.readText().trim() }
    process.waitFor()
    return buildString {
        if (out.isNotBlank()) append(out)
        if (err.isNotBlank()) { if (isNotEmpty()) append(" | STDERR: "); append(err) }
    }.ifEmpty { "OK" }
}

private class CmdResult(val code: Int, val stdout: String)

/**
 * Runs [script] under sh with [args] bound to positional params ($1, $2, …) so untrusted values
 * (e.g. an existing accessibility list read off the device) are passed as argv and NEVER re-parsed
 * by the shell — no injection, no quote-breakage. Returns ONLY stdout + exit code (stderr is sent
 * to /dev/null, so there is no second pipe to deadlock on and stdout can never be corrupted by an
 * stderr line — unlike [execShell], which merges them). Use this whenever success/output matters.
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
 * Self-grants notification-listener access for our MediaSessionListenerService stub, mirroring
 * enableAccessibilityService's remove-wait-readd cycle: NotificationManagerService's Settings
 * observer only re-binds listeners when the enabled_notification_listeners string actually
 * changes value, so re-adding a component already present would silently no-op after a crash.
 * Read-modify-write preserves other apps' listeners (our component is the only one we ever touch).
 * App-scoped, reversible Secure setting only; touches nothing on the vehicle (no autoservice/CAN).
 */
private fun enableNotificationListener(): Boolean {
    val component = HelperBinderProtocol.NOTIFICATION_LISTENER_COMPONENT
    val target = canonicalComponent(component)
    val current = readSecure("enabled_notification_listeners") ?: return false
    val others = current.split(':').filter { it.isNotEmpty() && canonicalComponent(it) != target }
    if (shExec("settings put secure enabled_notification_listeners \"\$1\"", others.joinToString(":")).code != 0) return false
    Thread.sleep(200L)
    if (shExec("settings put secure enabled_notification_listeners \"\$1\"", (others + component).joinToString(":")).code != 0) return false
    val after = (readSecure("enabled_notification_listeners") ?: return false).split(':').filter { it.isNotEmpty() }
    return after.any { canonicalComponent(it) == target }
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
 * Defense-in-depth: the result (and the package) is interpolated into `sh -c` by callers —
 * Android package names are strictly [A-Za-z0-9_.]; reject anything else so a caller can't
 * smuggle shell metacharacters into this shell-uid daemon. No real package is ever rejected.
 */
private fun resolveLaunchComponent(packageName: String): String? {
    if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return null
    val resolve = execShell("cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $packageName")
    return resolve.lineSequence().firstOrNull { it.contains("/") && !it.startsWith("No ") }?.trim()
}

/**
 * Launches [packageName] via am/monkey strategies. Returns true if a launch command ran without an
 * obvious "Error". Mirrors CarControlImpl.launchApp (simplified to a boolean).
 */
private fun launchApp(packageName: String): Boolean {
    if (!packageName.matches(Regex("[A-Za-z0-9_.]+"))) return false
    val component = resolveLaunchComponent(packageName)
    if (component != null) {
        val r = execShell("am start -n $component")
        if (!r.contains("Error")) return true
    }
    val r2 = execShell("am start -a android.intent.action.MAIN $packageName")
    if (!r2.contains("Error")) return true
    val r3 = execShell("monkey -p $packageName -c android.intent.category.LAUNCHER 1")
    return !r3.contains("Error") && !r3.contains("error")
}

/**
 * Finds [packageName]'s task id, launching the app and polling (16 x 500ms + settle pause)
 * when it is not running yet. Shared by launchAndForce and launchFreeform. Blocking.
 */
private fun resolveOrLaunchTask(packageName: String): Int {
    var taskId = findTaskId(packageName)
    if (taskId <= 0) {
        launchApp(packageName)
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
    val taskId = resolveOrLaunchTask(packageName)
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
 * Windowing-mode switch that survives ROMs without the binder API. AOSP S removed
 * IActivityTaskManager.setTaskWindowingMode and DiLink 5 did not restore it (on-car
 * NoSuchMethodException, 2026-07-15), so after that specific throw the shell ActivityStarter
 * path takes over: `am start --windowingMode 5 --display N -n <cmp>` applies mode+display to an
 * EXISTING task, keeping its task id (validated on-car). Freeform sticks to a task on this ROM —
 * the only way back to fullscreen is removing the stack and relaunching on the main display
 * (the navigator restores its own guidance session; the task id changes). Any other [reflectSet]
 * throw is rethrown untouched so [launchFreeformCore]'s classification still sees it.
 */
internal fun setWindowingModeCompat(
    taskId: Int,
    windowingMode: Int,
    freeformDisplayId: Int,
    reflectSet: (Int, Int) -> Unit,
    resolveComponent: () -> String?,
    shell: (String) -> String,
    sleep: (Long) -> Unit,
) {
    try {
        reflectSet(taskId, windowingMode)
        return
    } catch (e: NoSuchMethodException) {
        // fall through to the shell path
    }
    val component = resolveComponent()
        ?: throw IllegalStateException("setWindowingModeCompat: no launcher component for task=$taskId")
    if (windowingMode == WINDOWING_MODE_FREEFORM) {
        val out = shell("am start --windowingMode $WINDOWING_MODE_FREEFORM --display $freeformDisplayId -n $component")
        if (out.contains("Error")) throw IllegalStateException("am start freeform failed: ${out.take(200)}")
    } else {
        // A swallowed remove failure would let the relaunch deliver its intent to the
        // still-alive freeform task and report success with the task stranded on the
        // cluster (codex pre-release audit 2026-07-16). "Exception occurred while
        // executing" is the am wording for an in-process throw; a missing task prints
        // nothing and the relaunch then creates a fresh task — the correct outcome.
        val removed = shell("am stack remove $taskId")
        if (removed.contains("Error") || removed.contains("Exception")) {
            throw IllegalStateException("am stack remove failed: ${removed.take(200)}")
        }
        sleep(500L)
        val out = shell("am start --display 0 -n $component")
        if (out.contains("Error")) throw IllegalStateException("am start fullscreen failed: ${out.take(200)}")
    }
}

/**
 * Testable core of the direct freeform launch. Idempotent against a task stranded mid-way by a
 * quickboot kill: [state] reads the task's live windowing mode + display, so an already-freeform
 * task skips [setMode], a [setMode] throw is forgiven when the mode landed anyway (relaunch
 * race), and a [move] that throws "already there" is accepted when the task sits on the target
 * display. [setMode] gets ONE bounded retry after a settle pause — a transient vendor throw
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
 */
internal fun launchFreeformCore(
    taskId: Int,
    displayId: Int,
    left: Int, top: Int, right: Int, bottom: Int,
    setMode: (Int, Int) -> Unit,
    move: (Int, Int) -> Unit,
    bounds: (Int, Int, Int, Int, Int) -> Unit,
    focus: (Int) -> Unit,
    state: (Int) -> TaskModeState? = { null },
    log: (String, Throwable?) -> Unit = { _, _ -> },
    sleep: (Long) -> Unit,
): Int {
    if (taskId <= 0) return FreeformResultCodes.FAILED
    if (left < 0 || top < 0 || right <= left || bottom <= top) return FreeformResultCodes.FAILED
    // Phase 1: ensure the task is in freeform (idempotent, one bounded retry, state-verified).
    var freeform = runCatching { state(taskId) }.getOrNull()?.windowingMode == WINDOWING_MODE_FREEFORM
    var lastThrown: Throwable? = null
    var silentNoOp = false
    var attempt = 0
    while (!freeform && attempt < 2) {
        attempt++
        lastThrown = try {
            setMode(taskId, WINDOWING_MODE_FREEFORM)
            null
        } catch (t: Throwable) {
            t
        }
        lastThrown?.let { log("setTaskWindowingMode(task=$taskId, FREEFORM) attempt $attempt threw", it) }
        val after = runCatching { state(taskId) }.getOrNull()
        freeform = when {
            after != null -> after.windowingMode == WINDOWING_MODE_FREEFORM
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
        runCatching { move(taskId, 0) }
        return when {
            silentNoOp -> FreeformResultCodes.UNAVAILABLE // AOSP coerces silently when freeform is off
            isFreeformUnsupported(lastThrown!!) -> FreeformResultCodes.UNAVAILABLE
            else -> FreeformResultCodes.FAILED
        }
    }
    // Phase 2: reparent to the target display and pin (move is retried; bounds/focus best-effort).
    var movedByCall = false
    repeat(2) {
        if (runCatching { move(taskId, displayId) }.isSuccess) movedByCall = true
        runCatching { bounds(taskId, left, top, right, bottom) }
        runCatching { focus(taskId) }
        sleep(200L)
    }
    val final = runCatching { state(taskId) }.getOrNull()
    val placed = when {
        final != null -> final.displayId == displayId && final.windowingMode == WINDOWING_MODE_FREEFORM
        else -> movedByCall
    }
    if (placed && !movedByCall) log("move(task=$taskId) threw but task already on display $displayId; accepting", null)
    if (!placed) {
        // The task would be stranded as a tiny freeform window on the wrong display (or was
        // coerced back to fullscreen by the reparent). Restore fullscreen (best-effort) and
        // report FAILED so the client falls back to the VD pipeline.
        log("freeform placement not confirmed: final=$final movedByCall=$movedByCall; restoring fullscreen", null)
        runCatching { setMode(taskId, WINDOWING_MODE_FULLSCREEN) }
        return FreeformResultCodes.FAILED
    }
    return FreeformResultCodes.OK
}

/**
 * Direct freeform launch used by cluster projection: find-or-launch [packageName], switch its
 * task to freeform, move it to [displayId], apply the window bounds and focus it. Blocking
 * (launch retry loop) — binder threadpool thread; the app side uses a 15s timeout.
 */
private fun launchFreeform(packageName: String, displayId: Int, left: Int, top: Int, right: Int, bottom: Int): Int {
    val taskId = resolveOrLaunchTask(packageName)
    return launchFreeformCore(
        taskId, displayId, left, top, right, bottom,
        setMode = { t, m ->
            setWindowingModeCompat(
                t, m, displayId,
                ::setTaskWindowingModeReflect, { resolveLaunchComponent(packageName) }, ::execShell,
            ) { Thread.sleep(it) }
        },
        move = ::moveTaskToDisplayReflect,
        bounds = ::setTaskBoundsReflect,
        focus = ::setFocusedTaskReflect,
        state = ::taskModeState,
        // android.util.Log reaches logcat from the app_process daemon; System.err goes nowhere.
        // Passing the throwable prints the full stack trace including the cause chain.
        log = { msg, t -> android.util.Log.w("bydmate_helper", msg, t) },
    ) { Thread.sleep(it) }
}
