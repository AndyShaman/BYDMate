package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reparent + resize core of TX_SPLIT37_MOVE_TASK. `am stack move-task` is silent on success and
 * prints "Error: ..."/an exception on refusal, so the resize must only follow a silent move.
 */
class Split37MoveTaskCoreTest {

    private class RecordingShell(private val output: String = "") : (String, List<String>) -> String {
        val calls = mutableListOf<Pair<String, List<String>>>()
        override fun invoke(script: String, args: List<String>): String {
            calls += script to args
            return output
        }
    }

    private class RecordingResize : (Int, Int, Int, Int, Int) -> Unit {
        val calls = mutableListOf<List<Int>>()
        override fun invoke(taskId: Int, left: Int, top: Int, right: Int, bottom: Int) {
            calls += listOf(taskId, left, top, right, bottom)
        }
    }

    @Test
    fun `silent move resizes the task to the pane rect and reports OK`() {
        val shell = RecordingShell()
        val resize = RecordingResize()
        val status = split37MoveTaskCore(42, 7, 0, 84, 576, 990, true, shell, resize)
        assertEquals(HelperBinderProtocol.SPLIT37_OK, status)
        assertEquals(1, shell.calls.size)
        assertEquals(listOf(listOf(42, 0, 84, 576, 990)), resize.calls)
    }

    @Test
    fun `move output without error markers still counts as success`() {
        val resize = RecordingResize()
        val status = split37MoveTaskCore(42, 7, 0, 84, 576, 990, true, RecordingShell("OK"), resize)
        assertEquals(HelperBinderProtocol.SPLIT37_OK, status)
        assertEquals(1, resize.calls.size)
    }

    @Test
    fun `move-task Error output fails without resizing`() {
        val resize = RecordingResize()
        val status = split37MoveTaskCore(
            42, 7, 0, 84, 576, 990, true,
            RecordingShell("Error: task 42 not found"), resize,
        )
        assertEquals(HelperBinderProtocol.SPLIT37_FAILED, status)
        assertTrue("a refused move must not be followed by a resize", resize.calls.isEmpty())
    }

    @Test
    fun `move-task exception trace fails without resizing`() {
        val resize = RecordingResize()
        val status = split37MoveTaskCore(
            42, 7, 0, 84, 576, 990, true,
            RecordingShell("java.lang.SecurityException: MANAGE_ACTIVITY_TASKS"), resize,
        )
        assertEquals(HelperBinderProtocol.SPLIT37_FAILED, status)
        assertTrue(resize.calls.isEmpty())
    }

    @Test
    fun `empty rect moves the task but skips the resize`() {
        val shell = RecordingShell()
        val resize = RecordingResize()
        assertEquals(
            HelperBinderProtocol.SPLIT37_OK,
            split37MoveTaskCore(42, 7, 0, 0, 0, 0, true, shell, resize),
        )
        assertEquals("the move itself must still be issued", 1, shell.calls.size)
        assertTrue("an empty rect must not reach setTaskBounds", resize.calls.isEmpty())

        // Degenerate rects on either axis are empty too.
        assertEquals(
            HelperBinderProtocol.SPLIT37_OK,
            split37MoveTaskCore(42, 7, 576, 84, 576, 990, true, shell, resize),
        )
        assertEquals(
            HelperBinderProtocol.SPLIT37_OK,
            split37MoveTaskCore(42, 7, 0, 990, 576, 990, true, shell, resize),
        )
        assertTrue(resize.calls.isEmpty())
    }

    @Test
    fun `ids travel as positional args, never interpolated into the script`() {
        val shell = RecordingShell()
        split37MoveTaskCore(42, 7, 0, 84, 576, 990, true, shell, RecordingResize())
        val (script, args) = shell.calls.single()
        assertEquals("am stack move-task \"\$1\" \"\$2\" true", script)
        assertEquals("\$1 = taskId, \$2 = rootTaskId", listOf("42", "7"), args)
    }

    @Test
    fun `toTop false moves the task to the bottom of the root`() {
        // First half of the engine's bounce: the task must NOT be raised on this move.
        val shell = RecordingShell()
        val resize = RecordingResize()
        val status = split37MoveTaskCore(42, 7, 0, 0, 0, 0, false, shell, resize)
        assertEquals(HelperBinderProtocol.SPLIT37_OK, status)
        assertEquals("am stack move-task \"\$1\" \"\$2\" false", shell.calls.single().first)
        assertTrue(resize.calls.isEmpty())
    }
}
