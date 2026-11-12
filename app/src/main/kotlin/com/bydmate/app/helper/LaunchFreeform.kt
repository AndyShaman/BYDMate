package com.bydmate.app.helper

// Budget for the placement part of a TX_LAUNCH_FREEFORM, measured from before the launch retry
// loop. The client starts its FORCE_TIMEOUT_MS = 15s only once it holds the channel, so the whole
// TX has 15s of daemon time. Worst case inside it: resolveOrLaunchTask ~9.5s on a cold start, the
// pin loop, the grace poll (cut off here), and — only when placement fails — a fullscreen restore
// that runs outside the deadline and costs ~2.5-3s on the shell compat path. 11.5s + 3s = ~14.5s
// leaves ~0.5s for the binder return and for the unbudgeted tail of the last grace poll.
private const val GRACE_DEADLINE_MS = 11_500L

// One mid-relaunch grace poll: sleep, re-resolve, re-read. The deadline check reserves the sleep,
// so a sleep never straddles GRACE_DEADLINE_MS; the tail after it (re-resolve, at most one re-pin,
// re-read) is bounded but not budgeted — see GRACE_DEADLINE_MS for the slack it comes out of.
private const val GRACE_POLL_MS = 500L

/** Monotonic milliseconds: currentTimeMillis jumps when the head unit syncs its clock mid-launch. */
private fun monotonicMs(): Long = System.nanoTime() / 1_000_000

private val PACKAGE_NAME_REGEX = Regex("[A-Za-z0-9_.]+")

/**
 * Resolves [packageName]'s launcher component via `cmd package resolve-activity`. Returns null
 * when nothing resolves or the package name is not a valid Android package name.
 * Package validation (strict [A-Za-z0-9_.] regex) runs before any shell command; the package
 * is then passed as a positional arg ($1) so it is never re-parsed by the shell.
 */
