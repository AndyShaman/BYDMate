package com.bydmate.app.helper

import android.content.ComponentName
import android.graphics.Rect
import android.os.Parcel

// --- Native 3:7 split surface (BYD extension of IActivityTaskManager, platformized firmware) ---

/** Area ids reported by TX_SPLIT37_AREA_INFO, in reply order: narrow pane, wide pane, fullscreen. */
internal val SPLIT37_AREA_IDS = intArrayOf(
    HelperBinderProtocol.SPLIT37_AREA_NARROW,
    HelperBinderProtocol.SPLIT37_AREA_WIDE,
    HelperBinderProtocol.SPLIT37_AREA_FULL,
)

/**
 * Looks up a BYD split method by [name] and [paramCount] on the IActivityTaskManager proxy.
 * Returns null on firmware without the platformized split surface (pre-OTA image), so the caller
 * answers SPLIT37_UNSUPPORTED instead of turning a missing method into a generic failure.
 */
internal fun splitMethod(iAtm: Any, name: String, paramCount: Int): java.lang.reflect.Method? =
    iAtm.javaClass.methods.firstOrNull { it.name == name && it.parameterTypes.size == paramCount }

/** getScreenAreaInfoForMulti(): 3 = split on screen, 4 = fullscreen; -1 when the method is absent. */
internal fun screenAreaMode(iAtm: Any): Int =
    splitMethod(iAtm, "getScreenAreaInfoForMulti", 0)?.invoke(iAtm) as? Int ?: -1

/**
 * One getAllRootTaskInfos() snapshot, taken once per TX_SPLIT37_AREA_INFO request and shared by
 * all three areas. Null when the method is absent (firmware without the platformized split).
 */
internal fun rootTaskInfosSnapshot(iAtm: Any): List<Any>? =
    (splitMethod(iAtm, "getAllRootTaskInfos", 0)?.invoke(iAtm) as? List<*>)?.filterNotNull()

/**
 * Bounds of the root task [rootTaskId], taken from the [infos] entry whose taskId matches.
 * Null when the root is not listed or the bounds field is missing. Both fields go through
 * [fieldByName], which walks the superclass chain: on API 32 RootTaskInfo declares `bounds`
 * itself but inherits `taskId` from TaskInfo.
 */
internal fun rootTaskBounds(infos: List<Any>, rootTaskId: Int): Rect? {
    for (info in infos) {
        val idField = fieldByName(info, "taskId") ?: continue
        idField.isAccessible = true
        if (idField.getInt(info) != rootTaskId) continue
        val boundsField = fieldByName(info, "bounds") ?: return null
        boundsField.isAccessible = true
        return boundsField.get(info) as? Rect
    }
    return null
}

/**
 * Writes a complete TX_SPLIT37_AREA_INFO reply for the failure paths: [status], an unreadable
 * area mode and three absent roots. The client parses a fixed-length reply, so a short one on
 * the failure path would read as a transport error ("daemon outdated") rather than a status.
 */
internal fun writeAreaInfoFailure(reply: Parcel?, status: Int) {
    reply?.writeInt(status)
    reply?.writeInt(-1)
    SPLIT37_AREA_IDS.forEach { _ ->
        reply?.writeInt(-1)              // rootTaskId = absent
        repeat(4) { reply?.writeInt(0) } // bounds
    }
}

/**
 * One TX_SPLIT37_ROOT_STATE reading. Unknown values: "" for a component, -1 for a visibility
 * flag, null [children] when the root is not listed (or lists no children array).
 */
internal data class Split37RootStateReading(
    val rootTop: String,
    val rootVisible: Int,
    val children: List<Pair<Int, String>>?,
    val taskVisible: Int,
    val taskTop: String,
)

/**
 * Testable core of TX_SPLIT37_ROOT_STATE: picks the RootTaskInfo of [rootTaskId] out of
 * [rootInfos] and the RunningTaskInfo of [taskId] out of [tasks], and reads the few fields the
 * journal line needs. Every field is read on its own, so a ROM missing one still reports the rest.
 */
