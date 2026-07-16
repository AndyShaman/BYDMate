package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SetWindowingModeCompatTest {

    private val ops = mutableListOf<String>()

    private fun run(
        mode: Int = WINDOWING_MODE_FREEFORM,
        reflectSet: (Int, Int) -> Unit = { t, m -> ops += "reflect:$t:$m" },
        resolveComponent: () -> String? = { ops += "resolve"; "ru.yandex.yandexnavi/.core.NavigatorActivity" },
        shell: (String) -> String = { cmd -> ops += "shell:$cmd"; "" },
    ) = setWindowingModeCompat(36, mode, 4, reflectSet, resolveComponent, shell) { ops += "sleep:$it" }

    @Test
    fun `reflect path works - shell never touched`() {
        run()
        assertEquals(listOf("reflect:36:5"), ops)
    }

    @Test
    fun `missing binder API - freeform goes through am start with mode and display`() {
        // Root cause 2026-07-15: IActivityTaskManager.setTaskWindowingMode was removed in AOSP S
        // and DiLink 5 did not restore it — the shell ActivityStarter path is the only one that
        // applies mode+display to an existing task (validated on-car).
        run(reflectSet = { _, _ -> throw NoSuchMethodException("setTaskWindowingMode") })
        assertEquals(
            listOf(
                "resolve",
                "shell:am start --windowingMode 5 --display 4 -n ru.yandex.yandexnavi/.core.NavigatorActivity",
            ),
            ops,
        )
    }

    @Test
    fun `missing binder API - fullscreen removes the stack and relaunches on the main display`() {
        // Freeform sticks to a task on this ROM (`am start --windowingMode 1` is ignored);
        // the only way back is removing the stack and relaunching (validated on-car).
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
    }

    @Test
    fun `shell error output throws`() {
        assertThrows(IllegalStateException::class.java) {
            run(
                reflectSet = { _, _ -> throw NoSuchMethodException("x") },
                shell = { cmd -> ops += "shell:$cmd"; "Error: Activity not started" },
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
                shell = { cmd ->
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
        val boom = IllegalStateException("freeform disabled")
        val thrown = assertThrows(IllegalStateException::class.java) {
            run(reflectSet = { _, _ -> throw boom })
        }
        assertSame(boom, thrown)
        assertTrue(ops.isEmpty())
    }
}
