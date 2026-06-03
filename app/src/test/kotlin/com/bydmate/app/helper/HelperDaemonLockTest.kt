package com.bydmate.app.helper

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JVM tests for acquireSingleOwnerLock.
 *
 * No Android runtime needed — java.nio.channels is enough.
 */
class HelperDaemonLockTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `first acquire on a fresh file returns non-null`() {
        val lockFile = tmp.newFile("test.lock").absolutePath
        val result = acquireSingleOwnerLock(lockFile)
        assertNotNull("Expected first lock to succeed", result)
        // Release the lock
        result!!.second.release()
        result.first.close()
    }

    @Test
    fun `second acquire while first is held returns null`() {
        val lockFile = tmp.newFile("test2.lock").absolutePath
        val first = acquireSingleOwnerLock(lockFile)
        assertNotNull("First lock must succeed for this test to be valid", first)
        try {
            val second = acquireSingleOwnerLock(lockFile)
            assertNull(
                "Second acquire must return null while first lock is held (same JVM: OverlappingFileLockException)",
                second
            )
        } finally {
            first!!.second.release()
            first.first.close()
        }
    }

    @Test
    fun `acquire succeeds again after releasing the first lock`() {
        val lockFile = File(tmp.root, "test3.lock").absolutePath
        val first = acquireSingleOwnerLock(lockFile)
        assertNotNull(first)
        first!!.second.release()
        first.first.close()

        val second = acquireSingleOwnerLock(lockFile)
        assertNotNull("Acquire after release must succeed", second)
        second!!.second.release()
        second.first.close()
    }
}
