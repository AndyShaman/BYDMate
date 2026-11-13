package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B1-B4: our accessibility service is enabled before the dialog can appear. Needs the new
 * `AdbRestoreSystem.enableAccessibilityServiceInProcess`.
 */
class AdbRestoreServiceFirstTest {

    private fun manager(system: AdbRestoreSystem, prefs: AdbRestorePreferences = RestoreFakePrefs()) =
        AdbRestoreManager(prefs, system, TestScope())

    @Test
    fun `B1 unbound service is enabled in-process first, then adb_wifi_enabled is written`() = runTest {
        val system = RestoreFakeSystemV2()

        manager(system).attemptIfNeeded("service_start")

        val enable = system.calls.indexOf("a11y-inprocess")
        val write = system.calls.indexOf("adb_wifi_enabled=1")
        assertTrue(system.calls.toString(), enable >= 0 && write >= 0)
        assertTrue("enable must come before the write: ${system.calls}", enable < write)
        assertEquals(1, system.calls.count { it == "a11y-inprocess" })
    }

    @Test
    fun `B2 in-process failure falls back to the daemon path and the attempt goes on`() = runTest {
        val system = RestoreFakeSystemV2().apply { inProcessResult = false }

        val m = manager(system)
        m.attemptIfNeeded("service_start")

        val inProcess = system.calls.indexOf("a11y-inprocess")
        val daemon = system.calls.indexOf("a11y-daemon")
        val write = system.calls.indexOf("adb_wifi_enabled=1")
        assertTrue(system.calls.toString(), inProcess >= 0 && daemon > inProcess && write > daemon)
        assertTrue(m.state.value is AdbRestoreState.Restored)
    }

    @Test
    fun `B3 service already bound - no enable call at all, adb_wifi_enabled is written as today`() = runTest {
        val system = RestoreFakeSystemV2().apply { a11yBound = true }

        manager(system).attemptIfNeeded("service_start")

        assertTrue(system.calls.toString(), system.calls.none { it.startsWith("a11y") })
        assertEquals(1, system.calls.count { it == "adb_wifi_enabled=1" })
    }

    @Test
    fun `B4 live classic port never enables in-process and writes nothing`() = runTest {
        val system = RestoreFakeSystemV2().apply { classicAlive = { true } }

        val m = manager(system)
        m.attemptIfNeeded("service_start")

        assertEquals(AdbRestoreState.NotNeeded, m.state.value)
        assertTrue(system.calls.toString(), system.calls.none { it == "a11y-inprocess" })
        assertTrue(system.calls.toString(), system.writesOfAnyKind().isEmpty())
    }
}
