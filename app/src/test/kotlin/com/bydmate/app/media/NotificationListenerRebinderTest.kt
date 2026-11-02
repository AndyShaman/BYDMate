package com.bydmate.app.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #325: rebind = disallow/allow the listener, with hooks that let the media bridge re-register. */
class NotificationListenerRebinderTest {

    private val calls = mutableListOf<String>()

    private class Hook(val calls: MutableList<String>, val failBefore: Boolean = false, val failAfter: Boolean = false) :
        RebindHook {
        override fun beforeRebind() { calls += "before"; if (failBefore) error("boom") }
        override fun afterRebind() { calls += "after"; if (failAfter) error("boom") }
    }

    private fun rebinder(disallow: Boolean = true, allows: List<Boolean> = listOf(true), hook: RebindHook = Hook(calls)): NotificationListenerRebinder {
        val queue = allows.toMutableList()
        return NotificationListenerRebinder(
            disallow = { calls += "disallow"; disallow },
            allow = { calls += "allow"; if (queue.size > 1) queue.removeAt(0) else queue.first() },
            sleep = { calls += "sleep${it}" },
            hooks = listOf(hook),
        )
    }

    @Test fun `order is before, disallow, sleep, allow, after`() {
        assertTrue(rebinder().rebind("test"))
        assertEquals(listOf("before", "disallow", "sleep200", "allow", "after"), calls)
    }

    @Test fun `failed disallow skips allow and sleep but still runs after`() {
        assertFalse(rebinder(disallow = false).rebind("test"))
        assertEquals(listOf("before", "disallow", "after"), calls)
    }

    @Test fun `failed allow is retried once after another sleep`() {
        assertTrue(rebinder(allows = listOf(false, true)).rebind("test"))
        assertEquals(listOf("before", "disallow", "sleep200", "allow", "sleep200", "allow", "after"), calls)
    }

    @Test fun `allow failing twice returns false and still runs after`() {
        assertFalse(rebinder(allows = listOf(false, false)).rebind("test"))
        assertEquals("after", calls.last())
        assertEquals(2, calls.count { it == "allow" })
    }

    @Test fun `third rebind in a process does nothing`() {
        val r = rebinder()
        assertTrue(r.rebind("a"))
        assertTrue(r.rebind("b"))
        assertEquals(2, r.attempts)
        calls.clear()
        assertFalse(r.rebind("c"))
        assertTrue(calls.isEmpty())
        assertEquals(NotificationListenerRebinder.MAX_ATTEMPTS, r.attempts)
    }

    @Test fun `a throwing beforeRebind does not stop the rebind`() {
        assertTrue(rebinder(hook = Hook(calls, failBefore = true)).rebind("test"))
        assertEquals(listOf("before", "disallow", "sleep200", "allow", "after"), calls)
    }

    @Test fun `a throwing afterRebind does not change the result`() {
        assertTrue(rebinder(hook = Hook(calls, failAfter = true)).rebind("test"))
        assertFalse(rebinder(disallow = false, hook = Hook(calls, failAfter = true)).rebind("test"))
    }

    @Test fun `constants`() {
        assertEquals(2, NotificationListenerRebinder.MAX_ATTEMPTS)
        assertEquals(200L, NotificationListenerRebinder.GAP_MS)
    }

    // H6: ClusterMusicBridge needs a Context, Hilt deps and the firmware probe, so it cannot be built in a JVM test.
    // The re-registration condition is tested instead; the poll registers a listener only when none is held.
    @Test fun `bridge re-registers only when no listener is held and not ui7`() {
        assertTrue(shouldAddSessionsListener(ui7 = false, listenerRegistered = false))
        assertFalse(shouldAddSessionsListener(ui7 = false, listenerRegistered = true))
        assertFalse(shouldAddSessionsListener(ui7 = true, listenerRegistered = false))
    }
}
