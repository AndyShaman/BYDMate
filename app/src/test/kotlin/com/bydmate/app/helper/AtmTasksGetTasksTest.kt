package com.bydmate.app.helper

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for IActivityTaskManager.getTasks overload resolution and argument building (#483).
 */
class AtmTasksGetTasksTest {

    class FakeAtmFlagsOnly {
        fun getTasks(
            maxNum: Int,
            filterOnlyVisibleRecents: Boolean,
            keepIntentExtra: Boolean,
        ): List<Any> = emptyList()
    }

    class FakeAtmFlagsDisplayOnly(private val result: List<Any>) {
        var seenMaxNum: Int? = null
        var seenDisplayId: Int? = null

        fun getTasks(
            maxNum: Int,
            filterOnlyVisibleRecents: Boolean,
            keepIntentExtra: Boolean,
            displayId: Int,
        ): List<Any> {
            seenMaxNum = maxNum
            seenDisplayId = displayId
            return result
        }
    }

    class FakeAtmBothFlags {
        fun getTasks(
            maxNum: Int,
            filterOnlyVisibleRecents: Boolean,
            keepIntentExtra: Boolean,
        ): List<Any> = emptyList()

        fun getTasks(
            maxNum: Int,
            filterOnlyVisibleRecents: Boolean,
            keepIntentExtra: Boolean,
            displayId: Int,
        ): List<Any> = emptyList()
    }

    class FakeAtmPlainOnly {
        fun getTasks(maxNum: Int): List<Any> = emptyList()
    }

    class FakeAtmLongOnly {
        fun getTasks(maxNum: Long): List<Any> = emptyList()
    }

    @Test
    fun `only 3-arg getTasks resolves to FLAGS`() {
        val resolved = resolveGetTasks(FakeAtmFlagsOnly::class.java)
        assertNotNull(resolved)
        assertEquals(GetTasksShape.FLAGS, resolved!!.second)
        assertArrayEquals(arrayOf<Any>(50, false, false), getTasksArgs(GetTasksShape.FLAGS, 50))
    }

    @Test
    fun `only 4-arg getTasks resolves to FLAGS_DISPLAY and invokes with displayId -1`() {
        val expected = listOf("task-1", "task-2")
        val fake = FakeAtmFlagsDisplayOnly(expected)

        val resolved = resolveGetTasks(FakeAtmFlagsDisplayOnly::class.java)
        assertNotNull(resolved)
        val (method, shape) = resolved!!
        assertEquals(GetTasksShape.FLAGS_DISPLAY, shape)

        val args = getTasksArgs(GetTasksShape.FLAGS_DISPLAY, 50)
        assertArrayEquals(arrayOf<Any>(50, false, false, -1), args)

        val actual = method.invoke(fake, *args)
        assertEquals(expected, actual)
        assertEquals(50, fake.seenMaxNum)
        assertEquals(-1, fake.seenDisplayId)
    }

    @Test
    fun `both 3-arg and 4-arg getTasks prefer FLAGS`() {
        val resolved = resolveGetTasks(FakeAtmBothFlags::class.java)
        assertNotNull(resolved)
        assertEquals(GetTasksShape.FLAGS, resolved!!.second)
        assertEquals(3, resolved.first.parameterCount)
    }

    @Test
    fun `only 1-arg int getTasks resolves to PLAIN`() {
        val resolved = resolveGetTasks(FakeAtmPlainOnly::class.java)
        assertNotNull(resolved)
        assertEquals(GetTasksShape.PLAIN, resolved!!.second)
        assertArrayEquals(arrayOf<Any>(50), getTasksArgs(GetTasksShape.PLAIN, 50))
    }

    @Test
    fun `unsupported getTasks overload returns null and lists parameter types`() {
        assertNull(resolveGetTasks(FakeAtmLongOnly::class.java))
        val overloads = getTasksOverloads(FakeAtmLongOnly::class.java)
        assertTrue("expected long in overloads, got: $overloads", overloads.contains("long"))
    }
}
