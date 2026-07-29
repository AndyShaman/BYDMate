package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchAppCoreTest {

    private val shellCmds = mutableListOf<String>()

    /**
     * Reconstructs the effective command by substituting positional args ("$1", "$2", …) so
     * that existing assertions on the full command string continue to work. Handles both the
     * quoted form ("\$1") used in production scripts and the bare form ($1) as a fallback.
     */
    private fun effectiveCmd(script: String, args: List<String>): String {
        var e = script
        args.forEachIndexed { i, a ->
            e = e.replace("\"\$${i + 1}\"", a)  // quoted form: "$1" → arg
            e = e.replace("\$${i + 1}", a)        // bare form: $1 → arg (fallback)
        }
        return e
    }

    private fun run(
        packageName: String = "anddea.youtube",
        windowingMode: Int? = WINDOWING_MODE_FREEFORM,
        displayId: Int = 0,
        resolveComponent: (String) -> String? = { "anddea.youtube/com.google.android.youtube.app.honeycomb.Shell\$HomeActivity" },
        shell: (String, List<String>) -> String = { script, args -> shellCmds += effectiveCmd(script, args); "" },
    ) = launchAppCore(packageName, windowingMode, displayId, resolveComponent, shell)

    // --- freeform path ---

    @Test
    fun `freeform path component resolves - first command carries windowing mode and display flags`() {
        val ok = run()
        assertTrue(ok)
        val first = shellCmds.first()
        assertTrue("must contain --windowingMode 5", first.contains("--windowingMode 5"))
        assertTrue("must contain --activityType 3", first.contains("--activityType 3"))
        assertTrue("must contain --display 0", first.contains("--display 0"))
        assertTrue("must contain -n <component>", first.contains("-n anddea.youtube/"))
    }

    @Test
    fun `freeform path component absent - MAIN fallback carries flags`() {
        val shell: (String, List<String>) -> String = { script, args -> shellCmds += effectiveCmd(script, args); "" }
        val ok = run(resolveComponent = { null }, shell = shell)
        assertTrue(ok)
        val mainCmd = shellCmds.first { "android.intent.action.MAIN" in it }
        assertTrue("MAIN fallback must carry --windowingMode 5", mainCmd.contains("--windowingMode 5"))
        assertTrue("MAIN fallback must carry --activityType 3", mainCmd.contains("--activityType 3"))
        assertTrue("MAIN fallback must carry --display 0", mainCmd.contains("--display 0"))
    }

    @Test
    fun `freeform path all am strategies fail - monkey fallback carries no windowing mode`() {
        val shell: (String, List<String>) -> String = { script, args ->
            val cmd = effectiveCmd(script, args)
            shellCmds += cmd
            if (cmd.startsWith("monkey")) "" else "Error: not started"
        }
        val ok = run(shell = shell)
        assertTrue(ok)
        val monkeyCmd = shellCmds.first { it.startsWith("monkey") }
        assertFalse("monkey must not carry --windowingMode", monkeyCmd.contains("--windowingMode"))
        assertFalse("monkey must not carry --activityType", monkeyCmd.contains("--activityType"))
    }

    // --- plain path ---

    @Test
    fun `plain path windowingMode null - no windowing mode flag in any command`() {
        val ok = run(windowingMode = null)
        assertTrue(ok)
        assertTrue(
            "plain path must not produce --windowingMode anywhere",
            shellCmds.none { "--windowingMode" in it },
        )
        assertTrue(
            "plain path must not produce --activityType anywhere",
            shellCmds.none { "--activityType" in it },
        )
        assertTrue("first command must be plain am start -n", shellCmds.first().startsWith("am start -n "))
    }

    // --- validation gate ---

    @Test
    fun `invalid package name - no shell command issued`() {
        val ok = run(packageName = "bad;pkg")
        assertFalse(ok)
        assertTrue("no shell commands must be issued for invalid package", shellCmds.isEmpty())
    }

    // --- F1: merged error output drives the fallback chain ---

    @Test
    fun `merged Error output on -n path drives MAIN fallback (F1 regression guard)`() {
        // am/monkey write "Error:" to STDERR; shExecMerged captures it. Verify the function
        // correctly enters the MAIN fallback when the -n launch returns an error string.
        val invocations = mutableListOf<String>()
        val shell: (String, List<String>) -> String = { script, args ->
            val cmd = effectiveCmd(script, args)
            invocations += cmd
            // -n am-start fails; MAIN and monkey succeed.
            if (cmd.contains("-n ")) "Error: Activity not started, unable to resolve Intent" else ""
        }
        val ok = run(shell = shell)
        assertTrue("must succeed via MAIN or monkey fallback", ok)
        assertTrue("MAIN fallback must have been issued", invocations.any { "android.intent.action.MAIN" in it })
    }

    @Test
    fun `merged Error output on all am paths falls through to monkey (F1 regression guard)`() {
        val invocations = mutableListOf<String>()
        val shell: (String, List<String>) -> String = { script, args ->
            val cmd = effectiveCmd(script, args)
            invocations += cmd
            if (cmd.startsWith("monkey")) "" else "Error: Activity not started"
        }
        val ok = run(shell = shell)
        assertTrue("must succeed via monkey", ok)
        assertTrue("monkey must have been called after am failures",
            invocations.any { it.startsWith("monkey") })
    }

    // --- P1: component with '$' reaches am start intact (anti-vacuity mutation check) ---

    @Test
    fun `component with dollar sign arrives verbatim in args not shell-expanded`() {
        val rawInvocations = mutableListOf<Pair<String, List<String>>>()
        // YouTube-like component: '$HomeActivity' would expand to empty under `sh -c` interpolation.
        val dollarComponent = "anddea.youtube/com.google.android.youtube.app.honeycomb.Shell\$HomeActivity"
        val ok = run(
            resolveComponent = { dollarComponent },
            shell = { script, args -> rawInvocations += script to args; "" },
        )
        assertTrue(ok)
        // Script must use a quoted positional placeholder ("\$1"), not embed the component literal.
        val (script, args) = rawInvocations.first()
        assertTrue("script must contain quoted -n \"\$1\" placeholder", script.contains("-n \"\$1\""))
        assertFalse("script must NOT contain 'Shell' (component must be in args, not script)", script.contains("Shell"))
        // Component with '$' must arrive in args list exactly as-is — never re-parsed by shell.
        assertTrue("args must not be empty", args.isNotEmpty())
        assertTrue("component with '\$' must be the first arg verbatim", args[0] == dollarComponent)
    }

    @Test
    fun `packageName arrives verbatim in MAIN and monkey args (F5 argv coverage)`() {
        // packageName is regex-validated ([A-Za-z0-9_.]+) so has no $ risk; this confirms
        // the argv mechanism for MAIN/monkey sites is wired.
        val rawInvocations = mutableListOf<Pair<String, List<String>>>()
        val pkg = "com.example.app"
        // Force -n to fail so MAIN and monkey run.
        run(
            packageName = pkg,
            resolveComponent = { null },
            shell = { script, args ->
                rawInvocations += script to args
                if ("MAIN" in script) "Error: not started" else ""  // MAIN fails, monkey succeeds
            },
        )
        // MAIN invocation: args[0] must be packageName
        val mainInv = rawInvocations.firstOrNull { "MAIN" in it.first }
        assertTrue("MAIN invocation expected", mainInv != null)
        assertEquals(pkg, mainInv!!.second[0])
        // Monkey invocation: args[0] must be packageName
        val monkeyInv = rawInvocations.firstOrNull { "monkey" in it.first }
        assertTrue("monkey invocation expected", monkeyInv != null)
        assertEquals(pkg, monkeyInv!!.second[0])
    }
}
