package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests of the shared freeform typing invariant. Both entry points
 * ([raiseFreeformTaskCore] and the freeform branch of [setWindowingModeCompat]) delegate here,
 * so the branches are pinned once instead of per caller. The matrix covers both directions:
 * desired=RECENTS (cluster projection) and desired=STANDARD (split panes, 392).
 */
class EnsureTypedFreeformTest {

    private val ops = mutableListOf<String>()

    private fun run(
        taskId: Int,
        activityType: Int,
        desiredActivityType: Int = ACTIVITY_TYPE_RECENTS,
        displayId: Int = 0,
        shell: (String, List<String>) -> String = { script, args -> ops += effectiveCmd(script, args); "" },
        stateOf: (Int) -> TaskModeState? = { _ -> null },
    ) = ensureTypedFreeform(
        taskId, activityType, desiredActivityType, displayId, COMPONENT, shell, { ms -> ops += "sleep:$ms" },
        stateOf,
    )

    private fun effectiveCmd(script: String, args: List<String>): String {
        var e = script
        args.forEachIndexed { i, a -> e = e.replace("\"\$${i + 1}\"", a) }
        return e
    }

    private val startCmd = "am start --windowingMode 5 --activityType 3 --display 0 -n $COMPONENT"
    private val probeCmd = "am start --windowingMode 5 --display 0 -n $COMPONENT"

    @Test
    fun `STANDARD task is recreated - remove then start`() {
        run(taskId = 7, activityType = ACTIVITY_TYPE_STANDARD)
        assertEquals(listOf("am stack remove 7", "sleep:500", startCmd), ops)
    }

    @Test
    fun `RECENTS task keeps the light single am start`() {
        run(taskId = 7, activityType = ACTIVITY_TYPE_RECENTS)
        assertEquals(listOf(startCmd), ops)
    }

    @Test
    fun `absent task is created by the same single am start`() {
        // taskId -1 means "no task": nothing to remove, the flags apply at creation.
        run(taskId = -1, activityType = ACTIVITY_TYPE_STANDARD)
        assertEquals(listOf(startCmd), ops)
    }

    @Test
    fun `unknown activity type never removes on a guess`() {
        run(taskId = 7, activityType = -1)
        assertEquals(listOf(startCmd), ops)
    }

