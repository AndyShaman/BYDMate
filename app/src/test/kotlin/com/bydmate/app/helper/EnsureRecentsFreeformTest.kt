package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Direct tests of the shared freeform typing invariant. Both entry points
 * ([raiseFreeformTaskCore] and the freeform branch of [setWindowingModeCompat]) delegate here,
 * so the three branches are pinned once instead of per caller.
 */
class EnsureRecentsFreeformTest {

    private val ops = mutableListOf<String>()

    private fun run(
        taskId: Int,
        activityType: Int,
        displayId: Int = 0,
        shell: (String, List<String>) -> String = { script, args -> ops += effectiveCmd(script, args); "" },
    ) = ensureRecentsFreeform(
        taskId, activityType, displayId, COMPONENT, shell, { ms -> ops += "sleep:$ms" },
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

    private companion object {
        const val COMPONENT = "com.example.navi/com.example.navi.MainActivity"
    }
}
