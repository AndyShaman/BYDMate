package com.bydmate.app.ui.widget

import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetPreferencesTest {

    private lateinit var store: MutableMap<String, Any?>
    private lateinit var prefs: WidgetPreferences

    @Before
    fun setUp() {
        store = mutableMapOf()
        prefs = WidgetPreferences(fakeSharedPrefs(store))
    }

    @Test
    fun `enabled defaults to false`() {
        assertFalse(prefs.isEnabled())
    }

    @Test
    fun `setEnabled true stores true`() {
        prefs.setEnabled(true)
        assertTrue(prefs.isEnabled())
    }

    @Test
    fun `setEnabled toggles and can be read back`() {
        prefs.setEnabled(true)
        prefs.setEnabled(false)
        assertFalse(prefs.isEnabled())
    }

    @Test
    fun `position defaults are 0 0 when unset`() {
        assertEquals(0, prefs.getX())
        assertEquals(0, prefs.getY())
    }

    @Test
    fun `savePosition stores both coordinates`() {
        prefs.savePosition(100, 250)
        assertEquals(100, prefs.getX())
        assertEquals(250, prefs.getY())
    }

    @Test
    fun `savePosition overwrites previous values`() {
        prefs.savePosition(10, 20)
        prefs.savePosition(300, 400)
        assertEquals(300, prefs.getX())
        assertEquals(400, prefs.getY())
    }

    @Test
    fun `resetPosition clears to zero`() {
        prefs.savePosition(100, 200)
        prefs.resetPosition()
        assertEquals(0, prefs.getX())
        assertEquals(0, prefs.getY())
    }

    // --- Minimal fake of SharedPreferences used by WidgetPreferences ---
    private fun fakeSharedPrefs(backing: MutableMap<String, Any?>): SharedPreferences {
        return object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = backing.toMutableMap()
            override fun getString(key: String, defValue: String?): String? = backing[key] as? String ?: defValue
            override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? =
                @Suppress("UNCHECKED_CAST") (backing[key] as? MutableSet<String>) ?: defValues
            override fun getInt(key: String, defValue: Int): Int = backing[key] as? Int ?: defValue
            override fun getLong(key: String, defValue: Long): Long = backing[key] as? Long ?: defValue
            override fun getFloat(key: String, defValue: Float): Float = backing[key] as? Float ?: defValue
            override fun getBoolean(key: String, defValue: Boolean): Boolean = backing[key] as? Boolean ?: defValue
            override fun contains(key: String): Boolean = backing.containsKey(key)
            override fun edit(): SharedPreferences.Editor = FakeEditor(backing)
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }
    }

    private class FakeEditor(
        private val backing: MutableMap<String, Any?>,
    ) : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clear = false
        override fun putString(key: String, value: String?): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor { pending[key] = values; return this }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor { pending[key] = value; return this }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor { pending[key] = value; return this }
        override fun remove(key: String): SharedPreferences.Editor { removals.add(key); return this }
        override fun clear(): SharedPreferences.Editor { clear = true; return this }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clear) backing.clear()
            removals.forEach { backing.remove(it) }
            pending.forEach { (k, v) -> backing[k] = v }
            pending.clear(); removals.clear(); clear = false
        }
    }
}
