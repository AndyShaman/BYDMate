package com.bydmate.app.helper

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.UndeclaredThrowableException

/**
 * Issue #64: the daemon printed "ERR: addService null" because addService is called
 * reflectively and InvocationTargetException.message is always null. The report must show
 * the unwrapped cause instead.
 */
class BootstrapFailureReportTest {

    @Test fun `unwraps InvocationTargetException to its cause`() {
        val cause = SecurityException("addService not allowed for uid 2000")
        val report = describeBootstrapFailure(InvocationTargetException(cause))

        assertTrue("must name the real class, got: $report", report.contains("java.lang.SecurityException"))
        assertTrue("must carry the real message, got: $report", report.contains("addService not allowed for uid 2000"))
        assertTrue("must note the wrapper, got: $report", report.contains("InvocationTargetException"))
        assertFalse("must not degrade to a bare null, got: $report", report.startsWith("null"))
    }

    @Test fun `unwraps a nested reflection wrapper chain`() {
        val cause = IllegalStateException("service already registered")
        val report = describeBootstrapFailure(
            InvocationTargetException(UndeclaredThrowableException(cause))
        )

        assertTrue(report.contains("java.lang.IllegalStateException"))
        assertTrue(report.contains("service already registered"))
    }

    @Test fun `a plain exception is reported as itself`() {
        val report = describeBootstrapFailure(SecurityException("denied"))

        assertTrue(report.contains("java.lang.SecurityException: denied"))
        assertFalse("no wrapper note when there was no wrapper", report.contains("wrapped in"))
    }

    @Test fun `a cause without a message still names the class`() {
        val report = describeBootstrapFailure(InvocationTargetException(NullPointerException()))

        assertTrue(report.contains("java.lang.NullPointerException"))
        assertTrue("absent message must be explicit, got: $report", report.contains("(no message)"))
    }

    @Test fun `report carries a bounded stack tail`() {
        val cause = runCatching { error("boom") }.exceptionOrNull()!!
        val report = describeBootstrapFailure(InvocationTargetException(cause), frames = 3)

        val frameLines = report.lines().filter { it.trimStart().startsWith("at ") }
        assertTrue("expected up to 3 frames, got ${frameLines.size}", frameLines.size in 1..3)
    }

    @Test fun `a wrapper with no cause does not loop`() {
        val report = describeBootstrapFailure(InvocationTargetException(null))

        assertTrue(report.contains("InvocationTargetException"))
    }

    @Test fun `selinux context is read and NUL-stripped`() {
        val f = File.createTempFile("attr", "current")
        f.writeText("u:r:shell:s0" + Char(0))

        assertTrue(readSelinuxContext(f.absolutePath) == "u:r:shell:s0")
        f.delete()
    }

    @Test fun `unreadable selinux attr degrades to a marker`() {
        val missing = File(System.getProperty("java.io.tmpdir"), "no-such-attr-file-${System.nanoTime()}")

        assertTrue(readSelinuxContext(missing.absolutePath) == "(unavailable)")
    }
}
