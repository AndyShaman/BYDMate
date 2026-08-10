package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RaiseFreeformTaskCoreTest {

    private val shellCmds = mutableListOf<String>()

    /**
     * Reconstructs the effective command by substituting positional args ("$1", "$2", …) so
     * that existing assertions on the full command string continue to work. Handles both the
     * quoted form ("\$1") and the bare form ($1) as a fallback.
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
        packageName: String = "com.example.navi",
        displayId: Int = 0,
        desiredActivityType: Int = ACTIVITY_TYPE_RECENTS,
        resolveComponent: (String) -> String? = { "com.example.navi/com.example.navi.MainActivity" },
        shell: (String, List<String>) -> String = { script, args -> shellCmds += effectiveCmd(script, args); "" },
        taskIdForPackage: (String) -> Int = { _ -> -1 },
        getActivityType: (Int) -> Int = { _ -> -1 },
        sleep: (Long) -> Unit = { shellCmds += "sleep:$it" },
        stateOf: (Int) -> TaskModeState? = { _ -> null },
    ) = raiseFreeformTaskCore(
        packageName, displayId, desiredActivityType, resolveComponent, shell, taskIdForPackage,
        getActivityType, sleep, stateOf,
    )

    // --- flag correctness ---

    @Test
    fun `raise command contains all three required flags plus component`() {
        val ok = run()
        assertTrue(ok)
        val cmd = shellCmds.single()
        assertTrue("must contain --windowingMode 5", cmd.contains("--windowingMode 5"))
        assertTrue("must contain --activityType 3", cmd.contains("--activityType 3"))
        assertTrue("must contain --display 0", cmd.contains("--display 0"))
        assertTrue("must contain -n <component>", cmd.contains("-n com.example.navi/"))
    }

    @Test
    fun `raise command carries the provided displayId`() {
        run(displayId = 2)
        val cmd = shellCmds.single()
        assertTrue("must carry --display 2", cmd.contains("--display 2"))
    }

    // --- validation gate ---

    @Test
    fun `invalid package name - no shell command issued`() {
        val ok = run(packageName = "bad;pkg")
        assertFalse(ok)
        assertTrue("no shell commands must be issued for invalid package", shellCmds.isEmpty())
    }

    // --- component resolution ---

    @Test
    fun `component unresolved - returns false without issuing shell command`() {
        val ok = run(resolveComponent = { null })
        assertFalse("must return false when component cannot be resolved", ok)
        assertTrue("no shell command must be issued when component is unresolved", shellCmds.isEmpty())
    }

    // --- shell output ---

    @Test
    fun `shell command returning Error - returns false`() {
        val ok = run(shell = { script, args -> shellCmds += effectiveCmd(script, args); "Error: Activity not started, intent is null" })
        assertFalse(ok)
    }

    @Test
    fun `shell command returning brought to the front - returns true`() {
        val ok = run(shell = { script, args -> shellCmds += effectiveCmd(script, args); "its current task has been brought to the front" })
        assertTrue(ok)
    }

    // --- mutation anti-vacuity: drop --activityType 3 flag ---

    @Test
    fun `raise command - activityType 3 flag is present (mutation guard)`() {
        run()
        val cmd = shellCmds.single()
        // If someone removes --activityType 3, recents-typed tasks use a different activity type
        // and the pane caption reappears. Assert the exact flag to catch such regressions.
        assertTrue("--activityType 3 must be present (Task N requirement)", cmd.contains("--activityType 3"))
    }

    // --- F1: merged Error output makes raiseFreeformTaskCore return false ---

    @Test
    fun `merged Error output returns false (F1 regression guard)`() {
        // AOSP am writes "Error:" to stderr; shExecMerged captures it. The merged output
        // must cause raiseFreeformTaskCore to return false, not silently succeed.
        val ok = run(
            shell = { script, args ->
                shellCmds += effectiveCmd(script, args)
                "Error: Activity not started, unable to resolve Intent { act=android.intent.action.MAIN }"
            },
        )
        assertFalse("merged 'Error:' output must cause raiseFreeformTaskCore to return false", ok)
    }

    // --- Hotfix 390-3: raising a live STANDARD task must recreate it, not raise it in place ---

    @Test
    fun `raise of a live STANDARD task removes the stack first`() {
        // `--activityType 3` types a task only at creation, so raising a live STANDARD task leaves
        // it STANDARD and AOSP-12 draws the freeform caption over the pane (on-car 390).
        //
        // Anti-vacuity: dropping the type check turns this into a single am-start and the
        // assertEquals below fails.
        val ok = run(taskIdForPackage = { 42 }, getActivityType = { ACTIVITY_TYPE_STANDARD })
        assertTrue(ok)
        assertEquals(
            listOf(
                "am stack remove 42",
                "sleep:500",
                "am start --windowingMode 5 --activityType 3 --display 0 -n com.example.navi/com.example.navi.MainActivity",
            ),
            shellCmds,
        )
    }

    @Test
    fun `raise of a RECENTS task stays a single am start`() {
        val ok = run(taskIdForPackage = { 42 }, getActivityType = { ACTIVITY_TYPE_RECENTS })
        assertTrue(ok)
        assertEquals(1, shellCmds.size)
        assertFalse("no stack remove for a RECENTS task", shellCmds.any { "am stack remove" in it })
    }

    @Test
    fun `raise of a dead app stays a single am start`() {
        // No live task (taskId == -1): nothing to remove, the am start cold-launches the app.
        val ok = run(taskIdForPackage = { -1 }, getActivityType = { ACTIVITY_TYPE_STANDARD })
        assertTrue(ok)
        assertEquals(1, shellCmds.size)
        assertFalse("no stack remove when there is no task", shellCmds.any { "am stack remove" in it })
    }

    // --- 392: panes are raised as STANDARD; the v3.9 RECENTS panes migrate on first raise ---

    @Test
    fun `raise of a live STANDARD task with STANDARD desired stays a single am start`() {
        val ok = run(
            desiredActivityType = ACTIVITY_TYPE_STANDARD,
            taskIdForPackage = { 42 },
            getActivityType = { ACTIVITY_TYPE_STANDARD },
        )
        assertTrue(ok)
        assertEquals(
            listOf("am start --windowingMode 5 --display 0 -n com.example.navi/com.example.navi.MainActivity"),
            shellCmds,
        )
    }

    @Test
    fun `raise of a live RECENTS task with STANDARD desired recreates it untyped`() {
        // Runtime migration of a v3.9 pane: the live task is RECENTS, the session now wants
        // STANDARD, so the task is removed and recreated without --activityType.
        val ok = run(
            desiredActivityType = ACTIVITY_TYPE_STANDARD,
            taskIdForPackage = { 42 },
            getActivityType = { ACTIVITY_TYPE_RECENTS },
        )
        assertTrue(ok)
        assertEquals(
            listOf(
                "am stack remove 42",
                "sleep:500",
                "am start --windowingMode 5 --display 0 -n com.example.navi/com.example.navi.MainActivity",
            ),
            shellCmds,
        )
    }

    @Test
    fun `a failing stack remove aborts the raise`() {
        val ok = run(
            taskIdForPackage = { 42 },
            getActivityType = { ACTIVITY_TYPE_STANDARD },
            shell = { script, args ->
                val cmd = effectiveCmd(script, args)
                shellCmds += cmd
                if ("am stack remove" in cmd) "Exception occurred while executing" else ""
            },
        )
        assertFalse("a failed remove must not be followed by a blind raise", ok)
        assertFalse("no am start after a failed remove", shellCmds.any { "am start" in it })
    }

    // --- the state reader reaches ensureTypedFreeform (non-destructive probe wiring) ---

    @Test
    fun `raise on a firmware without freeform fails without removing the live task`() {
        // Anti-vacuity: drop the stateOf forwarding and the probe never runs — the live task is
        // removed and this assertion fails.
        val ok = run(
            taskIdForPackage = { 42 },
            getActivityType = { ACTIVITY_TYPE_STANDARD },
            stateOf = { TaskModeState(WINDOWING_MODE_FULLSCREEN, 0) },
        )
        assertFalse("a coerced probe must fail the raise", ok)
        assertFalse("the live task must survive", shellCmds.any { "am stack remove" in it })
    }

    // --- P1: component with '$' arrives verbatim in args (anti-vacuity mutation check) ---

    @Test
    fun `component with dollar sign arrives verbatim in args not shell-expanded`() {
        val rawInvocations = mutableListOf<Pair<String, List<String>>>()
        // Component with '$' — would silently expand to empty under `sh -c` interpolation.
        val dollarComponent = "com.example.app/com.example.app.Shell\$HomeActivity"
        run(
            resolveComponent = { dollarComponent },
            shell = { script, args -> rawInvocations += script to args; "" },
        )
        val (script, args) = rawInvocations.single()
        assertTrue("script must contain quoted -n \"\$1\" placeholder", script.contains("-n \"\$1\""))
        assertFalse("script must NOT contain 'Shell' (component must be in args, not script)", script.contains("Shell"))
        assertTrue("component with '\$' must arrive verbatim as first arg", args[0] == dollarComponent)
    }
}
