package com.bydmate.app.helper

import android.graphics.Rect

// Reflection shared by the task-list walks in HelperDaemon.kt (findTaskId, packageForTask,
// topTaskPackage, topTaskInfo, findTaskState, taskModeState, taskActivityType).
// None of these catch: each walk keeps its own runCatching and its own fallbacks.

/** Package of [task] from topActivity, else baseActivity; null when neither yields one. */
internal fun taskPackage(task: Any): String? =
    listOf("topActivity", "baseActivity").firstNotNullOfOrNull { fieldName ->
        fieldByName(task, fieldName)?.let { f ->
            f.isAccessible = true
            (f.get(task) as? android.content.ComponentName)?.packageName
        }
    }

/** Id of [task] from the int field "taskId" (fallback "id"); null when the task has neither. */
internal fun taskIdOf(task: Any): Int? {
    val idField = fieldByName(task, "taskId") ?: fieldByName(task, "id") ?: return null
    idField.isAccessible = true
    return idField.getInt(task)
}

/** task.configuration.windowConfiguration; null when any link of the chain is missing. */
internal fun taskWindowConfiguration(task: Any): Any? {
    val configField = fieldByName(task, "configuration") ?: return null
    configField.isAccessible = true
    val config = configField.get(task) ?: return null
    val winConfigField = fieldByName(config, "windowConfiguration") ?: return null
    winConfigField.isAccessible = true
    return winConfigField.get(config)
}

/** TaskInfo.displayId of [task], or [fallback] when the ROM has no such field. */
internal fun taskDisplayId(task: Any, fallback: Int): Int =
    fieldByName(task, "displayId")?.let { f -> f.isAccessible = true; f.getInt(task) } ?: fallback

/** Resolves IActivityTaskManager via ActivityTaskManager.getService() (hidden API, ok under app_process). */
internal fun activityTaskManager(): Any =
    Class.forName("android.app.ActivityTaskManager").getMethod("getService").invoke(null)
        ?: throw IllegalStateException("ActivityTaskManager.getService() returned null")

/** Walks the superclass chain for a declared field [name] (mirrors CarControlImpl.findField). */
internal fun fieldByName(target: Any, name: String): java.lang.reflect.Field? {
    var cls: Class<*>? = target.javaClass
    while (cls != null) {
        try { return cls.getDeclaredField(name) } catch (e: NoSuchFieldException) { cls = cls.superclass }
    }
    return null
}

// --- IActivityTaskManager compatibility (Android 12 primary, Android 10 head units fallback) ---
// Each verb resolves the CURRENT signature first and caches the Method for the daemon process:
// the split/cluster watchdogs call these at 1 Hz, so after the first call there must be no
// getMethod lookup, no methods[] scan and no thrown exception on either Android version.

/** Resolved IActivityTaskManager.getTasks; [atmGetTasksTakesFlags] false = Android 10 getTasks(int). */
@Volatile private var atmGetTasksMethod: java.lang.reflect.Method? = null
@Volatile private var atmGetTasksTakesFlags: Boolean = true

/**
 * Running task list via getTasks(maxNum, false, false) (Android 12), falling back to
 * getTasks(maxNum) on Android 10 ROMs (DiLink 3.0 / 4.0) that have no flags overload.
 */
internal fun atmGetTasks(iAtm: Any, maxNum: Int): List<*>? {
    var m = atmGetTasksMethod
    if (m == null) {
        m = try {
            iAtm.javaClass.getMethod(
                "getTasks", Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType,
            )
        } catch (e: NoSuchMethodException) {
            atmGetTasksTakesFlags = false
            android.util.Log.i("bydmate_helper", "atm compat: getTasks(int) (Android 10 signature)")
            iAtm.javaClass.getMethod("getTasks", Int::class.javaPrimitiveType)
        }
        atmGetTasksMethod = m
    }
    val raw = if (atmGetTasksTakesFlags) m.invoke(iAtm, maxNum, false, false) else m.invoke(iAtm, maxNum)
    return raw as? List<*>
}

/** The running task list. The task walks below take it as a parameter so a test can hand them one. */
private fun atmTasks(maxNum: Int): List<*>? = atmGetTasks(activityTaskManager(), maxNum)

/**
 * Finds the running task id of [packageName]. Reconstructed from CarControlImpl.getTopActivityPackage:
 * iAtm.getTasks(maxNum, false, false) -> List<RunningTaskInfo>; match topActivity/baseActivity package;
 * read int field "taskId" (fallback "id"). Returns -1 when not found.
 * NOTE: field names validated on-car in Phase 2.
 */
