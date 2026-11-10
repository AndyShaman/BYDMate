package com.bydmate.app.helper

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
