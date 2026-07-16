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
        setMode: (Int, Int) -> Unit = { t, m -> ops += "mode:$t:$m" },
        move: (Int, Int) -> Unit = { t, d -> ops += "move:$t:$d" },
        bounds: (Int, Int, Int, Int, Int) -> Unit = { t, l, tp, r, b -> ops += "bounds:$t:$l,$tp,$r,$b" },
        focus: (Int) -> Unit = { ops += "focus:$it" },
        state: (Int) -> TaskModeState? = { null },
        log: (String, Throwable?) -> Unit = { m, t -> logs += m; logThrowables += t },
    ): Int = launchFreeformCore(taskId, 4, 0, 38, 1280, 441, setMode, move, bounds, focus, state, log) { ops += "sleep:$it" }

    @Test
    fun `happy path switches mode first then pins twice`() {
        assertEquals(FreeformResultCodes.OK, run())
        assertEquals(
            listOf(
                "mode:36:5",
                "move:36:4", "bounds:36:0,38,1280,441", "focus:36", "sleep:200",
                "move:36:4", "bounds:36:0,38,1280,441", "focus:36", "sleep:200",
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
    fun `already freeform task skips the mode switch`() {
        var disp = 0
        val result = run(
            move = { t, d -> ops += "move:$t:$d"; disp = d },
            state = { TaskModeState(WINDOWING_MODE_FREEFORM, disp) },
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
            36, 4, 100, 100, 100, 200,
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
}
