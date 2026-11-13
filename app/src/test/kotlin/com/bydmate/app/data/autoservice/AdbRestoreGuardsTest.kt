package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Behaviour the ADB restore rework must leave alone. Compiles and runs on the code as it is
 * before the rework (uses no new [AdbRestoreSystem] member).
 */
class AdbRestoreGuardsTest {

    private fun manager(prefs: AdbRestorePreferences, system: AdbRestoreSystem) =
        AdbRestoreManager(prefs, system, TestScope())

    @Test
    fun `B4 live classic port with the service bound writes nothing and enables nothing`() = runTest {
        val system = RestoreFakeSystem().apply {
            classicAlive = { true }
            a11yBound = true
        }
        val m = manager(RestoreFakePrefs(), system)

        m.attemptIfNeeded("service_start")

        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
        assertTrue(system.calls.toString(), system.writesOfAnyKind().isEmpty())
        assertTrue(system.calls.toString(), system.calls.none { it.startsWith("a11y") })
    }

    @Test
    fun `B4 live classic port with the service unbound keeps today's single daemon enable and no writes`() = runTest {
        val system = RestoreFakeSystem().apply { classicAlive = { true } }
        val m = manager(RestoreFakePrefs(), system)

        m.attemptIfNeeded("service_start")

        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
        assertTrue(system.calls.toString(), system.writesOfAnyKind().isEmpty())
        assertEquals(1, system.calls.count { it == "a11y-daemon" })
    }

    @Test
    fun `C4 toggle off writes nothing of any kind and never touches the port`() = runTest {
        val system = RestoreFakeSystem()
        val m = manager(RestoreFakePrefs(enabled = false), system)

        m.attemptIfNeeded("service_start")

        assertEquals(AdbRestoreState.Disabled, m.state.value)
        assertTrue(system.calls.toString(), system.calls.isEmpty())
    }

    @Test
    fun `wireless path today - dead port, permission, wifi - writes adb_wifi_enabled and restores`() = runTest {
        val system = RestoreFakeSystem()
        val m = manager(RestoreFakePrefs(), system)

        m.attemptIfNeeded("service_start")

        assertTrue(m.state.value is AdbRestoreState.Restored)
        assertEquals(listOf("adb_wifi_enabled=1"), system.writesOfAnyKind())
    }
}
