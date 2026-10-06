package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #288: a task the move left in a native split pane gets one `am start` onto the target display. */
class SplitFallbackCoreTest {

    private val ops = mutableListOf<String>()
    private val component = "ru.yandex.yandexnavi/.core.NavigatorActivity"

    private fun run(
        before: TaskModeState?,
        states: List<TaskModeState?>,
        shellOut: String = "Starting: Intent { cmp=$component }",
        resolve: () -> String? = { component },
    ): Boolean {
        val queue = ArrayDeque(states)
        return splitFallbackCore(
            taskId = 9, displayId = 7, before = before,
            stateOf = { ops += "state"; if (queue.size > 1) queue.removeFirst() else queue.firstOrNull() },
            resolveComponent = resolve,
            shell = { script, args -> ops += "shell:" + script.replace("\"\$1\"", args.first()); shellOut },
            sleep = { ops += "sleep:$it" },
        )
    }

    @Test fun `predicate - split pane left on the main display needs the fallback`() {
        assertTrue(needsSplitFallback(TaskModeState(4, 0), TaskModeState(4, 0), 7))
        assertTrue(needsSplitFallback(TaskModeState(3, 0), TaskModeState(3, 0), 7))
    }

    @Test fun `predicate - fullscreen before the move never needs it`() {
        assertFalse(needsSplitFallback(TaskModeState(1, 0), TaskModeState(1, 0), 7))
        assertFalse(needsSplitFallback(null, TaskModeState(4, 0), 7))
    }

    @Test fun `predicate - split pane already on the target or unreadable needs nothing`() {
        assertFalse(needsSplitFallback(TaskModeState(4, 0), TaskModeState(1, 7), 7))
        assertFalse(needsSplitFallback(TaskModeState(4, 0), null, 7))
    }

    @Test fun `fullscreen before the move - no read, no shell`() {
        assertTrue(run(TaskModeState(1, 0), listOf(TaskModeState(1, 0))))
        assertTrue(run(null, listOf(TaskModeState(4, 0))))
        assertEquals(emptyList<String>(), ops)
    }

    @Test fun `split pane the move already placed - one read, no shell`() {
        assertTrue(run(TaskModeState(4, 0), listOf(TaskModeState(1, 7))))
        assertEquals(listOf("state"), ops)
    }

    @Test fun `split pane left behind - am start onto the display then confirmed`() {
        assertTrue(run(TaskModeState(4, 0), listOf(TaskModeState(4, 0), null, TaskModeState(1, 7))))
        assertEquals(
            listOf(
                "state",
                "shell:am start --windowingMode 1 --display 7 -n $component",
                "sleep:300", "state",
                "sleep:300", "state",
            ),
            ops,
        )
    }

    @Test fun `split pane left behind and the am start does not move it - false`() {
        assertFalse(run(TaskModeState(4, 0), listOf(TaskModeState(4, 0))))
        assertEquals(1 + PULLBACK_READS, ops.count { it == "state" })
    }

    @Test fun `am start error - false without polling`() {
        assertFalse(run(TaskModeState(4, 0), listOf(TaskModeState(4, 0)), shellOut = "Error: Activity not started"))
        assertEquals(listOf("state", "shell:am start --windowingMode 1 --display 7 -n $component"), ops)
    }

    @Test fun `no launcher component - false without shell`() {
        assertFalse(run(TaskModeState(4, 0), listOf(TaskModeState(4, 0)), resolve = { null }))
        assertEquals(listOf("state"), ops)
    }
}
