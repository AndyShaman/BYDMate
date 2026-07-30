package com.bydmate.app.helper

import org.junit.Assert.assertEquals
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
    ) = ensureTypedFreeform(
        taskId, activityType, desiredActivityType, displayId, COMPONENT, shell, { ms -> ops += "sleep:$ms" },
    )

    private fun effectiveCmd(script: String, args: List<String>): String {
        var e = script
        args.forEachIndexed { i, a -> e = e.replace("\"\$${i + 1}\"", a) }
        return e
    }

    private val startCmd = "am start --windowingMode 5 --activityType 3 --display 0 -n $COMPONENT"

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
