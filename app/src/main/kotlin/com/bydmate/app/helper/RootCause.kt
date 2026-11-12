package com.bydmate.app.helper

/** Reflection hides the real error inside InvocationTargetException — report the root. */
internal fun describeRootCause(t: Throwable): String {
    val root = generateSequence(t) { it.cause }.last()
    return "${root.javaClass.simpleName}: ${root.message ?: "(no message)"}"
}
