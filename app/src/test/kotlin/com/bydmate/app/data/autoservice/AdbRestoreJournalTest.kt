package com.bydmate.app.data.autoservice

import com.bydmate.app.diagnostics.TraceRecorder
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * D1 and D2 of the ADB restore rework. Compile against today's API (the journal seam is
 * `Trace` + [TraceRecorder]); red by assertion until the manager is changed.
 */
class AdbRestoreJournalTest {

    @get:Rule val trace = TraceRecorder()

    private fun manager(prefs: AdbRestorePreferences, system: AdbRestoreSystem) =
        AdbRestoreManager(prefs, system, TestScope())

    @Test
    fun `D1 Restored survives a later check that finds the port alive`() = runTest {
        val system = RestoreFakeSystem()
        val m = manager(RestoreFakePrefs(), system)
        m.attemptIfNeeded("service_start")
        val restored = m.state.value
        assertTrue("setup: expected Restored, got $restored", restored is AdbRestoreState.Restored)

        // Port 5555 is alive from now on, as after a successful restore.
        system.classicAlive = { true }
        m.attemptIfNeeded("watchdog")

        assertEquals(restored, m.state.value)
    }

    @Test
    fun `D2 each restore transition leaves one journal line, in order`() = runTest {
        val system = RestoreFakeSystem()
        val m = manager(RestoreFakePrefs(), system)

        m.attemptIfNeeded("service_start")

        // Wire format is the implementer's: area APP, event `adb-restore`, a `state=` field.
        val lines = trace.events().filter { it.contains("adb-restore") }
        val states = lines.mapNotNull { Regex("""state=(\w+)""").find(it)?.groupValues?.get(1) }
        assertEquals(lines.toString(), listOf("Connecting", "Restored"), states)
    }

    @Test
    fun `D2 toggle off leaves a Disabled journal line`() = runTest {
        val m = manager(RestoreFakePrefs(enabled = false), RestoreFakeSystem())

        m.attemptIfNeeded("service_start")

        assertTrue(trace.events().toString(),
            trace.events().any { it.contains("adb-restore") && it.contains("state=Disabled") })
    }

    // D2, dialog half: PENDING. A handled dialog event should leave one journal line too, but
    // onDialogShown takes a Context/AccessibilityService and its new signature is not decided
    // yet, so no test is invented here. Seam to use: Trace.event(TraceArea.APP, "adb-dialog", ...)
    // next to the existing record(), observed through TraceRecorder.
}
