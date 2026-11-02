package com.bydmate.app.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #379: pin the time zone by turning Settings.Global auto_time_zone off (network zone detector ignored). */
class TimezoneLockTest {

    private class FakeStore(override var enabled: Boolean = false, override var previous: String? = null) :
        TimezoneLockStore

    /** Fake Settings.Global value; [honorsWrite] false models a write the system ignores. */
    private class Env(var value: String?, val honorsWrite: Boolean = true) {
        var reads = 0
        val writes = mutableListOf<String>()
        val read: () -> String? = { reads++; value }
        val write: (String) -> Boolean = { v ->
            writes += v
            if (honorsWrite) value = v
            true
        }
    }

    private fun lock(env: Env, store: TimezoneLockStore) = TimezoneLock(env.read, env.write, store)

    @Test fun `enable writes 0 once and keeps the previous value`() {
        val env = Env("1")
        val store = FakeStore()
        assertTrue(lock(env, store).enable())
        assertEquals(listOf("0"), env.writes)
        assertTrue(store.enabled)
        assertEquals("1", store.previous)
    }

    @Test fun `enable fails when the write is ignored`() {
        val env = Env("1", honorsWrite = false)
        val store = FakeStore()
        assertFalse(lock(env, store).enable())
        assertFalse(store.enabled)
        assertNull(store.previous)
    }

    @Test fun `enable with an unreadable value stores the Android default 1`() {
        val env = Env(null)
        val store = FakeStore()
        assertTrue(lock(env, store).enable())
        assertEquals("1", store.previous)
        assertEquals(listOf("0"), env.writes)
    }

    @Test fun `service start rewrites 0 when the system flipped it back`() {
        val env = Env("1")
        lock(env, FakeStore(enabled = true, previous = "1")).onServiceStart()
        assertEquals(listOf("0"), env.writes)
    }

    @Test fun `service start does not write when already 0`() {
        val env = Env("0")
        lock(env, FakeStore(enabled = true, previous = "1")).onServiceStart()
        assertTrue(env.writes.isEmpty())
    }

    @Test fun `service start does nothing when the lock is off`() {
        val env = Env("1")
        lock(env, FakeStore(enabled = false)).onServiceStart()
        assertEquals(0, env.reads)
        assertTrue(env.writes.isEmpty())
    }

    @Test fun `disable restores the previous value`() {
        val env = Env("0")
        val store = FakeStore(enabled = true, previous = "1")
        assertTrue(lock(env, store).disable())
        assertEquals(listOf("1"), env.writes)
        assertFalse(store.enabled)
        assertNull(store.previous)
    }

    @Test fun `disable without a stored previous writes 1`() {
        val env = Env("0")
        val store = FakeStore(enabled = true, previous = null)
        assertTrue(lock(env, store).disable())
        assertEquals(listOf("1"), env.writes)
        assertFalse(store.enabled)
    }

    @Test fun `enable restores the previous value when the read-back is null`() {
        val env = Env("1")
        var firstRead = true
        val store = FakeStore()
        val ok = TimezoneLock(
            read = { if (firstRead) { firstRead = false; env.value } else null },
            write = env.write,
            store = store,
        ).enable()
        assertFalse(ok)
        assertFalse(store.enabled)
        assertEquals(listOf("0", "1"), env.writes)
        assertEquals("1", env.value)
    }

    @Test fun `enable restores 1 when nothing was readable before and the read-back is not 0`() {
        val env = Env(null)
        val store = FakeStore()
        val ok = TimezoneLock({ null }, env.write, store).enable()
        assertFalse(ok)
        assertFalse(store.enabled)
        assertEquals(listOf("0", "1"), env.writes)
    }
}