internal fun findTaskId(packageName: String, atm: (Int) -> List<*>? = ::atmTasks): Int {
    val tasks = atm(100) ?: return -1
    for (task in tasks) {
        if (task == null) continue
        if (taskPackage(task) == packageName) {
            return taskIdOf(task) ?: continue
        }
    }
    return -1
}

/** Inverse of [findTaskId]: the package owning [taskId], or null when the task is not listed. */
internal fun packageForTask(taskId: Int, atm: (Int) -> List<*>? = ::atmTasks): String? = runCatching {
    val tasks = atm(100) ?: return@runCatching null
    for (task in tasks) {
        if (task == null) continue
        if (taskIdOf(task) != taskId) continue
        return@runCatching taskPackage(task)
    }
    null
}.getOrNull()

/**
 * Returns the package name of the foreground (first/top-of-stack) task, or null when no task
 * exists or reflection fails. Uses the same getTasks reflection as [findTaskId] / [findTaskState];
 * tasks[0] is the most-recently-used / currently focused task on Android recents ordering.
 * Mirrors TX_GET_VERSION pattern: caller writes "" on null so the client reads a defined value.
 */
internal fun topTaskPackage(atm: (Int) -> List<*>? = ::atmTasks): String? = runCatching {
    val tasks = atm(1) ?: return@runCatching null
    val task = tasks.firstOrNull() ?: return@runCatching null
    taskPackage(task)
}.getOrNull()

/**
 * Snapshot of the top root task returned by TX_GET_TOP_TASK (Q3 / F-3 COVERED detection).
 * [displayId] mirrors the TaskInfo.displayId convention: 0 = main display, 2 = cluster fission.
 * [visible] is RunningTaskInfo.isVisible as 1/0, or -1 when the ROM has no such field: the MRU
 * ordering of getTasks() lists tasks nobody can see, and the split adoption must tell them apart.
 */