internal fun split37RootStateReading(
    rootInfos: List<Any>,
    rootTaskId: Int,
    tasks: List<*>?,
    taskId: Int,
): Split37RootStateReading {
    val root = rootInfos.firstOrNull { readField(it, "taskId") == rootTaskId }
    val task = tasks?.filterNotNull()?.firstOrNull {
        (readField(it, "taskId") ?: readField(it, "id")) == taskId
    }
    return Split37RootStateReading(
        rootTop = root?.let { componentText(readField(it, "topActivity")) } ?: "",
        rootVisible = root?.let { flagInt(readField(it, "visible")) } ?: -1,
        children = root?.let { rootChildren(it) },
        taskVisible = task?.let { flagInt(readField(it, "isVisible")) } ?: -1,
        taskTop = task?.let { componentText(readField(it, "topActivity")) } ?: "",
    )
}

/** Writes [r] after the status, in the TX_SPLIT37_ROOT_STATE reply layout. */
internal fun writeSplit37RootState(reply: Parcel, r: Split37RootStateReading) {
    reply.writeString(r.rootTop)
    reply.writeInt(r.rootVisible)
    val children = r.children
    reply.writeInt(children?.size ?: -1)
    children?.forEach { (id, name) -> reply.writeInt(id); reply.writeString(name) }
    reply.writeInt(r.taskVisible)
    reply.writeString(r.taskTop)
}

private fun readField(target: Any, name: String): Any? = runCatching {
    fieldByName(target, name)?.let { f -> f.isAccessible = true; f.get(target) }
}.getOrNull()

private fun flagInt(value: Any?): Int = when (value as? Boolean) {
    true -> 1
    false -> 0
    null -> -1
}

private fun componentText(value: Any?): String =
    (value as? android.content.ComponentName)?.flattenToShortString() ?: ""

/**
 * RootTaskInfo.childTaskIds paired with childTaskNames (flattened components, shortened here so
 * the journal line stays readable); null when the ids array is unreadable.
 */
private fun rootChildren(root: Any): List<Pair<Int, String>>? {
    val ids = readField(root, "childTaskIds") as? IntArray ?: return null
    val names = readField(root, "childTaskNames") as? Array<*>
    return ids.mapIndexed { i, id ->
        val raw = names?.getOrNull(i) as? String
        val short = raw?.let { android.content.ComponentName.unflattenFromString(it)?.flattenToShortString() }
        id to (short ?: raw ?: "")
    }
}

/**
 * Testable core of TX_SPLIT37_MOVE_TASK: reparents [taskId] into the native split root
 * [rootTaskId] with `am stack move-task` (= activity_task tx 54 moveTaskToRootTask; shell uid
 * holds MANAGE_ACTIVITY_TASKS), then resizes it to the pane rect when that rect is non-empty.
 * [toTop] is the onTop argument of the move: false parks the task at the bottom of the root, which
 * is how the engine bounces a task out of a root it already lives in before raising it again.
 * The move is the load-bearing step — a resize failure throws out to the caller's runCatching.
 * `am stack move-task` prints `Error: ...` or an exception trace when it refuses and stays silent
 * on success, so the outcome is read with the daemon-wide substring convention (see launchAppCore).
 * Returns a HelperBinderProtocol.SPLIT37_* status.
 */
internal fun split37MoveTaskCore(
    taskId: Int,
    rootTaskId: Int,
    left: Int,
    top: Int,
    right: Int,
    bottom: Int,
    toTop: Boolean,
    shell: (String, List<String>) -> String,
    resize: (Int, Int, Int, Int, Int) -> Unit,
): Int {
    // Ids are internal Ints but still go through positional args — nothing is interpolated.
    val out = shell(
        "am stack move-task \"\$1\" \"\$2\" $toTop",
        listOf(taskId.toString(), rootTaskId.toString()),
    )
    if (out.contains("Error") || out.contains("Exception")) {
        android.util.Log.w(
            "bydmate_helper",
            "split37 move-task task=$taskId root=$rootTaskId failed: ${out.take(200)}",
        )
        return HelperBinderProtocol.SPLIT37_FAILED
    }
    if (right > left && bottom > top) resize(taskId, left, top, right, bottom)
    return HelperBinderProtocol.SPLIT37_OK
}
