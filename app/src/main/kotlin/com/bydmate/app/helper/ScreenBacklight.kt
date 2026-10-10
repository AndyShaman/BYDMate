package com.bydmate.app.helper

import java.lang.reflect.InvocationTargetException

/** How many times the status is read after the call, [SCREEN_BACKLIGHT_READ_GAP_MS] apart. */
internal const val SCREEN_BACKLIGHT_READS = 5
private const val SCREEN_BACKLIGHT_READ_GAP_MS = 100L

private const val SCREEN_BACKLIGHT_TAG = "bydmate_helper"

/** TX_SET_SCREEN_BACKLIGHT reply: the outcome, the last status read (-1 = none) and the log detail. */
internal class ScreenBacklightResult(val outcome: Int, val status: Int, val detail: String)

/**
 * The verdict of one backlight command. Only a status read that shows the wanted state confirms
 * it: a call that merely returned says nothing on a car nobody has tried.
 */
internal fun screenBacklightOutcome(methodFound: Boolean, threw: Boolean, lastRead: Int?, want: Int): Int = when {
    !methodFound -> HelperBinderProtocol.SCREEN_BACKLIGHT_ABSENT
    threw -> HelperBinderProtocol.SCREEN_BACKLIGHT_THREW
    lastRead == want -> HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED
    else -> HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED
}

/**
 * TX_SET_SCREEN_BACKLIGHT body: turnBacklightOff / turnBacklightOn(long) on the PowerManager
 * [powerManager] hands back, `TurnBacklight*` when the lowercase name is missing (DiLink 3.0 / 4.0),
 * looked up by reflection because the methods are BYD's hidden additions. The plain variants only:
 * the `WithLock` ones can leave the screen stuck dark. Then getPowerScreenStatus() (0 = off,
 * 1 = on) is read up to [SCREEN_BACKLIGHT_READS] times, [pause] apart, until it shows the wanted
 * state. Nothing is propagated: every command ends in one log line and a reply.
 */
internal fun setScreenBacklight(
    on: Boolean,
    uptimeMs: Long,
    pause: () -> Unit = { Thread.sleep(SCREEN_BACKLIGHT_READ_GAP_MS) },
    powerManager: () -> Any,
): ScreenBacklightResult {
    val want = if (on) 1 else 0
    val result = runCatching(powerManager).fold(
        onSuccess = { pm -> callBacklight(pm, on, want, uptimeMs, pause) },
        onFailure = { e -> ScreenBacklightResult(HelperBinderProtocol.SCREEN_BACKLIGHT_THREW, -1, "no PowerManager ${describe(e)}") },
    )
    android.util.Log.i(
        SCREEN_BACKLIGHT_TAG,
        "screen backlight want=${if (on) "on" else "off"} ${result.detail} -> ${outcomeName(result.outcome)}",
    )
    return result
}

private fun callBacklight(pm: Any, on: Boolean, want: Int, uptimeMs: Long, pause: () -> Unit): ScreenBacklightResult {
    val names = if (on) listOf("turnBacklightOn", "TurnBacklightOn") else listOf("turnBacklightOff", "TurnBacklightOff")
    val method = names.firstNotNullOfOrNull { name ->
        runCatching { pm.javaClass.getMethod(name, Long::class.javaPrimitiveType) }.getOrNull()
    } ?: return ScreenBacklightResult(
        screenBacklightOutcome(methodFound = false, threw = false, lastRead = null, want = want), -1,
        "no ${names.joinToString("/")}",
    )
    runCatching { method.invoke(pm, uptimeMs) }.onFailure { e ->
        // The framework's message is its own verdict (the missing permission), no user data.
        return ScreenBacklightResult(
            screenBacklightOutcome(methodFound = true, threw = true, lastRead = null, want = want), -1,
            "${method.name} ${describe(e)}",
        )
    }
    val reads = mutableListOf<Int>()
    var unreadable: String? = null
    while (reads.size < SCREEN_BACKLIGHT_READS && unreadable == null && reads.lastOrNull() != want) {
        if (reads.isNotEmpty()) pause()
        runCatching { pm.javaClass.getMethod("getPowerScreenStatus").invoke(pm) as Int }
            .fold(onSuccess = { reads += it }, onFailure = { unreadable = unwrap(it).javaClass.simpleName })
    }
    val shown = (reads.map(Int::toString) + listOfNotNull(unreadable)).joinToString(",")
    return ScreenBacklightResult(
        screenBacklightOutcome(methodFound = true, threw = false, lastRead = reads.lastOrNull(), want = want),
        reads.lastOrNull() ?: -1, "${method.name} reads=$shown",
    )
}

private fun unwrap(e: Throwable): Throwable = (e as? InvocationTargetException)?.cause ?: e

private fun describe(e: Throwable): String = unwrap(e).let { "${it.javaClass.simpleName}: ${it.message}" }

private fun outcomeName(outcome: Int): String = when (outcome) {
    HelperBinderProtocol.SCREEN_BACKLIGHT_CONFIRMED -> "confirmed"
    HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED -> "not_followed"
    HelperBinderProtocol.SCREEN_BACKLIGHT_ABSENT -> "absent"
    else -> "threw"
}