internal data class TopTaskInfo(
    val pkg: String,
    val taskId: Int,
    val windowingMode: Int,
    val activityType: Int,
    val displayId: Int,
    val visible: Int,
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
internal fun topTaskInfo(atm: (Int) -> List<*>? = ::atmTasks): TopTaskInfo? = runCatching {
    val tasks = atm(10) ?: return@runCatching null
    // Pick the first task on the main display (displayId == 0).
    // C-4: if the displayId field is absent via reflection, skip the task rather than defaulting
    // to 0 (which would synthesise main-display presence and falsely trigger COVERED teardown).
    val task = tasks.firstOrNull { rawTask ->
        rawTask != null &&
        runCatching { taskDisplayId(rawTask, fallback = -1) == 0 }.getOrDefault(false)
    } ?: return@runCatching null
    val pkg = taskPackage(task) ?: return@runCatching null
    val taskId = taskIdOf(task) ?: -1
    // Require windowConfiguration: returning null on failure prevents synthesising FULLSCREEN@display0,
    // which would look like a foreign fullscreen app and falsely trigger COVERED teardown (Q3-1).
    val winConfig = taskWindowConfiguration(task) ?: return@runCatching null
    // getWindowingMode throws → outer runCatching catches → getOrNull() returns null (safe).
    val windowingMode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as Int
    val activityType = runCatching {
        winConfig.javaClass.getMethod("getActivityType").invoke(winConfig) as Int
    }.getOrDefault(-1)
    val displayId = runCatching { taskDisplayId(task, fallback = 0) }.getOrDefault(0)
    // -1 (unknown) rather than a guess: the client treats anything but 1 as "not visible".
    val visible = runCatching {
        fieldByName(task, "isVisible")?.let { f ->
            f.isAccessible = true
            if (f.getBoolean(task)) 1 else 0
        } ?: -1
    }.getOrDefault(-1)
    TopTaskInfo(pkg, taskId, windowingMode, activityType, displayId, visible)
}.getOrNull()

/**
 * Windowing state (taskId, mode, bounds, displayId) for a running task; null when not found
 * or reflection fails. [displayId] is the Android display the task lives on: 0 = main screen,
 * 2 = cluster fission display on Leopard 3. Falls back to 0 when the field is absent on the ROM,
 * so an old app reading only the first 6 ints sees the same behavior as before this field was
 * added (feature auto-disarms because displayId == 0 == main display).
 */
internal data class TaskWindowState(
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
internal fun findTaskState(packageName: String, atm: (Int) -> List<*>? = ::atmTasks): TaskWindowState? = runCatching {
    val tasks = atm(100) ?: return@runCatching null
    for (task in tasks) {
        if (task == null) continue
        if (taskPackage(task) != packageName) continue
        val taskId = taskIdOf(task) ?: continue
        val winConfig = taskWindowConfiguration(task) ?: return@runCatching null
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
        val displayId = runCatching { taskDisplayId(task, fallback = 0) }.getOrDefault(0)
        return@runCatching TaskWindowState(taskId, mode, bounds.left, bounds.top, bounds.right, bounds.bottom, displayId)
    }
    null
}.getOrNull()

/**
 * Reads [taskId]'s live windowing mode + display id via the same getTasks reflection as
 * [findTaskId] (TaskInfo.configuration.windowConfiguration.getWindowingMode() + TaskInfo.displayId).
 * Returns null when the task is not in the list (detached/dead) or reflection fails — callers
 * treat null as "state unknown" and fall back to call-outcome semantics.
 */
internal fun taskModeState(taskId: Int, atm: (Int) -> List<*>? = ::atmTasks): TaskModeState? = runCatching {
    val tasks = atm(100) ?: return@runCatching null
    for (task in tasks) {
        if (task == null) continue
        if (taskIdOf(task) != taskId) continue
        val winConfig = taskWindowConfiguration(task) ?: return@runCatching null
        val mode = winConfig.javaClass.getMethod("getWindowingMode").invoke(winConfig) as Int
        val display = taskDisplayId(task, fallback = -1)
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
internal fun taskActivityType(taskId: Int, atm: (Int) -> List<*>? = ::atmTasks): Int = runCatching {
    val tasks = atm(100) ?: return@runCatching -1
    for (task in tasks) {
        if (task == null) continue
        if (taskIdOf(task) != taskId) continue
        val winConfig = taskWindowConfiguration(task) ?: return@runCatching -1
        return@runCatching winConfig.javaClass.getMethod("getActivityType").invoke(winConfig) as Int
    }
    -1
}.getOrElse { e ->
    android.util.Log.w("bydmate_helper", "taskActivityType(task=$taskId) failed: ${e.message}")
    -1
}

/** Resolved direct task-move method; null once [atmMoveViaStack] took over. */
@Volatile private var atmMoveTaskMethod: java.lang.reflect.Method? = null
@Volatile private var atmGetAllStackInfosMethod: java.lang.reflect.Method? = null
@Volatile private var atmMoveStackToDisplayMethod: java.lang.reflect.Method? = null
@Volatile private var atmMoveViaStack: Boolean = false

/**
 * moveRootTaskToDisplay(int,int) preferred, fallback moveTaskToDisplay(int,int); on Android 10
 * ROMs that have neither, moves the task's whole stack via getAllStackInfos/moveStackToDisplay.
 */
internal fun moveTaskToDisplayReflect(taskId: Int, displayId: Int) {
    val iAtm = activityTaskManager()
    if (!atmMoveViaStack) {
        var m = atmMoveTaskMethod
        if (m == null) {
            m = iAtm.javaClass.methods.firstOrNull { it.name == "moveRootTaskToDisplay" && it.parameterTypes.size == 2 }
                ?: iAtm.javaClass.methods.firstOrNull { it.name == "moveTaskToDisplay" && it.parameterTypes.size == 2 }
            if (m != null) atmMoveTaskMethod = m
        }
        if (m != null) {
            m.invoke(iAtm, taskId, displayId)
            return
        }
        // Android 10 (DiLink 3.0 / 4.0): tasks move only together with their containing stack.
        atmGetAllStackInfosMethod = iAtm.javaClass.getMethod("getAllStackInfos")
        atmMoveStackToDisplayMethod = iAtm.javaClass.getMethod(
            "moveStackToDisplay", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        atmMoveViaStack = true
        android.util.Log.i("bydmate_helper", "atm compat: move via getAllStackInfos/moveStackToDisplay")
    }
    moveTaskToDisplayViaStack(iAtm, taskId, displayId)
}

/**
 * Android 10 stack path: find the ActivityManager.StackInfo holding [taskId] and move that stack.
 * Throws IllegalStateException when no stack lists the task (callers wrap this in runCatching).
 */
private fun moveTaskToDisplayViaStack(iAtm: Any, taskId: Int, displayId: Int) {
    val stacks = (atmGetAllStackInfosMethod?.invoke(iAtm) as? List<*>).orEmpty().mapNotNull { stack ->
        if (stack == null) return@mapNotNull null
        val taskIds = fieldByName(stack, "taskIds")?.let { f -> f.isAccessible = true; f.get(stack) as? IntArray }
            ?: return@mapNotNull null
        val stackId = fieldByName(stack, "stackId")?.let { f -> f.isAccessible = true; f.getInt(stack) }
            ?: return@mapNotNull null
        val stackDisplay = fieldByName(stack, "displayId")?.let { f -> f.isAccessible = true; f.getInt(stack) } ?: -1
        AtmStackEntry(stackId, stackDisplay, taskIds)
    }
    when (val decision = decideStackMove(stacks, taskId, displayId)) {
        is StackMoveDecision.Move -> atmMoveStackToDisplayMethod?.invoke(iAtm, decision.stackId, displayId)
        StackMoveDecision.AlreadyThere -> Unit  // a second move into the same display throws
        is StackMoveDecision.Refuse -> throw StackMoveRefused(decision.reason)
    }
}

/**
 * The Android 10 stack move was declined by [decideStackMove]: the task cannot be moved without
 * taking foreign windows along (or it is not in any stack). Distinct from the ROM's own throws,
 * which callers keep swallowing as best-effort.
 */
internal class StackMoveRefused(reason: String) : IllegalStateException(reason)

/** One ActivityManager.StackInfo reduced to the fields [decideStackMove] needs. */
internal data class AtmStackEntry(val stackId: Int, val displayId: Int, val taskIds: IntArray) {
    override fun equals(other: Any?): Boolean = this === other ||
        (other is AtmStackEntry && stackId == other.stackId && displayId == other.displayId &&
            taskIds.contentEquals(other.taskIds))

    override fun hashCode(): Int = (stackId * 31 + displayId) * 31 + taskIds.contentHashCode()
}

internal sealed class StackMoveDecision {
    data class Move(val stackId: Int) : StackMoveDecision()
    object AlreadyThere : StackMoveDecision()
    data class Refuse(val reason: String) : StackMoveDecision()
}

/**
 * Decides whether [taskId] can reach [displayId] through its containing stack.
 *
 * The Android 10 move verb takes a whole stack, so it is only safe when the stack holds that one
 * task — a freeform task (the cluster/split path flips the mode before moving) gets its own stack,
 * while every fullscreen task on the main display shares one: moving that would drag all of the
 * user's windows onto the target display.
 */
internal fun decideStackMove(stacks: List<AtmStackEntry>, taskId: Int, displayId: Int): StackMoveDecision {
    val stack = stacks.firstOrNull { it.taskIds.contains(taskId) }
        ?: return StackMoveDecision.Refuse("no stack for task=$taskId")
    if (stack.taskIds.size > 1) {
        return StackMoveDecision.Refuse(
            "stack ${stack.stackId} shared by ${stack.taskIds.size} tasks; refusing to move")
    }
    if (stack.displayId == displayId) return StackMoveDecision.AlreadyThere
    return StackMoveDecision.Move(stack.stackId)
}

internal fun setTaskWindowingModeReflect(taskId: Int, windowingMode: Int) {
    val iAtm = activityTaskManager()
    // toTop=false: focus is set separately (setFocusedRootTask), and toTop=true walks the
    // vendor moveToFront path on the ORIGINAL display before the reparent — the prime suspect
    // for the on-car throw during re-projection (2026-07-15).
    val result = iAtm.javaClass.getMethod("setTaskWindowingMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
        .invoke(iAtm, taskId, windowingMode, false)
    // The legacy API returns boolean; false = rejected without an exception (e.g. lock-task).
    if (result == false) throw IllegalStateException("setTaskWindowingMode(task=$taskId, mode=$windowingMode) returned false")
}

internal fun setTaskBoundsReflect(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
    val iAtm = activityTaskManager()
    val resizeTask = iAtm.javaClass.getMethod("resizeTask", Int::class.javaPrimitiveType, Rect::class.java, Int::class.javaPrimitiveType)
    val rect = if (left == 0 && top == 0 && right == 0 && bottom == 0) null else Rect(left, top, right, bottom)
    resizeTask.invoke(iAtm, taskId, rect, 1)
}

/** Resolved focus method: setFocusedRootTask(int) (Android 12) or setFocusedTask(int) (Android 10). */
@Volatile private var atmSetFocusedTaskMethod: java.lang.reflect.Method? = null

internal fun setFocusedTaskReflect(taskId: Int) {
    val iAtm = activityTaskManager()
    var m = atmSetFocusedTaskMethod
    if (m == null) {
        m = try {
            iAtm.javaClass.getMethod("setFocusedRootTask", Int::class.javaPrimitiveType)
        } catch (e: NoSuchMethodException) {
            android.util.Log.i("bydmate_helper", "atm compat: setFocusedTask(int)")
            iAtm.javaClass.getMethod("setFocusedTask", Int::class.javaPrimitiveType)
        }
        atmSetFocusedTaskMethod = m
    }
    m.invoke(iAtm, taskId)
}
