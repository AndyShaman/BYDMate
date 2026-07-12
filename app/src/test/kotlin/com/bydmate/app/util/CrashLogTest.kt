package com.bydmate.app.util

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric tests for CrashLog — uses a real SharedPreferences instance
 * (Robolectric resets the sandbox filesystem between test methods, so no
 * state leaks across tests here).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CrashLogTest {

    private lateinit var context: Context

    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test fun `record writes an entry that read returns`() {
        CrashLog.record(context, RuntimeException("boom"))
        val entries = CrashLog.read(context)
        assertEquals(1, entries.size)
    }

    @Test fun `record more than 5 times evicts the oldest entries`() {
        repeat(7) { i -> CrashLog.record(context, RuntimeException("crash $i")) }
        val entries = CrashLog.read(context)
        assertEquals(5, entries.size)
        entries.forEach { entry ->
            assertTrue("must not contain evicted crash 0", !entry.contains("crash 0"))
            assertTrue("must not contain evicted crash 1", !entry.contains("crash 1"))
        }
    }

    @Test fun `read returns entries newest first`() {
        CrashLog.record(context, RuntimeException("first"))
        CrashLog.record(context, RuntimeException("second"))
        val entries = CrashLog.read(context)
        assertEquals(2, entries.size)
        assertTrue("newest entry must be first", entries[0].contains("second"))
        assertTrue("oldest entry must be last", entries[1].contains("first"))
    }

    @Test fun `entry contains exception class and first stack trace line`() {
        val throwable = RuntimeException("boom")
        CrashLog.record(context, throwable)
        val entry = CrashLog.read(context).first()
        assertTrue("must contain exception class name", entry.contains(throwable.javaClass.name))
        assertTrue(
            "must contain the first stack trace frame",
            entry.contains(throwable.stackTrace[0].toString())
        )
    }
}
