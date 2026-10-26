package com.bydmate.app.split

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Two pairs (decision 11): the MENU pair is persisted under the legacy keys (narrow_pkg / wide_pkg /
 * narrow_side) and written only by the settings pickers; the SESSION (last-used) pair lives in memory
 * of one [SplitPreferencesImpl] instance (= one process). [SplitPreferences.getLastPair] is the
 * start-from-saved read: session pair if set, else menu pair.
 */
class SplitPreferencesMenuPairTest {
    private lateinit var store: MutableMap<String, Any?>
    private val menu = SplitPair("pkg.menu.narrow", "pkg.menu.wide", SplitSide.RIGHT)
    private val other = SplitPair("pkg.other.narrow", "pkg.other.wide", SplitSide.LEFT)

    /** A new instance over the same file = a new process. */
    private fun newProcess(): SplitPreferencesImpl {
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences(SplitPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE) } returns
            fakeSplitSharedPrefs(store)
        return SplitPreferencesImpl(ctx)
    }

    @Before fun setUp() { store = mutableMapOf() }

    @Test fun `existing keys are read as the menu pair`() {
        store[SplitPreferencesImpl.KEY_NARROW_PKG] = "pkg.menu.narrow"
        store[SplitPreferencesImpl.KEY_WIDE_PKG] = "pkg.menu.wide"
        store[SplitPreferencesImpl.KEY_NARROW_SIDE] = "right"
        val prefs = newProcess()
        assertEquals(menu, prefs.getMenuPair())
        assertEquals(menu, prefs.getLastPair())
    }

    @Test fun `saving the menu pair writes the legacy keys`() {
        newProcess().saveMenuPair(menu)
        assertEquals("pkg.menu.narrow", store[SplitPreferencesImpl.KEY_NARROW_PKG])
        assertEquals("pkg.menu.wide", store[SplitPreferencesImpl.KEY_WIDE_PKG])
        assertEquals("right", store[SplitPreferencesImpl.KEY_NARROW_SIDE])
    }

    @Test fun `a launch with other apps changes the next start but not the menu pair`() {
        val prefs = newProcess()
        prefs.saveMenuPair(menu)
        prefs.saveLastPair(other)
        assertEquals(other, prefs.getLastPair())
        assertEquals(menu, prefs.getMenuPair())
        assertEquals("pkg.menu.narrow", store[SplitPreferencesImpl.KEY_NARROW_PKG])
    }

    @Test fun `a fresh process opens the menu pair again`() {
        val first = newProcess()
        first.saveMenuPair(menu)
        first.saveLastPair(other)
        val afterRestart = newProcess()
        assertEquals(menu, afterRestart.getLastPair())
        assertNull(afterRestart.getSessionPair())
    }

    @Test fun `empty menu plus a successful first launch persists it as the menu pair`() {
        val prefs = newProcess()
        assertNull(prefs.getMenuPair())
        prefs.saveLastPair(other)
        assertEquals(other, prefs.getMenuPair())
    }

    @Test fun `the first launch menu pair survives a restart`() {
        newProcess().saveLastPair(other)
        assertEquals(other, newProcess().getMenuPair())
    }

    @Test fun `a launch never overwrites a non-empty menu pair`() {
        val prefs = newProcess()
        prefs.saveMenuPair(menu)
        prefs.saveLastPair(other)
        assertEquals(menu, newProcess().getMenuPair())
    }

    @Test fun `saving the menu pair clears the session pair`() {
        val prefs = newProcess()
        prefs.saveLastPair(other)
        prefs.saveMenuPair(menu)
        assertNull(prefs.getSessionPair())
        assertEquals(menu, prefs.getLastPair())
    }

    @Test fun `clearing the menu pair clears the session pair too`() {
        val prefs = newProcess()
        prefs.saveMenuPair(menu)
        prefs.saveLastPair(other)
        prefs.clearMenuPair()
        assertNull(prefs.getMenuPair())
        assertNull(prefs.getSessionPair())
        assertNull(prefs.getLastPair())
    }

    @Test fun `with no pair at all there is nothing to start`() {
        assertNull(newProcess().getLastPair())
    }
}
