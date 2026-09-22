package com.bydmate.app.helper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [dumpFidsCore]. Synthetic classes are fed via the [classResolver] lambda
 * so the test has no dependency on the BYD SDK or Android runtime.
 */
class DumpFidsCoreTest {

    // --- Synthetic field holders for filter tests ---

    // A Kotlin class (not object) whose companion provides static fields and whose body
    // provides instance fields. declaredFields on this class yields both; only the static
    // companion fields must appear in the dump.
    @Suppress("unused")
    class FakeFeatureIds(
        // Non-static instance field — must NOT appear in the dump.
        @JvmField val instanceInt: Int = 999,
    ) {
        companion object {
            @JvmField val STATIC_INT: Int = 100
            @JvmField val STATIC_LONG: Long = 200L
            @JvmField val STATIC_STRING: String = "should_not_appear"
            const val CONST_INT: Int = 300
        }
    }

    // Declared in REVERSE-alphabetical order so `getDeclaredFields` natural order (BETA, ALPHA)
    // differs from sorted order (ALPHA, BETA). A missing `.sorted()` would fail the sort test.
    @Suppress("unused")
    object FakeConstants {
        @JvmField val BETA: Int = 2
        @JvmField val ALPHA: Int = 1
    }

    // Class with a nested object to verify declaredClasses scanning.
    @Suppress("unused")
    class FakeOuter {
        companion object {
            @JvmField val OUTER_INT: Int = 10
        }
        object Inner {
            @JvmField val INNER_LONG: Long = 20L
        }
    }

    // --- Helpers ---

    private fun resolverFor(vararg pairs: Pair<String, Class<*>>): (String) -> Class<*>? {
        val map = mapOf(*pairs)
        return { name -> map[name] }
    }

    private fun dumpWith(vararg pairs: Pair<String, Class<*>>): String =
        dumpFidsCore(resolverFor(*pairs))

    // --- Tests ---

    @Test
    fun `static int and long fields are collected, string and instance fields are not`() {
        // FakeFeatureIds is a regular class: instanceInt is a non-static field, so the
        // Modifier.isStatic filter must exclude it. @JvmField companion fields ARE static.
        val result = dumpWith("android.hardware.bydauto.BYDAutoFeatureIds" to FakeFeatureIds::class.java)
        assertTrue("STATIC_INT must appear", result.contains("STATIC_INT=100"))
        assertTrue("STATIC_LONG must appear", result.contains("STATIC_LONG=200"))
        assertTrue("CONST_INT must appear", result.contains("CONST_INT=300"))
        assertFalse("STATIC_STRING must not appear (type filter)", result.contains("STATIC_STRING"))
        // Instance field must not appear — this is the isStatic mutation guard.
        assertFalse("instanceInt must not appear (isStatic filter)", result.contains("instanceInt"))
    }

    @Test
    fun `output lines are sorted alphabetically`() {
        // FakeConstants declares BETA before ALPHA (reverse order), so the unsorted output from
        // getDeclaredFields would be ["FakeConstants.BETA=2", "FakeConstants.ALPHA=1"].
        // After .sorted() it must be ["FakeConstants.ALPHA=1", "FakeConstants.BETA=2"].
        // Removing .sorted() from dumpFidsCore would break this exact assertion.
        val result = dumpWith("android.hardware.bydauto.BYDAutoConstants" to FakeConstants::class.java)
        val lines = result.lines().filter { it.isNotBlank() }
        assertEquals(
            listOf("FakeConstants.ALPHA=1", "FakeConstants.BETA=2"),
            lines,
        )
    }

    @Test
    fun `both target classes are collected and prefixed correctly`() {
        val result = dumpWith(
            "android.hardware.bydauto.BYDAutoFeatureIds" to FakeFeatureIds::class.java,
            "android.hardware.bydauto.BYDAutoConstants" to FakeConstants::class.java,
        )
        assertTrue("FakeFeatureIds prefix", result.contains("FakeFeatureIds."))
        assertTrue("FakeConstants prefix", result.contains("FakeConstants."))
    }

    @Test
    fun `declaredClasses of the root class are scanned`() {
        val result = dumpWith("android.hardware.bydauto.BYDAutoFeatureIds" to FakeOuter::class.java)
        assertTrue("root class field", result.contains("FakeOuter.OUTER_INT=10"))
        assertTrue("inner class field", result.contains("Inner.INNER_LONG=20"))
    }

    @Test
    fun `resolver returning null for one class does not throw and still produces the other`() {
        // One class absent from firmware (common on non-BYD ROM).
        val result = dumpWith(
            // "android.hardware.bydauto.BYDAutoFeatureIds" deliberately omitted → resolver returns null
            "android.hardware.bydauto.BYDAutoConstants" to FakeConstants::class.java,
        )
        // Other class still produced
        assertTrue("Constants class still collected", result.contains("FakeConstants.ALPHA=1"))
        // No exception thrown — test passes if we reach here
    }

    @Test
    fun `throwing resolver (ClassNotFoundException) for one class still produces the other`() {
        // C1 scenario: Class.forName throws ClassNotFoundException for an absent class.
        // The production daemon wraps it: classResolver = { runCatching { Class.forName(it) }.getOrNull() }
        // dumpFidsCore itself also wraps classResolver in runCatching, so a throwing resolver is
        // treated identically to a null-returning one — the class is skipped, no error.
        val throwingForFeatureIds: (String) -> Class<*>? = { name ->
            if (name == "android.hardware.bydauto.BYDAutoFeatureIds")
                throw ClassNotFoundException("simulated absent class on this firmware")
            FakeConstants::class.java
        }
        val result = dumpFidsCore(throwingForFeatureIds)
        assertTrue("Constants class still produced after sibling throws", result.contains("FakeConstants.ALPHA=1"))
    }

    @Test
    fun `empty string returned when all classes absent (non-BYD firmware)`() {
        val result = dumpFidsCore { null }
        assertEquals("", result)
    }
}
