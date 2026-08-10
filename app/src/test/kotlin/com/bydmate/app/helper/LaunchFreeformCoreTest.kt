package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchFreeformCoreTest {

    private val ops = mutableListOf<String>()
    private val logs = mutableListOf<String>()
    private val logThrowables = mutableListOf<Throwable?>()

    private fun run(
        taskId: Int = 36,
        desiredActivityType: Int = ACTIVITY_TYPE_RECENTS,
        setMode: (Int, Int) -> Unit = { t, m -> ops += "mode:$t:$m" },
        move: (Int, Int) -> Unit = { t, d -> ops += "move:$t:$d" },
        bounds: (Int, Int, Int, Int, Int) -> Unit = { t, l, tp, r, b -> ops += "bounds:$t:$l,$tp,$r,$b" },
        focus: (Int) -> Unit = { ops += "focus:$it" },
        state: (Int) -> TaskModeState? = { null },
        getActivityType: (Int) -> Int = { -1 },
        log: (String, Throwable?) -> Unit = { m, t -> logs += m; logThrowables += t },
        resolveCurrentTaskId: () -> Int = { taskId },
        deadlineMs: Long = Long.MAX_VALUE,
        now: () -> Long = { 0L },
        sleep: (Long) -> Unit = { ops += "sleep:$it" },
    ): Int = launchFreeformCore(
        taskId, 4, 0, 38, 1280, 441, desiredActivityType, setMode, move, bounds, focus, state,
        getActivityType, log, resolveCurrentTaskId, deadlineMs, now, sleep,
    )

    @Test
    fun `happy path switches mode first then pins twice`() {
        assertEquals(FreeformResultCodes.OK, run())
        assertEquals(
            listOf(
                "mode:36:5",
                "move:36:4", "bounds:36:0,38,1280,441", "focus:36", "sleep:200",
                "move:36:4", "bounds:36:0,38,1280,441", "focus:36", "sleep:200",
                // Unreadable state (default lambda) exhausts the mid-relaunch grace poll before
                // falling back to the call outcome.
                "sleep:500", "sleep:500", "sleep:500", "sleep:500", "sleep:500", "sleep:500",
            ),
            ops,
        )
    }

    @Test
    fun `rejected freeform switch maps to UNAVAILABLE and runs no pin ops`() {
        val result = run(setMode = { _, _ -> throw IllegalStateException("freeform disabled") })
        assertEquals(FreeformResultCodes.UNAVAILABLE, result)
        // Both bounded attempts (with the settle pause between them), then the pull back to the
        // main display (the shell compat setMode can strand the task on the target display) —
        // but no pin ops.
        assertEquals(listOf("sleep:250", "move:36:0"), ops)
    }

    @Test
    fun `mode switch throw without freeform marker maps to FAILED`() {
        // Fix round 2026-07-15: an unrelated per-task failure (e.g. racing a relaunch) must NOT
        // latch the "reboot pending" hint — only a genuine freeform-unsupported rejection may.
        val result = run(setMode = { _, _ -> throw IllegalArgumentException("Unable to find task id=36") })
        assertEquals(FreeformResultCodes.FAILED, result)
        assertEquals(listOf("sleep:250", "move:36:0"), ops)
    }

    @Test
    fun `mode switch retry succeeds after a transient throw`() {
        // Codex round 2026-07-15: without a bounded retry, a single transient vendor throw
        // dumps the launch into the VD fallback — the very symptom being fixed.
        var calls = 0
        val result = run(setMode = { t, m ->
            calls++
            if (calls == 1) throw IllegalStateException("transient vendor failure")
            ops += "mode:$t:$m"
        })
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals("sleep:250", ops.first()) // settle pause between the attempts
        assertTrue(ops.contains("mode:36:5"))
        assertTrue(ops.contains("move:36:4"))
    }

    @Test
    fun `silent no-op mode switch maps to UNAVAILABLE`() {
        // AOSP does not throw when freeform is off — Task.setWindowingMode silently coerces the
        // request away; the live task state is the probe.
        val result = run(
            setMode = { _, _ -> },
            state = { TaskModeState(WINDOWING_MODE_FULLSCREEN, 0) },
        )
        assertEquals(FreeformResultCodes.UNAVAILABLE, result)
        // Never reaches the pin ops; the only move is the pull back to the main display.
        assertEquals(listOf("move:36:0"), ops.filter { it.startsWith("move:") })
    }

    @Test
    fun `already freeform task of the desired type skips the mode switch`() {
        var disp = 0
        val result = run(
            desiredActivityType = ACTIVITY_TYPE_RECENTS,
            move = { t, d -> ops += "move:$t:$d"; disp = d },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, disp) },
            getActivityType = { ACTIVITY_TYPE_RECENTS },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue(ops.none { it.startsWith("mode:") })
        assertTrue(ops.contains("move:36:4"))
    }

    @Test
    fun `already freeform STANDARD task with RECENTS desired is retyped`() {
        // Cluster direction: a pane of the active split (STANDARD since 392) sent to the cluster
        // must still end up RECENTS. Skipping setMode on windowingMode alone left it STANDARD.
        var disp = 0
        var type = ACTIVITY_TYPE_STANDARD
        val result = run(
            desiredActivityType = ACTIVITY_TYPE_RECENTS,
            setMode = { t, m -> ops += "mode:$t:$m"; type = ACTIVITY_TYPE_RECENTS },
            move = { t, d -> ops += "move:$t:$d"; disp = d },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, disp) },
            getActivityType = { type },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue("the retype must go through setMode", ops.contains("mode:36:5"))
        assertTrue(ops.contains("move:36:4"))
    }

    @Test
    fun `already freeform RECENTS task with STANDARD desired is retyped`() {
        // Split direction: a v3.9 leftover pane is RECENTS+freeform; without the retype it keeps
        // the shared-root input shield and only the top pane receives touch.
        var disp = 0
        var type = ACTIVITY_TYPE_RECENTS
        val result = run(
            desiredActivityType = ACTIVITY_TYPE_STANDARD,
            setMode = { t, m -> ops += "mode:$t:$m"; type = ACTIVITY_TYPE_STANDARD },
            move = { t, d -> ops += "move:$t:$d"; disp = d },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, disp) },
            getActivityType = { type },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue("the retype must go through setMode", ops.contains("mode:36:5"))
        assertTrue(ops.contains("move:36:4"))
    }

    @Test
    fun `already freeform task with unknown type is left alone`() {
        // -1 means reflection is broken or the task is gone: no blind retype (conservative).
        var disp = 0
        val result = run(
            desiredActivityType = ACTIVITY_TYPE_STANDARD,
            move = { t, d -> ops += "move:$t:$d"; disp = d },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, disp) },
            getActivityType = { -1 },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue(ops.none { it.startsWith("mode:") })
        assertTrue(ops.contains("move:36:4"))
    }

    @Test
    fun `mode switch throw is forgiven when live state reports freeform`() {
        var mode = WINDOWING_MODE_FULLSCREEN
        var disp = 0
        val result = run(
            setMode = { _, m ->
                mode = m // the switch landed before the exception surfaced
                throw IllegalStateException("relaunch race")
            },
            move = { t, d -> ops += "move:$t:$d"; disp = d },
            state = { TaskModeState(mode, disp) },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue(ops.contains("move:36:4"))
    }

    @Test
    fun `missing task maps to FAILED without ops`() {
        assertEquals(FreeformResultCodes.FAILED, run(taskId = -1))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `degenerate bounds map to FAILED without ops`() {
        val r = launchFreeformCore(
            36, 4, 100, 100, 100, 200, ACTIVITY_TYPE_STANDARD,
            { _, _ -> ops += "mode" }, { _, _ -> }, { _, _, _, _, _ -> }, { },
        ) { }
        assertEquals(FreeformResultCodes.FAILED, r)
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `bounds and focus are best-effort - throwing does not abort`() {
        // move succeeds; bounds/focus throw on every attempt → still OK (best-effort for those ops)
        val result = run(
            bounds = { _, _, _, _, _ -> throw IllegalArgumentException("bounds fail") },
            focus = { _ -> throw IllegalArgumentException("focus fail") },
        )
        assertEquals(FreeformResultCodes.OK, result)
    }

    @Test
    fun `both move attempts throw - FAILED and fullscreen restored`() {
        // Major 3 fix: task would be stranded as a tiny freeform window on its original display.
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            move = { _, _ -> throw IllegalArgumentException("move rejected") },
        )
        assertEquals(FreeformResultCodes.FAILED, result)
        // setMode must have been called twice: once for FREEFORM (5), once to restore FULLSCREEN (1).
        assertEquals(listOf("36:5", "36:1"), modeOps)
    }

    @Test
    fun `first move throws but second succeeds - OK without restoring fullscreen`() {
        var moveCount = 0
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            move = { t, d ->
                moveCount++
                if (moveCount == 1) throw IllegalArgumentException("first attempt failed")
                ops += "move:$t:$d"
            },
        )
        assertEquals(FreeformResultCodes.OK, result)
        // Only the initial freeform switch; no fullscreen restore.
        assertEquals(listOf("36:5"), modeOps)
    }

    @Test
    fun `stranded freeform task already on target display - throwing moves still OK`() {
        // On-car case 2026-07-15: quickboot kill strands Navigator freeform on the projection
        // display; the retry must converge instead of restoring fullscreen and reporting FAILED.
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            move = { _, _ -> throw IllegalArgumentException("already on TaskDisplayArea") },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, 4) },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue(modeOps.isEmpty())
    }

    @Test
    fun `non-throwing move that never reaches the target display - FAILED and fullscreen restored`() {
        // Codex round 2026-07-15: moveRootTaskToDisplay is void and may silently no-op; a
        // non-throwing move alone must not count as success when live state says otherwise.
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, 0) }, // never leaves display 0
        )
        assertEquals(FreeformResultCodes.FAILED, result)
        // Already freeform → no switch; only the fullscreen restore.
        assertEquals(listOf("36:1"), modeOps)
    }

    @Test
    fun `mode coerced back to fullscreen after the reparent - FAILED`() {
        // The reparent itself can trigger a vendor relaunch that coerces the mode back;
        // the final verification must require freeform AND the target display together.
        val states = ArrayDeque(
            listOf(
                TaskModeState(WINDOWING_MODE_FULLSCREEN, 0), // pre
                TaskModeState(WINDOWING_MODE_FREEFORM, 0),   // after setMode
            ),
        )
        val result = run(
            state = { states.removeFirstOrNull() ?: TaskModeState(WINDOWING_MODE_FULLSCREEN, 4) },
        )
        assertEquals(FreeformResultCodes.FAILED, result)
    }

    @Test
    fun `mode switch throwable reaches the log callback`() {
        val result = run(
            setMode = { _, _ -> throw RuntimeException("reflect wrapper", IllegalStateException("freeform not supported")) },
        )
        assertEquals(FreeformResultCodes.UNAVAILABLE, result) // marker found in the cause chain
        assertTrue(logThrowables.filterNotNull().any { it.cause?.message == "freeform not supported" })
        assertTrue(logs.any { "setTaskWindowingMode" in it })
    }

    @Test
    fun `isFreeformUnsupported matches known wordings across the cause chain`() {
        assertTrue(isFreeformUnsupported(IllegalStateException("freeform is not supported")))
        assertTrue(isFreeformUnsupported(IllegalStateException("Device does not support FREEFORM windowing")))
        assertTrue(isFreeformUnsupported(RuntimeException("outer", IllegalStateException("freeform windows disabled"))))
        assertFalse(isFreeformUnsupported(IllegalArgumentException("Unable to find task id=16")))
        assertFalse(isFreeformUnsupported(IllegalStateException(null as String?)))
    }

    // --- Fix round 390-3b: setMode may RECREATE the task, giving it a new id ---

    @Test
    fun `a task recreated by setMode is retargeted for the whole placement`() {
        // Fleet scenario: a fullscreen STANDARD navigator is sent to the cluster. The compat path
        // cannot re-type it in place, so it removes the stack and relaunches — new task id 37.
        // Phase 2 and the final confirmation must address 37; hitting the dead 36 would either
        // throw (VD fallback over an already-created direct task) or silently no-op (a false OK
        // with no bounds and no focus).
        var recreated = false
        val result = run(
            setMode = { t, m -> ops += "mode:$t:$m"; recreated = true },
            state = { t ->
                ops += "state:$t"
                if (recreated) TaskModeState(WINDOWING_MODE_FREEFORM, 4)
                else TaskModeState(WINDOWING_MODE_FULLSCREEN, 0)
            },
            resolveCurrentTaskId = { if (recreated) 37 else 36 },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals(
            listOf(
                "state:36",
                "mode:36:5",
                "state:37",
                "move:37:4", "bounds:37:0,38,1280,441", "focus:37", "sleep:200",
                "move:37:4", "bounds:37:0,38,1280,441", "focus:37", "sleep:200",
                "state:37",
            ),
            ops,
        )
        assertTrue(
            "the retarget must be logged",
            logs.any { it.contains("task recreated by setMode: 36 -> 37") },
        )
    }

    @Test
    fun `a task that keeps its id runs byte-for-byte as before`() {
        // RECENTS task: the compat path flips it in place, the resolver keeps answering 36.
        var flipped = false
        val result = run(
            setMode = { t, m -> ops += "mode:$t:$m"; flipped = true },
            state = { t ->
                ops += "state:$t"
                if (flipped) TaskModeState(WINDOWING_MODE_FREEFORM, 4)
                else TaskModeState(WINDOWING_MODE_FULLSCREEN, 0)
            },
            resolveCurrentTaskId = { 36 },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals(
            listOf(
                "state:36",
                "mode:36:5",
                "state:36",
                "move:36:4", "bounds:36:0,38,1280,441", "focus:36", "sleep:200",
                "move:36:4", "bounds:36:0,38,1280,441", "focus:36", "sleep:200",
                "state:36",
            ),
            ops,
        )
        assertFalse("no retarget must be logged", logs.any { it.contains("task recreated") })
    }

    // --- Issue #134: the relaunched task may reach getTasks only after the pin loop ---

    @Test
    fun `a task visible only mid grace poll is accepted without restoring fullscreen`() {
        // Sea Lion 07 journals: the compat setMode recreates the task via `am start`, so the first
        // state read after the moves sees nothing. Restoring fullscreen there killed a launch that
        // was about to succeed (the user saw the navigator "crash" into the VD fallback).
        var reads = 0
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            move = { _, _ -> throw IllegalArgumentException("Unable to find task id=36") },
            state = { reads++; if (reads <= 4) null else TaskModeState(WINDOWING_MODE_FREEFORM, 4) },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals("only the freeform switch; no fullscreen restore", listOf("36:5"), modeOps)
        assertEquals(2, ops.count { it == "sleep:500" })
    }

    @Test
    fun `state unreadable through the whole grace window still maps to FAILED`() {
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            move = { _, _ -> throw IllegalArgumentException("move rejected") },
            state = { null },
        )
        assertEquals(FreeformResultCodes.FAILED, result)
        assertEquals(listOf("36:5", "36:1"), modeOps)
        assertEquals(6, ops.count { it == "sleep:500" })
    }

    @Test
    fun `a task recreated mid grace poll is retargeted, pinned and confirms the placement`() {
        // The relaunch can surface under a NEW id only during the grace window: the old id stays
        // unreadable forever, so without re-resolving inside the loop the throwing move leaves
        // nothing to confirm and the launch is restored to fullscreen for no reason. The new task
        // also carries none of our geometry — the shell relaunch restores mode/type/display but not
        // the bounds — so it must be pinned before the verdict is read.
        var resolves = 0
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            // Records the attempt AND throws: movedByCall stays false, so the OK verdict can only
            // come from the state read on the retargeted id.
            move = { t, d -> ops += "move:$t:$d"; throw IllegalArgumentException("Unable to find task id=$t") },
            state = { t -> if (t == 37) TaskModeState(WINDOWING_MODE_FREEFORM, 4) else null },
            resolveCurrentTaskId = { resolves++; if (resolves <= 2) 36 else 37 },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals("only the freeform switch; no fullscreen restore", listOf("36:5"), modeOps)
        assertEquals(2, ops.count { it == "sleep:500" })
        assertTrue(
            "the retarget must be logged",
            logs.any { it.contains("task recreated by setMode: 36 -> 37") },
        )
        assertTrue("the new id must be moved", ops.contains("move:37:4"))
        assertTrue("the new id must get the bounds", ops.contains("bounds:37:0,38,1280,441"))
        assertTrue("the new id must be focused", ops.contains("focus:37"))
    }

    @Test
    fun `a task pinned mid grace is pinned once, not on every poll`() {
        // The re-pin is keyed on the id changing, not on the poll: a task that stays unreadable
        // under its new id must not be moved, resized and focused six times over.
        var resolves = 0
        val result = run(
            resolveCurrentTaskId = { resolves++; if (resolves <= 1) 36 else 37 },
            state = { null },
        )
        assertEquals(FreeformResultCodes.OK, result) // unreadable state falls back to the move outcome
        assertEquals(6, ops.count { it == "sleep:500" })
        assertEquals(1, ops.count { it == "move:37:4" })
        assertEquals(1, ops.count { it == "bounds:37:0,38,1280,441" })
        assertEquals(1, ops.count { it == "focus:37" })
        assertEquals(1, logs.count { it.contains("re-pinning task 37") })
    }

    @Test
    fun `a task surfacing mid grace on the wrong display is FAILED despite a successful move`() {
        // Live state beats the call outcome: moveRootTaskToDisplay returned without throwing, but
        // the task the grace poll finally sees never left display 0.
        var reads = 0
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            state = { reads++; if (reads <= 4) null else TaskModeState(WINDOWING_MODE_FREEFORM, 0) },
        )
        assertEquals(FreeformResultCodes.FAILED, result)
        assertEquals(listOf("36:5", "36:1"), modeOps)
        assertEquals(2, ops.count { it == "sleep:500" })
    }

    @Test
    fun `a move that succeeded on the vanished id does not vouch for the task found mid grace`() {
        // The old task was moved fine, then disappeared and resurfaced under a new id whose own
        // move throws. Trusting the dead task's success would report OK for a task with no
        // confirmed display, bounds or focus — and skip the VD fallback that would place it.
        var resolves = 0
        val modeOps = mutableListOf<String>()
        val result = run(
            setMode = { t, m -> modeOps += "$t:$m" },
            move = { t, d ->
                ops += "move:$t:$d"
                if (t != 36) throw IllegalArgumentException("Unable to find task id=$t")
            },
            state = { null },
            resolveCurrentTaskId = { resolves++; if (resolves <= 2) 36 else 37 },
        )
        assertEquals(FreeformResultCodes.FAILED, result)
        assertEquals(
            "fullscreen must be restored on the task that is actually live",
            listOf("36:5", "37:1"),
            modeOps,
        )
        assertTrue("the new id must have been re-pinned", ops.contains("move:37:4"))
        assertEquals(6, ops.count { it == "sleep:500" })
    }

    // --- Codex round 2026-08-08: the grace poll must fit the client's 15s TX budget ---

    @Test
    fun `an exhausted budget skips the grace poll and trusts the move`() {
        val result = run(deadlineMs = 1_000L, now = { 5_000L })
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals(0, ops.count { it == "sleep:500" })
    }

    @Test
    fun `the grace poll stops as soon as the budget runs out`() {
        var clock = 0L
        val result = run(
            deadlineMs = 1_500L,
            now = { clock },
            sleep = { ops += "sleep:$it"; clock += it },
        )
        assertEquals(FreeformResultCodes.OK, result)
        // 400ms burned by the two pin pauses; a poll costs 500ms and must END before the deadline,
        // so the checks at 400 and 900 pass and the one at 1400 (would land on 1900) does not.
        assertEquals(2, ops.count { it == "sleep:500" })
    }

    @Test
    fun `a poll that would overrun the deadline is not started`() {
        // The sleep itself is what spends the budget: starting a 500ms poll 400ms before the
        // deadline pushes the reply past the client's timeout even though now() is still inside it.
        val result = run(deadlineMs = 400L, now = { 0L })
        assertEquals(FreeformResultCodes.OK, result)
        assertEquals(0, ops.count { it == "sleep:500" })
    }

    // --- DiLink 5.1: a firmware without freeform must not cost the user their navigation ---

    @Test
    fun `freeform off - the whole production chain reports UNAVAILABLE without removing the task`() {
        // End-to-end through the real setWindowingModeCompat + ensureTypedFreeform wiring
        // (the shell compat path: reflectSet is absent on this ROM). The live navigator task is
        // STANDARD, the cluster wants RECENTS — legacy code removed the task and only then learned
        // from the readback that freeform never activates, killing the guidance session for nothing.
        val shellCmds = mutableListOf<String>()
        val coerced = { _: Int -> TaskModeState(WINDOWING_MODE_FULLSCREEN, 0) }
        val result = run(
            setMode = { t, m ->
                setWindowingModeCompat(
                    t, m, 4, ACTIVITY_TYPE_RECENTS,
                    reflectSet = { _, _ -> throw NoSuchMethodException("setTaskWindowingMode") },
                    resolveComponent = { "ru.yandex.yandexnavi/.core.NavigatorActivity" },
                    shell = { script, args ->
                        shellCmds += args.foldIndexed(script) { i, s, a -> s.replace("\"\$${i + 1}\"", a) }
                        ""
                    },
                    getActivityType = { ACTIVITY_TYPE_STANDARD },
                    stateOf = coerced,
                    sleep = { },
                )
            },
            state = coerced,
        )
        assertEquals(FreeformResultCodes.UNAVAILABLE, result)
        assertFalse("the navigator task must survive", shellCmds.any { "am stack remove" in it })
        assertTrue(
            "the probe start must have run",
            shellCmds.any { it == "am start --windowingMode 5 --display 4 -n ru.yandex.yandexnavi/.core.NavigatorActivity" },
        )
    }

    @Test
    fun `an unresolvable id mid-relaunch keeps the last known task`() {
        // The resolver can momentarily see no task while it relaunches; -1 must not become the
        // target (taskId <= 0 would make every downstream call meaningless).
        val result = run(
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, 4) },
            resolveCurrentTaskId = { -1 },
        )
        assertEquals(FreeformResultCodes.OK, result)
        assertTrue("phase 2 must still address 36", ops.any { it == "move:36:4" })
    }
}
