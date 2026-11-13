package com.bydmate.app.data.autoservice

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C1-C5: classic ADB is tried first by writing `adb_enabled` and the two BYD properties, before
 * any wireless-debugging dialog is raised. Needs the new `readAdbEnabled`, `writeAdbEnabled`,
 * `readBydAdbProp`, `writeBydAdbProp` members of [AdbRestoreSystem].
 */
class AdbRestoreClassicFirstTest {

    private val wantedEnable = "prop ${RestoreFakeSystemV2.PROP_ENABLE}=true"
    private val wantedConnect = "prop ${RestoreFakeSystemV2.PROP_CONNECT}=1"

    private fun manager(system: AdbRestoreSystem, prefs: AdbRestorePreferences = RestoreFakePrefs()) =
        AdbRestoreManager(prefs, system, TestScope())

    @Test
    fun `C1 adb_enabled 0 - written and read back, props attempted, port rechecked, Restored without a dialog`() = runTest {
        val system = RestoreFakeSystemV2()
        system.classicAlive = { system.adbEnabled == 1 }
        val m = manager(system)

        m.attemptIfNeeded("service_start")

        val log = system.calls
        val write = log.indexOf("adb_enabled=1")
        assertTrue(log.toString(), write >= 0)
        assertTrue("read-back after the write: $log", log.drop(write).contains("read adb_enabled"))
        assertTrue(log.toString(), log.contains(wantedEnable) && log.contains(wantedConnect))
        val lastWrite = maxOf(write, log.indexOf(wantedEnable), log.indexOf(wantedConnect))
        assertTrue("port rechecked after the writes: $log", log.drop(lastWrite + 1).contains("classic=true"))
        assertTrue(m.state.value is AdbRestoreState.Restored)
        assertTrue("no dialog path: $log", log.none { it.startsWith("adb_wifi_enabled=") })
    }

    @Test
    fun `C1 props refused by the system do not stop the attempt`() = runTest {
        val system = RestoreFakeSystemV2().apply { propWriteAccepted = false }
        system.classicAlive = { system.adbEnabled == 1 }
        val m = manager(system)

        m.attemptIfNeeded("service_start")

        assertTrue(m.state.value is AdbRestoreState.Restored)
        assertTrue(system.calls.toString(), system.calls.none { it.startsWith("adb_wifi_enabled=") })
    }

    @Test
    fun `C2 adb_enabled already 1 is not written, props that differ are still attempted`() = runTest {
        val system = RestoreFakeSystemV2().apply { adbEnabled = 1 }

        manager(system).attemptIfNeeded("service_start")

        assertTrue(system.calls.toString(), system.calls.none { it.startsWith("adb_enabled=") })
        assertTrue(system.calls.toString(), system.calls.contains(wantedEnable) && system.calls.contains(wantedConnect))
    }

    @Test
    fun `C2 props already at the wanted values are not written`() = runTest {
        val system = RestoreFakeSystemV2().apply {
            adbEnabled = 1
            props[RestoreFakeSystemV2.PROP_ENABLE] = "true"
            props[RestoreFakeSystemV2.PROP_CONNECT] = "1"
        }

        manager(system).attemptIfNeeded("service_start")

        assertTrue(system.calls.toString(), system.calls.none { it.startsWith("adb_enabled=") || it.startsWith("prop ") })
    }

    @Test
    fun `C3 port still dead after the classic writes - the wireless path runs after them`() = runTest {
        val system = RestoreFakeSystemV2()
        val m = manager(system)

        m.attemptIfNeeded("service_start")

        val log = system.calls
        val classicWrite = log.indexOf("adb_enabled=1")
        val wifiWrite = log.indexOf("adb_wifi_enabled=1")
        assertTrue(log.toString(), classicWrite >= 0 && wifiWrite > classicWrite)
        assertTrue(log.toString(), log.contains("tcpip"))
        assertTrue(m.state.value is AdbRestoreState.Restored)
    }

    @Test
    fun `C4 toggle off writes nothing of any kind`() = runTest {
        val system = RestoreFakeSystemV2()

        manager(system, RestoreFakePrefs(enabled = false)).attemptIfNeeded("service_start")

        assertTrue(system.calls.toString(), system.calls.isEmpty())
    }

    @Test
    fun `C5 no WRITE_SECURE_SETTINGS - no adb_enabled or property write, NeedsActivation as today`() = runTest {
        val system = RestoreFakeSystemV2().apply { permission = false }
        val m = manager(system)

        m.attemptIfNeeded("service_start")

        assertEquals(AdbRestoreState.NeedsActivation, m.state.value)
        assertTrue(system.calls.toString(), system.writesOfAnyKind().isEmpty())
    }
}
