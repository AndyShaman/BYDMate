package com.bydmate.app.split

import android.content.SharedPreferences

fun fakeSplitSharedPrefs(backing: MutableMap<String, Any?>): SharedPreferences {
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
        override fun edit(): SharedPreferences.Editor = FakeSplitEditor(backing)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }
}

class FakeSplitEditor(
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
