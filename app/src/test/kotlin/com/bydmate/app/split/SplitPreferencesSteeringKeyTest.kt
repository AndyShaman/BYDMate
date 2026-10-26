package com.bydmate.app.split

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
}
