package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayDensityCommandTest {

    private class RecordingExec(
        private val exitCode: Int = 0,
        private val output: (String) -> String = { "" },
    ) : (String, List<String>) -> CmdResult {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override fun invoke(script: String, args: List<String>): CmdResult {
            calls += script to args
            // Only the set/reset command carries the exit code under test; the readback that
            // follows it is a separate command and is always reachable.
            val code = if (script.contains("wm density -d")) 0 else exitCode
            return CmdResult(code, output(script))
        }
    }

    /** What `wm density -d 1` prints on Android 10 while an override is in force. */
    private fun readbackExec(exitCode: Int = 0) = RecordingExec(exitCode) { script ->
        if (script.contains("wm density -d")) "Physical density: 320\nOverride density: 160" else ""
    }

    @Test
    fun `set builds wm density with density and displayId as positional args, then reads back`() {
        val exec = readbackExec()
        val result = setDisplayDensityCore(4, 230, exec)
        assertTrue(result.ok)
        assertEquals(
            listOf(
                "wm density \"\$1\" -d \"\$2\"" to listOf("230", "4"),
                "wm density -d \"\$1\"" to listOf("4"),
            ),
            exec.calls,
        )
        assertEquals("Physical density: 320; Override density: 160", result.readback)
    }

    @Test
    fun `density 0 builds wm density reset and reads back too`() {
        val exec = readbackExec()
        val result = setDisplayDensityCore(4, 0, exec)
        assertTrue(result.ok)
        assertEquals(
            listOf(
                "wm density reset -d \"\$1\"" to listOf("4"),
                "wm density -d \"\$1\"" to listOf("4"),
            ),
            exec.calls,
        )
        assertEquals("Physical density: 320; Override density: 160", result.readback)
    }

    @Test
    fun `main display is rejected without running anything`() {
        val exec = readbackExec()
        assertFalse(setDisplayDensityCore(0, 230, exec).ok)
        assertFalse(setDisplayDensityCore(-1, 230, exec).ok)
        assertTrue(exec.calls.isEmpty())
    }

    @Test
    fun `out-of-range density is rejected without running anything`() {
        val exec = readbackExec()
        assertFalse(setDisplayDensityCore(4, 79, exec).ok)
        assertFalse(setDisplayDensityCore(4, 641, exec).ok)
        assertTrue(exec.calls.isEmpty())
    }

    /** A rejected write is exactly the case the readback has to explain, so it still runs. */
    @Test
    fun `non-zero exit code maps to not-ok but the readback is still captured`() {
        val result = setDisplayDensityCore(4, 230, readbackExec(exitCode = 1))
        assertFalse(result.ok)
        assertEquals("Physical density: 320; Override density: 160", result.readback)
    }

    @Test
    fun `a readback that printed nothing is an empty string, not a crash`() {
        val result = setDisplayDensityCore(4, 230, RecordingExec())
        assertTrue(result.ok)
        assertEquals("", result.readback)
    }
}
