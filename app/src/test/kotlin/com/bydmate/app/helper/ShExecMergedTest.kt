package com.bydmate.app.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [shExecMerged] — the merged-stream runner that routes am/monkey stderr into stdout.
 * AOSP ActivityManagerShellCommand writes "Error:" and "Exception occurred while executing"
 * to stderr; this runner must capture them so the callers' `contains("Error")` checks work.
 */
class ShExecMergedTest {

    @Test
    fun `shExecMerged captures stderr - empty stdout plus stderr Error not swallowed as OK`() {
        // Script writes nothing to stdout but writes "Error: test" to stderr.
        // shExecMerged must merge stderr; plain shExec would return "OK" (stderr discarded).
        val result = shExecMerged("echo 'Error: test failure' >&2")
        assertTrue(
            "merged stderr must be returned, not swallowed as 'OK': got '$result'",
            result.contains("Error"),
        )
        assertFalse("result must not be the literal 'OK' sentinel", result == "OK")
    }

    @Test
    fun `shExecMerged returns OK sentinel when both stdout and stderr are empty`() {
        val result = shExecMerged("true")
        assertTrue("empty output must produce 'OK' sentinel", result == "OK")
    }

    @Test
    fun `shExecMerged passes positional args safely`() {
        // "$1" must be expanded to the arg, not treated as literal (no $-mangling risk).
        val result = shExecMerged("echo \"\$1\"", "hello world")
        assertTrue("positional arg must be echoed: got '$result'", result.contains("hello world"))
    }

    @Test
    fun `amShell stderr captured via shExecMerged`() {
        // amShell is the named val pinning all five prod sites to shExecMerged.
        // If any site is reverted to shExec, this test catches it: shExec discards stderr,
        // returning "OK" for an empty-stdout script instead of the "Error:" line.
        val result = amShell("echo 'Error: x' >&2", emptyList())
        assertTrue(
            "amShell must capture stderr via shExecMerged; got '$result'",
            result.contains("Error"),
        )
    }
}
