package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SetWindowingModeCompatTest {

    private val ops = mutableListOf<String>()

    /**
     * Reconstructs the effective command by substituting positional args ("$1", "$2", …) so
     * that existing string-based assertions on the full command continue to work unchanged.
     * Handles both the quoted form ("\$1") and the bare form ($1) as a fallback.
     */
    private fun effectiveCmd(script: String, args: List<String>): String {
        var e = script
        args.forEachIndexed { i, a ->
            e = e.replace("\"\$${i + 1}\"", a)
            e = e.replace("\$${i + 1}", a)
        }
        return e
    }

    private fun run(
        mode: Int = WINDOWING_MODE_FREEFORM,
        reflectSet: (Int, Int) -> Unit = { t, m -> ops += "reflect:$t:$m" },
        resolveComponent: () -> String? = { ops += "resolve"; "ru.yandex.yandexnavi/.core.NavigatorActivity" },
        shell: (String, List<String>) -> String = { script, args ->
            ops += "shell:${effectiveCmd(script, args)}"
            ""
        },
        getActivityType: (Int) -> Int = { _ -> -1 },
    ) = setWindowingModeCompat(36, mode, 4, reflectSet, resolveComponent, shell, getActivityType) { ops += "sleep:$it" }

    @Test
    fun `reflect path works - shell never touched`() {
        // activityType=RECENTS: skipReflect=false → reflectSet is used; shell never called.
        run(getActivityType = { _ -> ACTIVITY_TYPE_RECENTS })
        assertEquals(listOf("reflect:36:5"), ops)
    }

    @Test
    fun `missing binder API - freeform goes through am start with mode and display`() {
        // Root cause 2026-07-15: IActivityTaskManager.setTaskWindowingMode was removed in AOSP S
        // and DiLink 5 did not restore it — the shell ActivityStarter path is the only one that
        // applies mode+display to an existing task (validated on-car).
        // activityType 3 (RECENTS) suppresses the freeform DecorCaption (validated on-car 2026-07-28).
        run(reflectSet = { _, _ -> throw NoSuchMethodException("setTaskWindowingMode") })
        assertEquals(
            listOf(
                "resolve",
                "shell:am start --windowingMode 5 --activityType 3 --display 4 -n ru.yandex.yandexnavi/.core.NavigatorActivity",
            ),
            ops,
        )
    }

    @Test
    fun `missing binder API - fullscreen removes the stack and relaunches on the main display`() {
        // Freeform sticks to a task on this ROM (`am start --windowingMode 1` is ignored);
        // the only way back is removing the stack and relaunching (validated on-car).
        // The plain relaunch must NOT carry --activityType so the task is restored as type=standard.
        run(mode = WINDOWING_MODE_FULLSCREEN, reflectSet = { _, _ -> throw NoSuchMethodException("x") })
        assertEquals(
            listOf(
                "resolve",
                "shell:am stack remove 36",
                "sleep:500",
                "shell:am start --display 0 -n ru.yandex.yandexnavi/.core.NavigatorActivity",
            ),
            ops,
        )
        assertFalse(
            "fullscreen relaunch must not carry --activityType (would resurrect type=recents)",
            ops.any { "--activityType" in it },
        )
    }

    @Test
    fun `shell error output throws`() {
        assertThrows(IllegalStateException::class.java) {
            run(
                reflectSet = { _, _ -> throw NoSuchMethodException("x") },
                shell = { script, args ->
                    ops += "shell:${effectiveCmd(script, args)}"
                    "Error: Activity not started"
                },
            )
        }
    }

    @Test
    fun `failed stack remove throws before the relaunch`() {
        // Codex pre-release audit 2026-07-16: a swallowed remove failure let the relaunch
        // deliver its intent to the STILL-ALIVE freeform task without printing "Error" — the
        // TX reported success and the client could clear the recovery marker with the task
        // stranded on the cluster. A failed remove must fail the whole switch.
        assertThrows(IllegalStateException::class.java) {
            run(
                mode = WINDOWING_MODE_FULLSCREEN,
                reflectSet = { _, _ -> throw NoSuchMethodException("x") },
                shell = { script, args ->
                    val cmd = effectiveCmd(script, args)
                    ops += "shell:$cmd"
                    if (cmd.startsWith("am stack remove")) "Exception occurred while executing 'stack'" else ""
                },
            )
        }
        assertEquals(listOf("resolve", "shell:am stack remove 36"), ops)
    }

    @Test
    fun `unresolvable component throws before any shell command`() {
        assertThrows(IllegalStateException::class.java) {
            run(
                reflectSet = { _, _ -> throw NoSuchMethodException("x") },
                resolveComponent = { null },
            )
        }
        assertTrue(ops.none { it.startsWith("shell:") })
    }

    @Test
    fun `other reflect throwables are rethrown untouched`() {
        // launchFreeformCore's honest classification (UNAVAILABLE vs FAILED) reads the original
        // throwable — the compat wrapper must not swallow or re-wrap it.
        // activityType=RECENTS: skipReflect=false → reflectSet is attempted and rethrows.
        val boom = IllegalStateException("freeform disabled")
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(reflectSet = { _, _ -> throw boom }, getActivityType = { _ -> ACTIVITY_TYPE_RECENTS })
        }
        assertSame(boom, thrown)
        assertTrue(ops.isEmpty())
    }

    // --- P1 (freeform path): component with '$' arrives verbatim in args ---

    @Test
    fun `freeform component with dollar sign arrives verbatim in args not shell-expanded`() {
        val rawInvocations = mutableListOf<Pair<String, List<String>>>()
        val dollarComponent = "ru.yandex.yandexnavi/.core.Shell\$HomeActivity"
        run(
            reflectSet = { _, _ -> throw NoSuchMethodException("x") },
            resolveComponent = { dollarComponent },
            shell = { script, args -> rawInvocations += script to args; "" },
        )
        assertTrue("at least one shell invocation expected", rawInvocations.isNotEmpty())
        // The freeform am-start shell call must use quoted positional "$1".
        val (script, args) = rawInvocations.first { it.first.contains("am start") }
        assertTrue("script must contain quoted -n \"\$1\" placeholder", script.contains("-n \"\$1\""))
        assertFalse("script must NOT embed 'Shell' literally", script.contains("Shell"))
        assertEquals("component with '\$' must arrive verbatim as first arg", dollarComponent, args[0])
    }

    // --- Q2 / F-2+F-6: activityType gate before reflectSet ---

    @Test fun `activityType standard + freeform skips reflectSet and relaunches with activityType 3`() {
        // Anti-vacuity proof: without the skipReflect gate, the default reflectSet stub would
        // record "reflect:36:5" and this test would fail. The gate is what prevents caption buttons.
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> ACTIVITY_TYPE_STANDARD },
        )
        assertFalse(
            "reflectSet must not be attempted when task activityType is STANDARD (would keep caption)",
            ops.any { it.startsWith("reflect:") },
        )
        assertTrue(
            "the recreating relaunch must carry --activityType 3",
            ops.any { "--activityType 3" in it },
        )
    }

    // --- Hotfix 390-3: a live STANDARD task must be REMOVED, not flipped in place ---

    @Test fun `freeform flip of a live STANDARD task removes the stack before relaunching`() {
        // `--activityType 3` types a task only at creation: flipping a live STANDARD task in place
        // left it STANDARD and AOSP-12 drew the caption (X / maximize) over the pane (on-car 390).
        // The task must be removed so the relaunch recreates it as RECENTS.
        //
        // Anti-vacuity: removing the `am stack remove` step drops the first two ops and this
        // assertEquals fails.
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> ACTIVITY_TYPE_STANDARD },
        )
        assertEquals(
            listOf(
                "resolve",
                "shell:am stack remove 36",
                "sleep:500",
                "shell:am start --windowingMode 5 --activityType 3 --display 4 -n ru.yandex.yandexnavi/.core.NavigatorActivity",
            ),
            ops,
        )
    }

    @Test fun `freeform flip of a RECENTS task never removes the stack`() {
        // Already the right type — the reflectSet fast path stands, nothing is killed.
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> ACTIVITY_TYPE_RECENTS },
        )
        assertFalse("no stack remove for a RECENTS task", ops.any { "am stack remove" in it })
        assertTrue("reflectSet fast path", ops.any { it.startsWith("reflect:") })
    }

    @Test fun `freeform flip with unknown type keeps the historic plain relaunch`() {
        // -1 means reflection is broken or there is no such task: there may be nothing to remove,
        // and killing activities on a guess is worse than a caption.
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> -1 },
        )
        assertFalse("no stack remove on an unknown type", ops.any { "am stack remove" in it })
        assertEquals(
            listOf(
                "resolve",
                "shell:am start --windowingMode 5 --activityType 3 --display 4 -n ru.yandex.yandexnavi/.core.NavigatorActivity",
            ),
            ops,
        )
    }

    @Test fun `a failing stack remove aborts the freeform flip instead of relaunching blind`() {
        // Same reasoning as the fullscreen branch: a swallowed remove failure would let the
        // relaunch hit the still-alive task and report success with the caption still drawn.
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(
                mode = WINDOWING_MODE_FREEFORM,
                getActivityType = { _ -> ACTIVITY_TYPE_STANDARD },
                shell = { script, args ->
                    val cmd = effectiveCmd(script, args)
                    ops += "shell:$cmd"
                    if ("am stack remove" in cmd) "Exception occurred while executing" else ""
                },
            )
        }
        assertTrue(thrown.message!!.contains("am stack remove failed"))
        assertFalse("no relaunch after a failed remove", ops.any { "am start" in it })
    }

    @Test fun `activityType recents + freeform uses reflectSet fast path`() {
        // Task already has activityType=3 — reflectSet keeps it and is faster (no relaunch).
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> ACTIVITY_TYPE_RECENTS },
        )
        assertTrue(
            "reflectSet must be chosen when activityType is already RECENTS",
            ops.any { it.startsWith("reflect:") },
        )
        assertFalse(
            "no shell relaunch when reflectSet succeeds",
            ops.any { it.startsWith("shell:") },
        )
    }

    @Test fun `activityType unknown (-1) + freeform takes shell path conservatively`() {
        // When activityType cannot be read (getActivityType returns -1), the conservative path
        // (shell relaunch with --activityType 3) is taken — same behaviour as default.
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> -1 },
        )
        assertFalse("reflectSet must not be attempted when type is unknown", ops.any { it.startsWith("reflect:") })
        assertTrue("shell relaunch must carry --activityType 3", ops.any { "--activityType 3" in it })
    }

    @Test fun `activityType check is not applied to the fullscreen direction`() {
        // The invariant is enforced at pane ENTRY (freeform), not at exit (fullscreen).
        // Fullscreen restore must stay as STANDARD — do NOT add --activityType to the exit relaunch.
        run(
            mode = WINDOWING_MODE_FULLSCREEN,
            reflectSet = { _, _ -> throw NoSuchMethodException("x") },
            getActivityType = { _ -> ACTIVITY_TYPE_STANDARD },
        )
        assertFalse(
            "fullscreen path must not carry --activityType regardless of current type",
            ops.any { "--activityType" in it },
        )
    }

    @Test fun `gentle flip (freeform) with RECENTS task uses reflectSet not shell`() {
        // SplitSessionManager.forceStopIfNeeded sends WINDOWING_FREEFORM through
        // TX_SET_TASK_WINDOWING_MODE (gentle flip — preserves app process for music).
        // The TX handler must pass getActivityType so a RECENTS task takes the reflectSet
        // fast path (binder call only, no relaunch). Anti-vacuity: without the wiring
        // (default { _ -> -1 }), skipReflect=true even for a RECENTS task → shell would be
        // called instead, and this assertion on reflectSet would fail.
        run(
            mode = WINDOWING_MODE_FREEFORM,
            getActivityType = { _ -> ACTIVITY_TYPE_RECENTS },
        )
        assertTrue(
            "RECENTS task gentle flip must use reflectSet (no process disruption)",
            ops.any { it.startsWith("reflect:") },
        )
        assertFalse(
            "no shell relaunch for RECENTS task gentle flip",
            ops.any { it.startsWith("shell:") },
        )
    }

    // --- F5: fullscreen pull-back component with '$' arrives verbatim in args ---

    @Test
    fun `fullscreen pull-back component with dollar sign arrives verbatim in args (F5)`() {
        // The fullscreen branch (am start --display 0 -n "$1") carries the component and is
        // equally $-prone. Verify it routes through the positional argv mechanism.
        val rawInvocations = mutableListOf<Pair<String, List<String>>>()
        val dollarComponent = "ru.yandex.yandexnavi/.core.Shell\$HomeActivity"
        run(
            mode = WINDOWING_MODE_FULLSCREEN,
            reflectSet = { _, _ -> throw NoSuchMethodException("x") },
            resolveComponent = { dollarComponent },
            shell = { script, args ->
                rawInvocations += script to args
                ""  // stack remove and relaunch both succeed
            },
        )
        // Find the am-start --display 0 call (fullscreen pull-back).
        val fullscreenInv = rawInvocations.firstOrNull { it.first.contains("--display 0") }
        assertTrue("fullscreen am-start invocation expected", fullscreenInv != null)
        val (script, args) = fullscreenInv!!
        assertTrue("script must contain quoted -n \"\$1\" placeholder", script.contains("-n \"\$1\""))
        assertFalse("script must NOT embed 'Shell' literally", script.contains("Shell"))
        assertEquals("component with '\$' must arrive verbatim as first arg", dollarComponent, args[0])
    }

    // ── Q2-4: handleSetWindowingModeTx extracted handler (anti-vacuity for TX wiring) ──

    /**
     * Verifies that [handleSetWindowingModeTx] routes to reflectSet when the task's activityType
     * is RECENTS, and to shell when it is unknown (-1). This proves [getActivityType] is
     * load-bearing within the extracted handler body.
     *
     * Anti-vacuity: replacing `activityTypeFor = { ACTIVITY_TYPE_RECENTS }` with `{ -1 }` in the
     * test's call to [handleSetWindowingModeTx] makes the shell branch execute, failing the
     * `assertFalse(shellCalled)` assertion. Equivalently, removing the `getActivityType` forwarding
     * from inside [handleSetWindowingModeTx] to [setWindowingModeCompat] would produce the same failure.
     */
    @Test fun `handleSetWindowingModeTx RECENTS task in FREEFORM direction uses reflectSet not shell`() {
        var reflectCalled = false
        var shellCalled = false
        handleSetWindowingModeTx(
            taskId = 10,
            mode = WINDOWING_MODE_FREEFORM,
            reflectSet = { _, _ -> reflectCalled = true },
            resolveComponent = { "com.example/.Activity" },
            shell = { _, _ -> shellCalled = true; "" },
            getActivityType = { ACTIVITY_TYPE_RECENTS },
            sleep = {},
        )
        assertTrue("RECENTS task must take reflectSet path", reflectCalled)
        assertFalse("shell must not be called for RECENTS gentle flip", shellCalled)
    }

    @Test fun `handleSetWindowingModeTx unknown activityType in FREEFORM direction uses shell`() {
        // Anti-vacuity: with activityType=-1, skipReflect=true → shell is taken.
        // This is the mutation that would fire if getActivityType were not forwarded.
        var reflectCalled = false
        var shellCalled = false
        handleSetWindowingModeTx(
            taskId = 10,
            mode = WINDOWING_MODE_FREEFORM,
            reflectSet = { _, _ -> reflectCalled = true },
            resolveComponent = { "com.example/.Activity" },
            shell = { _, _ -> shellCalled = true; "" },
            getActivityType = { -1 },
            sleep = {},
        )
        assertFalse("reflectSet must NOT be called for unknown activityType", reflectCalled)
        assertTrue("shell must be called when activityType is unknown", shellCalled)
    }
}