    @Test
    fun `a failing remove aborts before the relaunch`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(taskId = 7, activityType = ACTIVITY_TYPE_STANDARD, shell = { script, args ->
                val cmd = effectiveCmd(script, args)
                ops += cmd
                if ("am stack remove" in cmd) "Exception occurred while executing" else ""
            })
        }
        assertTrue(thrown.message!!.contains("am stack remove failed"))
        assertEquals(listOf("am stack remove 7"), ops)
    }

    @Test
    fun `a failing start is reported, not swallowed`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(taskId = 7, activityType = ACTIVITY_TYPE_RECENTS, shell = { script, args ->
                ops += effectiveCmd(script, args)
                "Error: Activity not started"
            })
        }
        assertTrue(thrown.message!!.contains("am start freeform failed"))
    }

    // --- non-destructive freeform probe before the retype remove (field bug, DiLink 5.1) ---

    @Test
    fun `probe that never becomes freeform throws before removing the live task`() {
        // Firmware with freeform off silently coerces the mode away. Removing the navigator task
        // first (legacy) killed the user's guidance session for a launch that could never succeed.
        // Three stale post-probe reads are the full settle budget before that verdict.
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(
                taskId = 7,
                activityType = ACTIVITY_TYPE_STANDARD,
                stateOf = { TaskModeState(WINDOWING_MODE_FULLSCREEN, 0) },
            )
        }
        assertTrue(
            "message must classify as UNAVAILABLE for launchFreeformCore: ${thrown.message}",
            isFreeformUnsupported(thrown),
        )
        assertFalse("the live task must survive a failed probe", ops.any { "am stack remove" in it })
        assertEquals(listOf(probeCmd, "sleep:500", "sleep:500", "sleep:500"), ops)
    }

    @Test
    fun `a successful probe falls through to the recreating remove`() {
        run(
            taskId = 7,
            activityType = ACTIVITY_TYPE_STANDARD,
            stateOf = { TaskModeState(WINDOWING_MODE_FREEFORM, 0) },
        )
        assertEquals(listOf(probeCmd, "sleep:500", "am stack remove 7", "sleep:500", startCmd), ops)
    }

    @Test
    fun `a slow firmware reporting freeform only on the second read is a success`() {
        // Settle tolerance: one stale read must not be mistaken for "freeform is off".
        var reads = 0
        run(
            taskId = 7,
            activityType = ACTIVITY_TYPE_STANDARD,
            stateOf = {
                reads++
                // 1st read = the pre-probe gate, 2nd = still stale, 3rd = settled.
                if (reads >= 3) TaskModeState(WINDOWING_MODE_FREEFORM, 0)
                else TaskModeState(WINDOWING_MODE_FULLSCREEN, 0)
            },
        )
        assertEquals(
            listOf(probeCmd, "sleep:500", "sleep:500", "am stack remove 7", "sleep:500", startCmd),
            ops,
        )
    }

    @Test
    fun `a slow firmware reporting freeform only on the third read is a success`() {
        // Full settle budget: two stale post-probe reads must not be mistaken for "freeform is off".
        var reads = 0
        run(
            taskId = 7,
            activityType = ACTIVITY_TYPE_STANDARD,
            stateOf = {
                reads++
                // 1st read = the pre-probe gate, 2nd and 3rd = still stale, 4th = settled.
                if (reads >= 4) TaskModeState(WINDOWING_MODE_FREEFORM, 0)
                else TaskModeState(WINDOWING_MODE_FULLSCREEN, 0)
            },
        )
        assertEquals(
            listOf(probeCmd, "sleep:500", "sleep:500", "sleep:500", "am stack remove 7", "sleep:500", startCmd),
            ops,
        )
    }

    @Test
    fun `unreadable task state keeps the legacy remove path`() {
        // Default stateOf (production sites that cannot read state): byte-identical legacy sequence.
        run(taskId = 7, activityType = ACTIVITY_TYPE_STANDARD)
        assertEquals(listOf("am stack remove 7", "sleep:500", startCmd), ops)
        assertFalse("no probe start without a state reader", ops.any { it == probeCmd })
    }

    @Test
    fun `a failing probe start is reported, not swallowed`() {
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(
                taskId = 7,
                activityType = ACTIVITY_TYPE_STANDARD,
                shell = { script, args -> ops += effectiveCmd(script, args); "Error: Activity not started" },
                stateOf = { TaskModeState(WINDOWING_MODE_FULLSCREEN, 0) },
            )
        }
        assertTrue(thrown.message!!.contains("am start freeform failed"))
        assertEquals(listOf(probeCmd), ops)
    }

    // --- desired = STANDARD: the split-pane direction (392) ---

    @Test
    fun `standard desired standard live - light path, no remove`() {
        val commands = mutableListOf<String>()
        ensureTypedFreeform(
            taskId = 42, liveActivityType = 1, desiredActivityType = 1, displayId = 0,
            component = "ru.yandex.music/.main.MainScreenActivity",
            shell = { cmd, _ -> commands += cmd; "" }, sleep = { },
        )
        assertEquals(1, commands.size)
        assertEquals("am start --windowingMode 5 --display 0 -n \"\$1\"", commands[0])
    }

    @Test
    fun `standard desired recents live - remove then standard relaunch (v39 pane migration)`() {
        val commands = mutableListOf<String>()
        ensureTypedFreeform(
            taskId = 42, liveActivityType = 3, desiredActivityType = 1, displayId = 0,
            component = "ru.yandex.music/.main.MainScreenActivity",
            shell = { cmd, _ -> commands += cmd; "" }, sleep = { },
        )
        assertEquals(listOf(
            "am stack remove 42",
            "am start --windowingMode 5 --display 0 -n \"\$1\"",
        ), commands)
    }

    @Test
    fun `recents desired standard live - remove then recents relaunch (cluster path unchanged)`() {
        val commands = mutableListOf<String>()
        ensureTypedFreeform(
            taskId = 42, liveActivityType = 1, desiredActivityType = 3, displayId = 2,
            component = "ru.yandex.yandexnavi/.core.NavigatorActivity",
            shell = { cmd, _ -> commands += cmd; "" }, sleep = { },
        )
        assertEquals(listOf(
            "am stack remove 42",
            "am start --windowingMode 5 --activityType 3 --display 2 -n \"\$1\"",
        ), commands)
    }

    // --- fleet safety: only the STANDARD/RECENTS pair is ever retyped ---

    @Test
    fun `exotic live type with recents desired - no remove (v39 behavior preserved)`() {
        // ACTIVITY_TYPE_HOME (2): v3.9 issued a single am start for it on the RECENTS direction,
        // and so must this. Anti-vacuity: a bare `live != desired` check removes the task here.
        val commands = mutableListOf<String>()
        ensureTypedFreeform(
            taskId = 42, liveActivityType = 2, desiredActivityType = ACTIVITY_TYPE_RECENTS, displayId = 0,
            component = "ru.yandex.music/.main.MainScreenActivity",
            shell = { cmd, _ -> commands += cmd; "" }, sleep = { },
        )
        assertEquals(
            listOf("am start --windowingMode 5 --activityType 3 --display 0 -n \"\$1\""),
            commands,
        )
    }

    @Test
    fun `exotic live type with standard desired - no remove`() {
        val commands = mutableListOf<String>()
        ensureTypedFreeform(
            taskId = 42, liveActivityType = 2, desiredActivityType = ACTIVITY_TYPE_STANDARD, displayId = 0,
            component = "ru.yandex.music/.main.MainScreenActivity",
            shell = { cmd, _ -> commands += cmd; "" }, sleep = { },
        )
        assertEquals(listOf("am start --windowingMode 5 --display 0 -n \"\$1\""), commands)
    }

    @Test
    fun `unknown live type - no remove, plain desired-typed start`() {
        val commands = mutableListOf<String>()
        ensureTypedFreeform(
            taskId = 42, liveActivityType = -1, desiredActivityType = 1, displayId = 0,
            component = "ru.yandex.music/.main.MainScreenActivity",
            shell = { cmd, _ -> commands += cmd; "" }, sleep = { },
        )
        assertEquals(listOf("am start --windowingMode 5 --display 0 -n \"\$1\""), commands)
    }

    private companion object {
        const val COMPONENT = "com.example.navi/com.example.navi.MainActivity"
    }
}