internal fun resolveLaunchComponent(packageName: String): String? {
    if (!packageName.matches(PACKAGE_NAME_REGEX)) return null
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
 * issued for an invalid name.
 *
 * [windowingMode] null is the plain path and [activityType] is then unused, but the plain path
 * still splits on the display: [displayId] != 0 births the task on the target display and has NO
 * monkey fallback — monkey cannot target a display, so it would put the app on the main screen and
 * report a success the caller ([launchAndForce]) cannot distinguish from a real one. Only
 * [windowingMode] null with [displayId] == 0 is the original launchApp behavior, monkey included.
 */
internal fun launchAppCore(
    packageName: String,
    windowingMode: Int?,
    displayId: Int,
    activityType: Int,
    resolveComponent: (String) -> String?,
    shell: (String, List<String>) -> String,
): Boolean {
    if (!packageName.matches(PACKAGE_NAME_REGEX)) return false
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
    // A display-targeted launch has no monkey fallback: monkey has no --display flag, so it would
    // start the app on the main screen and return "success" for a target the caller never got.
    if (windowingMode == null && displayId != 0) return false
    val r3 = shell("monkey -p \"\$1\" -c android.intent.category.LAUNCHER 1", listOf(packageName))
    return !r3.contains("Error") && !r3.contains("error")
}

/**
 * Launches [packageName] via am/monkey strategies. Returns true if a launch command ran without an
 * obvious "Error". Mirrors CarControlImpl.launchApp (simplified to a boolean).
 */
internal fun launchApp(packageName: String): Boolean =
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
 * [setWindowingModeCompat]; [desiredActivityType] is the caller's choice (STANDARD for both
 * split panes and cluster projection).
 * Its [IllegalStateException] is mapped back to this function's Boolean contract.
 *
 * NOTE: the defaults of [taskIdForPackage] / [getActivityType] report "no task / unknown type",
 * which disables the STANDARD handling; the default [stateOf] disables the non-destructive freeform
 * probe. Production call sites must pass real implementations.
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
    stateOf: (Int) -> TaskModeState? = { _ -> null },
): Boolean {
    if (!packageName.matches(PACKAGE_NAME_REGEX)) return false
    val component = resolveComponent(packageName) ?: return false
    val taskId = taskIdForPackage(packageName)
    val activityType = if (taskId != -1) getActivityType(taskId) else -1
    return try {
        ensureTypedFreeform(taskId, activityType, desiredActivityType, displayId, component, shell, sleep, stateOf)
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
 * [desiredActivityType] is the caller's choice: split panes ask for STANDARD (392) and so does
 * cluster projection (#134). Migration note: v3.9 created its panes as RECENTS, so on the
 * first 392 split the live panes are re-typed through the remove branch below.
 *
 * Three cases, by [liveActivityType]:
 * - Already the desired type: light path — the single `am start` with the freeform flags re-uses
 *   the existing task and applies mode+display to it (validated on-car; the task id survives).
 * - The other half of the STANDARD/RECENTS pair: freeform probe (below) → `am stack remove` →
 *   settle → the same `am start`, which recreates the task with the desired type. The app PROCESS
 *   and its services survive the remove (only activities die), so media keeps playing and the
 *   navigator restores its own guidance session.
 * - No task ([taskId] == -1), unknown type (-1: reflection broken, task not found) or an exotic
 *   type (HOME/ASSISTANT/DREAM): the same single `am start`, which creates the task with the flags
 *   applied. Nothing is removed on a guess — killing activities blindly is worse than a mistyped
 *   task, and there may be no task at all.
 *
 * The remove is DESTRUCTIVE, so the retype branch asks first: on firmwares where freeform never
 * activates the recreate can never succeed, and until now every cluster-send press killed the
 * user's live navigation session before [launchFreeformCore] discovered that from the state
 * readback (field journals DiLink 5.1: "task recreated by setMode", guidance lost). [stateOf]
 * enables the probe — the same light `am start` WITHOUT `--activityType`, which applies mode+display
 * to the EXISTING task and keeps its id. If the mode does not become freeform, this throws an
 * [isFreeformUnsupported]-shaped message (→ UNAVAILABLE) with the task untouched. The post-probe
 * read is repeated up to twice more, each after a settle pause, so a slow-but-working firmware is
 * not misread as unsupported, and an unreadable state (null, including the default [stateOf]) takes
 * the legacy remove path — an unknown must not change fleet behavior.
 *
 * Throws [IllegalStateException] when the probe, the remove or the start fails. A swallowed
 * remove failure would let the relaunch deliver its intent to the still-alive task and report
 * success with the pane left un-retyped (codex pre-release audit 2026-07-16); "Exception occurred
 * while executing" is the am wording for an in-process throw, while a missing task prints nothing and
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
    stateOf: (Int) -> TaskModeState? = { _ -> null },
) {
    // Only the STANDARD <-> RECENTS pair is ever retyped. Exotic live types (HOME/ASSISTANT/DREAM)
    // and the unknown -1 are left untouched: nothing this daemon places into freeform is supposed
    // to carry them, and removing such a task would be a behavior change on the RECENTS direction
    // that v3.9 never made (fleet safety).
    val retypable = liveActivityType == ACTIVITY_TYPE_STANDARD || liveActivityType == ACTIVITY_TYPE_RECENTS
    if (taskId != -1 && retypable && liveActivityType != desiredActivityType) {
        // Non-destructive probe before the remove (see KDoc). A null pre-read means the state is
        // unreadable (or the caller passed no reader): no probe, legacy sequence unchanged.
        if (stateOf(taskId) != null) {
            // The light path — mode+display applied to the EXISTING task, no --activityType, so
            // the task keeps its id and the navigator keeps its guidance session.
            val probe = shell(
                "am start --windowingMode $WINDOWING_MODE_FREEFORM --display $displayId -n \"\$1\"",
                listOf(component),
            )
            if (probe.contains("Error") || probe.contains("Exception")) {
                throw IllegalStateException("am start freeform failed: ${probe.take(200)}")
            }
            // Settle tolerance: a slow-but-working firmware can still report the old mode on the
            // first reads — only a third stale read means freeform is really off. The loop stops
            // at the first read that already shows freeform.
            var after: TaskModeState? = null
            for (attempt in 0 until 3) {
                sleep(500L)
                after = stateOf(taskId)
                if (after?.windowingMode == WINDOWING_MODE_FREEFORM) break
            }
            if (after != null && after.windowingMode != WINDOWING_MODE_FREEFORM) {
                // Wording matches [isFreeformUnsupported] so launchFreeformCore reports UNAVAILABLE.
                throw IllegalStateException(
                    "freeform not supported: probe start coerced task=$taskId to mode=${after.windowingMode}"
                )
            }
        }
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
 * Finds [packageName]'s task id, launching the app and polling (16 x 500ms + settle pause)
 * when it is not running yet. Shared by launchAndForce and launchFreeform. Blocking.
 * [windowingMode] non-null (freeform path only) passes the mode and [activityType] to the launch
 * command so the task is born freeform with the right type; null (plain path, launchAndForce)
 * ignores [activityType] and, when [displayId] != 0, births the task on that display without the
 * monkey fallback (see [launchAppCore]).
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
 * (move -> bounds -> focus, x2). Returns true once redirection ran; apps that die during the move
 * (2GIS/Qt) are relaunched ONCE on the target display, and a failed relaunch returns false.
 * Mirrors CarControlImpl.launchAndForce.
 * Blocking (Thread.sleep) — runs on a binder threadpool thread; the app side uses a 15s timeout.
 */
internal fun launchAndForce(packageName: String, displayId: Int, width: Int, height: Int): Boolean {
    // Not-yet-running apps are born on the target display — 2GIS/Qt dies if moved there instead.
    val taskId = resolveOrLaunchTask(
        packageName, windowingMode = null, displayId = displayId, activityType = ACTIVITY_TYPE_STANDARD,
    )
    if (taskId <= 0) return false
    // #288: a task inside a native split pane may not follow the root-task move; remember where
    // it started so [splitFallbackCore] can check it.
    val before = taskModeState(taskId)
    // Each redirect op is best-effort, mirroring CarControlImpl (every reflective call there returns
    // a status string and swallows its own exception). resizeTask in particular throws "not allowed"
    // on a fullscreen task — that must NOT abort the move/focus or bubble up as a launchAndForce
    // failure, otherwise the caller tears down the VirtualDisplay Navi was just moved onto before it
    // can render. The VD size (mini=640 / full=1280) already sets the geometry, so a resize failure
    // is harmless.
    repeat(2) {
        try {
            moveTaskToDisplayReflect(taskId, displayId)
        } catch (e: StackMoveRefused) {
            // Android 10 stack move the daemon refused: pinning bounds/focus on a task that never
            // left the main display would report a placement that did not happen.
            android.util.Log.w("bydmate_helper", "launchAndForce: ${e.message}; aborting")
            return false
        } catch (t: Throwable) {
            // Best-effort as before: AOSP 12 throws "already on display" on the second pass.
        }
        runCatching { setTaskBoundsReflect(taskId, 0, 0, width, height) }
        runCatching { setFocusedTaskReflect(taskId) }
        Thread.sleep(200L)
    }
    // Settle before the liveness check: findTaskId matches topActivity OR baseActivity, so a task
    // whose process is dying can still be listed by getTasks with a stale baseActivity and hide the
    // death from the check below.
    Thread.sleep(500L)
    val placed = splitFallbackCore(
        taskId, displayId, before,
        stateOf = { ti -> taskModeState(ti) },
        resolveComponent = { resolveLaunchComponent(packageName) },
        shell = amShell,
        sleep = { Thread.sleep(it) },
    )
    if (!placed) return false
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

/**
 * True when a task that sat in a native split pane ([before]) is still alive off [displayId] after
 * the move ([after]). Fullscreen or unknown [before] is never a fallback case; a null [after]
 * (task gone) is left to the liveness check.
 */
internal fun needsSplitFallback(before: TaskModeState?, after: TaskModeState?, displayId: Int): Boolean =
    before != null && before.windowingMode in SPLIT_WINDOWING_MODES &&
        after != null && after.displayId != displayId

/**
 * #288: a navigator pulled back into the native 50/50 split lives in a split pane (mode 3/4), and
 * moveRootTaskToDisplay leaves it there without a word, so the cluster stayed dark while the
 * launch reported success. When [before] is a split pane and the task is still off [displayId],
 * the light pullback's `am start` (mode+display on the EXISTING task, task id kept) is aimed at
 * the target display and the task is read back with the same settle budget. Returns false only
 * when that fallback ran and the task did not arrive; any other start returns true untouched.
 */
@Suppress("LongParameterList") // every system touch is injected so tests pin the sequence
internal fun splitFallbackCore(
    taskId: Int,
    displayId: Int,
    before: TaskModeState?,
    stateOf: (Int) -> TaskModeState?,
    resolveComponent: () -> String?,
    shell: (String, List<String>) -> String,
    sleep: (Long) -> Unit,
): Boolean {
    if (before == null || before.windowingMode !in SPLIT_WINDOWING_MODES) return true
    val after = stateOf(taskId)
    if (!needsSplitFallback(before, after, displayId)) {
        android.util.Log.i("bydmate_helper", "launchAndForce split: task=$taskId from=$before moved state=$after")
        return true
    }
    val component = resolveComponent()
    if (component == null) {
        android.util.Log.w("bydmate_helper", "launchAndForce split: task=$taskId state=$after no launcher component")
        return false
    }
    android.util.Log.i(
        "bydmate_helper",
        "launchAndForce split: task=$taskId from=$before still state=$after → am start display=$displayId",
    )
    val out = runCatching {
        shell("am start --windowingMode $WINDOWING_MODE_FULLSCREEN --display $displayId -n \"\$1\"", listOf(component))
    }.getOrElse { "Exception ${it.message}" }
    if (out.contains("Error") || out.contains("Exception")) {
        android.util.Log.w("bydmate_helper", "launchAndForce split: task=$taskId am failed: ${out.take(120)}")
        return false
    }
    var last: TaskModeState? = null
    repeat(PULLBACK_READS) {
        sleep(PULLBACK_POLL_MS)
        last = stateOf(taskId)
        if (last?.displayId == displayId) {
            android.util.Log.i("bydmate_helper", "launchAndForce split: task=$taskId fallback ok state=$last")
            return true
        }
    }
    android.util.Log.w("bydmate_helper", "launchAndForce split: task=$taskId fallback failed state=$last")
    return false
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
// Native split panes (SPLIT_SCREEN_PRIMARY / SECONDARY).
internal val SPLIT_WINDOWING_MODES = setOf(3, 4)

/** Settle budget of the light fullscreen pull-back: [PULLBACK_READS] reads, one per pause. */
internal const val PULLBACK_POLL_MS = 300L
internal const val PULLBACK_READS = 4
// Who wants which type (392/#134): split panes are STANDARD, and so is cluster projection.
// RECENTS suppresses the AOSP-12 freeform DecorCaption (hasWindowDecorCaption() is true only for
// activityType == STANDARD && windowingMode == FREEFORM), which is why v3.9 created panes as
// RECENTS — but recents-typed tasks nest as leaves under one shared root task, so their input
// shields are not cropped to the pane bounds and only the top pane receives touch (on-car
// 2026-07-29, ActivityRecordInputSink fullscreen; memory project_split_screen_wave.md). Panes
// therefore take the caption and keep their own root task. The cluster kept RECENTS until #134:
// a live navigator task is STANDARD, so the retype forced a remove+relaunch that killed the
// navigator on Sea Lion 07 — the cluster is STANDARD now and wears the caption too.
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
    stateOf: (Int) -> TaskModeState? = { _ -> null },
) = setWindowingModeCompat(
    taskId, mode, 0, desiredActivityType, reflectSet, resolveComponent, shell, getActivityType, stateOf, sleep,
)

/**
 * Windowing-mode switch that survives ROMs without the binder API. AOSP S removed
 * IActivityTaskManager.setTaskWindowingMode and DiLink 5 did not restore it (on-car
 * NoSuchMethodException, 2026-07-15), so after that specific throw the shell ActivityStarter
 * path takes over: `am start --windowingMode 5 --display N -n <cmp>` applies mode+display to an
 * EXISTING task, keeping its task id (validated on-car). The way back to fullscreen is the same
 * command with mode 1 and display 0; the task is read back through [stateOf] and only an
 * unconfirmed pullback (state unreadable, wrong display or still not fullscreen) falls back to the
 * legacy remove+relaunch, which changes the task id and makes the navigator rebuild its guidance
 * session (#134). Any other [reflectSet] throw is rethrown untouched so [launchFreeformCore]'s
 * classification still sees it.
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
 * relaunch). [stateOf] is handed to [ensureTypedFreeform] as well: it enables the non-destructive
 * freeform probe that keeps a live task alive on firmwares without freeform.
 * NOTE: omitting [getActivityType] silently disables the reflectSet fast path for the freeform
 * direction, and omitting [stateOf] disables the probe. Every production call site must pass real
 * implementations.
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
    stateOf: (Int) -> TaskModeState? = { _ -> null },
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
        // Log.i, not Log.d: this branch decision is the only record of why a pane was re-typed
        // (or not), and release dumps carry nothing below INFO.
        android.util.Log.i("bydmate_helper", "setWindowingModeCompat task=$taskId mode=$windowingMode → $branch")
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
        ensureTypedFreeform(
            taskId, freeformType, desiredActivityType, freeformDisplayId, component, shell, sleep, stateOf,
        )
    } else {
        // Light path first (#134): the same `am start` trick the freeform direction uses —
        // mode+display applied to the EXISTING task, so the navigator keeps its id, its process
        // and its guidance session. Verified by reading the task back; only an unconfirmed
        // pullback falls through to the destructive remove+relaunch below.
        val light = runCatching {
            shell("am start --windowingMode $WINDOWING_MODE_FULLSCREEN --display 0 -n \"\$1\"", listOf(component))
        }.getOrNull()
        if (light != null && !light.contains("Error") && !light.contains("Exception")) {
            // Settle tolerance, same shape as the freeform probe: the reparent takes a moment and
            // the state can read null (task momentarily invisible mid-move) or stale. Only an
            // exhausted budget means the light path did not work. The am start is NOT repeated.
            var after: TaskModeState? = null
            repeat(PULLBACK_READS) {
                sleep(PULLBACK_POLL_MS)
                after = stateOf(taskId)
                if (after != null && after.displayId == 0 &&
                    after.windowingMode == WINDOWING_MODE_FULLSCREEN
                ) {
                    android.util.Log.i("bydmate_helper", "pullback light path ok task=$taskId")
                    return
                }
            }
            android.util.Log.i(
                "bydmate_helper",
                "pullback light path failed: task=$taskId state=$after → remove+relaunch",
            )
        } else {
            android.util.Log.i(
                "bydmate_helper",
                "pullback light path failed: task=$taskId am=${light?.take(120)} → remove+relaunch",
            )
        }
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
        // Explicit mode (#288): with no --windowingMode the platform resolves the fresh task's
        // mode from display 0, and a native split still active there takes it into its secondary
        // pane (wm=4), so the next projection starts from a split pane again.
        val out = shell("am start --windowingMode $WINDOWING_MODE_FULLSCREEN --display 0 -n \"\$1\"", listOf(component))
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
 * recreated with the right type, because the type is the caller's whole point — STANDARD for both
 * split panes and the cluster (#134). Unknown type (-1) skips the check, as does the default
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
 *
 * Time budget: the mid-relaunch grace poll runs only while its sleep still fits before
 * [deadlineMs] — the TX shares the client's 15s timeout with the launch retry loop, the shell
 * setMode spawns and the failure-path fullscreen restore, and an unconditional extra 3s here can
 * push the reply past it. What each poll does after the sleep (re-resolve, at most one re-pin,
 * re-read) is bounded but not budgeted; it comes out of the slack [GRACE_DEADLINE_MS] leaves. [now] must be monotonic (see [monotonicMs]); a wall clock that jumps
 * backwards mid-launch would stretch the poll past the client's budget. Default: no deadline
 * (tests and callers that do not care).
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
    deadlineMs: Long = Long.MAX_VALUE,
    now: () -> Long = { monotonicMs() },
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
    fun pin(t: Int) {
        if (runCatching { move(t, displayId) }.isSuccess) movedByCall = true
        runCatching { bounds(t, left, top, right, bottom) }
        runCatching { focus(t) }
    }
    repeat(2) {
        pin(liveTaskId)
        sleep(200L)
    }
    var pinnedTaskId = liveTaskId
    // Mid-relaunch grace: the compat setMode applies mode AND target display in one `am start`,
    // which RECREATES the task — it can still be absent from getTasks when the pin loop ends
    // (Sea Lion 07 journals, issue #134). Treating that null as failure restored fullscreen and
    // dropped the client to the VD fallback, killing a launch that was about to succeed. Re-resolve
    // by package and re-read, up to 3s — but only within [deadlineMs]: everything before this point
    // (launch retry loop, shell setMode spawns) already spent part of the client's 15s budget, and
    // the poll's sleep must fit before the deadline, so an exhausted budget skips the poll entirely
    // and falls straight through to the movedByCall trust path.
    // A task that surfaces here under a NEW id was never pinned: the shell relaunch restores mode,
    // type and display but not our bounds, and phase 2 addressed the id that is now gone — so pin
    // the new id once, before reading the state that decides the verdict.
    var final = runCatching { state(liveTaskId) }.getOrNull()
    var graceAttempt = 0
    while (final == null && graceAttempt < 6 && now() + GRACE_POLL_MS <= deadlineMs) {
        graceAttempt++
        sleep(GRACE_POLL_MS)
        refreshTaskId()
        if (liveTaskId != pinnedTaskId) {
            log("re-pinning task $liveTaskId found mid-grace (was $pinnedTaskId)", null)
            // The task phase 2 moved is gone: its success says nothing about this one, and the
            // trust path below must not answer OK for a task whose own move failed.
            movedByCall = false
            pin(liveTaskId)
            pinnedTaskId = liveTaskId
        }
        final = runCatching { state(liveTaskId) }.getOrNull()
    }
    var placed = when {
        final != null -> final.displayId == displayId && final.windowingMode == WINDOWING_MODE_FREEFORM
        else -> movedByCall
    }
    // #194 (DiLink 4.0, same finding as byd-dashcast): the cluster display of that firmware has no
    // FLAG_SUPPORTS_FREEFORM_WINDOW_MANAGEMENT, so the ROM silently drops freeform during the
    // reparent and the task arrives fullscreen ON the target display. DashCast switches the mode
    // AFTER the move for exactly this reason. Re-assert it once, re-pin, and let the re-read decide
    // — only while the sleep still fits the client's budget, like the grace poll above. A firmware
    // that keeps freeform across the move (the whole current fleet) never enters this branch.
    if (final != null && final.displayId == displayId &&
        final.windowingMode != WINDOWING_MODE_FREEFORM && now() + GRACE_POLL_MS <= deadlineMs
    ) {
        runCatching { setMode(liveTaskId, WINDOWING_MODE_FREEFORM) }
        refreshTaskId()
        runCatching { bounds(liveTaskId, left, top, right, bottom) }
        runCatching { focus(liveTaskId) }
        // Same poll as the mid-relaunch grace above, and for the same reason: the compat setMode
        // goes through `am start`, which can RECREATE the task — a single read 200 ms later hits
        // either nothing or the dying old task and reports a false FAILED (and a fullscreen
        // restore) for a placement that is about to be correct. A task surfacing under a new id
        // was never pinned, so it is pinned here before its state decides the verdict.
        var reassertAttempt = 0
        var reasserted: TaskModeState? = null
        while ((reasserted == null || reasserted.windowingMode != WINDOWING_MODE_FREEFORM) &&
            reassertAttempt < 6 && now() + GRACE_POLL_MS <= deadlineMs
        ) {
            reassertAttempt++
            sleep(GRACE_POLL_MS)
            refreshTaskId()
            if (liveTaskId != pinnedTaskId) {
                log("re-pinning task $liveTaskId found after the freeform re-assert (was $pinnedTaskId)", null)
                movedByCall = false
                pin(liveTaskId)
                pinnedTaskId = liveTaskId
            }
            reasserted = runCatching { state(liveTaskId) }.getOrNull()
        }
        final = reasserted
        placed = reasserted != null && reasserted.displayId == displayId &&
            reasserted.windowingMode == WINDOWING_MODE_FREEFORM
        log("freeform dropped by reparent to display $displayId; " +
            "re-asserted after move -> mode=${reasserted?.windowingMode} (polls=$reassertAttempt)", null)
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
 * (launch retry loop) — binder threadpool thread; the app side gives this TX 15s counted from the
 * moment it holds the channel, which [GRACE_DEADLINE_MS] splits between the placement and the
 * failure-path fullscreen restore.
 */
internal fun launchFreeform(
    packageName: String,
    displayId: Int,
    left: Int, top: Int, right: Int, bottom: Int,
    activityType: Int,
): Int {
    // The budget starts before the launch retry loop: it, not just the core, eats the client's 15s.
    val startMs = monotonicMs()
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
                // Same reader launchFreeformCore verifies with: it lets ensureTypedFreeform probe
                // freeform on the live task instead of removing the navigator to find out.
                stateOf = { ti -> taskModeState(ti) },
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
        deadlineMs = startMs + GRACE_DEADLINE_MS,
        now = { monotonicMs() },
    ) { Thread.sleep(it) }
}
