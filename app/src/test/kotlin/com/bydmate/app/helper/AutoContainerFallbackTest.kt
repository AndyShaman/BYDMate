package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoContainerFallbackTest {

    private class RecordingExec(private val codes: MutableList<Int>) : (String, String) -> Int {
        val scripts = mutableListOf<String>()
        val args = mutableListOf<String>()
        override fun invoke(script: String, arg: String): Int {
            scripts += script
            args += arg
            return codes.removeAt(0)
        }
    }

    @Test
    fun `snake_case success never tries CamelCase`() {
        val exec = RecordingExec(mutableListOf(0))
        assertTrue(autoContainerCall(16, exec))
        assertEquals(1, exec.scripts.size)
        assertTrue(exec.scripts[0].contains("auto_container"))
        assertTrue(exec.scripts[0].contains("i32 1000"))
        assertEquals("16", exec.args[0])
    }

    @Test
    fun `snake_case failure falls back to CamelCase`() {
        val exec = RecordingExec(mutableListOf(10, 0))
        assertTrue(autoContainerCall(18, exec))
        assertEquals(2, exec.scripts.size)
        assertTrue(exec.scripts[1].contains("AutoContainer"))
        assertFalse(exec.scripts[1].contains("auto_container"))
        assertTrue(exec.scripts[1].contains("i32 1000"))
        assertEquals("18", exec.args[1])
    }

    @Test
    fun `both names failing returns false`() {
        val exec = RecordingExec(mutableListOf(10, 10))
        assertFalse(autoContainerCall(0, exec))
        assertEquals(2, exec.scripts.size)
    }

    @Test
    fun `non-whitelisted cmd throws before any exec`() {
        val exec = RecordingExec(mutableListOf())
        assertThrows(IllegalArgumentException::class.java) { autoContainerCall(17, exec) }
        assertEquals(0, exec.scripts.size)
    }
}
