package com.bydmate.app.split

import android.content.Context
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SplitPreferencesSteeringKeyTest {
    private class Prefs(var enabled: Boolean, var key: Int) : SplitPreferences {
        override fun getLastPair(): SplitPair? = null
        override fun saveLastPair(pair: SplitPair) {}
        override fun clearLastPair() {}
        override fun isFeatureEnabled() = enabled
        override fun setFeatureEnabled(enabled: Boolean) { this.enabled = enabled }
        override fun getSteeringKeyCode() = key
        override fun setSteeringKeyCode(keyCode: Int) { key = keyCode }
    }

    @Test fun `the key is active only with the feature on and a key assigned`() {
        assertTrue(Prefs(enabled = true, key = 383).isSteeringKeyActive())
        assertFalse(Prefs(enabled = false, key = 383).isSteeringKeyActive())
        assertFalse(Prefs(enabled = true, key = 0).isSteeringKeyActive())
    }
    @Test fun `the disabled default has no key`() {
        assertFalse(DisabledSplitPreferences.isSteeringKeyActive())
    }

    @Test fun `companions default to none`() {
        assertEquals(emptySet<Int>(), Prefs(enabled = true, key = 383).getSteeringKeyCompanions())
        assertEquals(emptySet<Int>(), DisabledSplitPreferences.getSteeringKeyCompanions())
    }

    private fun newProcess(store: MutableMap<String, Any?>): SplitPreferencesImpl {
        val ctx = mockk<Context>()
        every { ctx.getSharedPreferences(SplitPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE) } returns
            fakeSplitSharedPrefs(store)
        return SplitPreferencesImpl(ctx)
    }

    @Test fun `companions survive a new process`() {
        val store = mutableMapOf<String, Any?>()
        newProcess(store).setSteeringKeyCompanions(setOf(327, 328))
        assertEquals(setOf(327, 328), newProcess(store).getSteeringKeyCompanions())
    }
    @Test fun `saving an empty set clears the companions`() {
        val store = mutableMapOf<String, Any?>()
        val prefs = newProcess(store)
        prefs.setSteeringKeyCompanions(setOf(327))
        prefs.setSteeringKeyCompanions(emptySet())
        assertEquals(emptySet<Int>(), newProcess(store).getSteeringKeyCompanions())
    }
    @Test fun `companions are stored apart from the key code`() {
        val store = mutableMapOf<String, Any?>()
        val prefs = newProcess(store)
        prefs.setSteeringKeyCode(304)
        prefs.setSteeringKeyCompanions(setOf(327))
        assertEquals(304, newProcess(store).getSteeringKeyCode())
    }
}
