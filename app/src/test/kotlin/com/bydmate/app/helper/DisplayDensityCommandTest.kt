package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayDensityCommandTest {

    private class RecordingExec(private val exitCode: Int = 0) : (String, List<String>) -> Int {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override fun invoke(script: String, args: List<String>): Int {
            calls += script to args
            return exitCode
        }
    }

    @Test
    fun `set builds wm density with density and displayId as positional args`() {
        val exec = RecordingExec()
        assertTrue(setDisplayDensityCore(4, 230, exec))
        assertEquals(listOf("wm density \"\$1\" -d \"\$2\"" to listOf("230", "4")), exec.calls)
    }

    @Test
    fun `density 0 builds wm density reset`() {
        val exec = RecordingExec()
        assertTrue(setDisplayDensityCore(4, 0, exec))
        assertEquals(listOf("wm density reset -d \"\$1\"" to listOf("4")), exec.calls)
    }

    @Test
    fun `main display is rejected without running anything`() {
        val exec = RecordingExec()
        assertFalse(setDisplayDensityCore(0, 230, exec))
        assertFalse(setDisplayDensityCore(-1, 230, exec))
        assertTrue(exec.calls.isEmpty())
    }

    @Test
    fun `out-of-range density is rejected without running anything`() {
        val exec = RecordingExec()
        assertFalse(setDisplayDensityCore(4, 79, exec))
        assertFalse(setDisplayDensityCore(4, 641, exec))
        assertTrue(exec.calls.isEmpty())
    }

    @Test
    fun `non-zero exit code maps to false`() {
        assertFalse(setDisplayDensityCore(4, 230, RecordingExec(exitCode = 1)))
    }
}
