package com.bydmate.app.data.settings

import android.content.Context
import android.util.Log
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.runBlocking

/** What the lock keeps: whether the user turned it on, and the value it replaced. */
interface TimezoneLockStore {
    var enabled: Boolean
    var previous: String?
}

/**
 * #379: near the China border the network time zone detector (NITZ) keeps switching the head unit
 * to Beijing time. Settings.Global `auto_time_zone` = 0 makes Android ignore those suggestions; the
 * user picks the zone once in the car's own settings. Off by default: nothing is written unless
 * the user turned it on, and turning it off puts back the value it found. [read] and [write] go
 * through the helper daemon and block: call off the main thread.
 */
class TimezoneLock(
    private val read: () -> String?,
    private val write: (String) -> Boolean,
    private val store: TimezoneLockStore,
) {
    /** Writes 0 and keeps the value it replaced; true only when the read-back shows 0, else puts it back. */
    fun enable(): Boolean {
        // A repeated enable must not take our own 0 for the user's value.
        val current = read()
        val previous = (if (store.enabled) store.previous else null) ?: current ?: ANDROID_DEFAULT
        val written = write(LOCKED)
        val readBack = read()
        val ok = written && readBack == LOCKED
        Log.i(TAG, "enable: current=$current previous=$previous written=$written readBack=$readBack ok=$ok")
        if (ok) {
            store.previous = previous
            store.enabled = true
        } else if (written && !store.enabled) {
            // The 0 may have landed while the switch stays off: put back what was there.
            val restored = write(current ?: ANDROID_DEFAULT)
            Log.w(TAG, "enable: read-back not 0, restored ${current ?: ANDROID_DEFAULT} ok=$restored")
        }
        return ok
    }

    /** Puts back the value [enable] replaced (Android's default 1 when none was kept). */
    fun disable(): Boolean {
        val target = store.previous ?: ANDROID_DEFAULT
        val ok = write(target)
        Log.i(TAG, "disable: restore=$target ok=$ok")
        if (ok) {
            store.enabled = false
            store.previous = null
        }
        return ok
    }

    /** Once per service start with the helper up: a factory reset or a firmware update may have
     *  turned the detector back on. */
    fun onServiceStart() {
        if (!store.enabled) {
            Log.i(TAG, "service start: lock off, nothing touched")
            return
        }
        val current = read()
        if (current == LOCKED) {
            Log.i(TAG, "service start: lock on, auto_time_zone=$current, no write")
            return
        }
        val ok = write(LOCKED)
        Log.i(TAG, "service start: lock on, auto_time_zone=$current, rewrote 0 ok=$ok")
    }

    companion object {
        private const val TAG = "TimezoneLock"
        const val KEY = "auto_time_zone"
        private const val LOCKED = "0"
        private const val ANDROID_DEFAULT = "1"

        /** The lock over the helper daemon's Settings.Global channel and its own prefs file. */
        fun create(context: Context, helper: HelperClient): TimezoneLock = TimezoneLock(
            read = { runBlocking { helper.getGlobalSetting(KEY) }?.toString() },
            write = { value -> runBlocking { helper.putGlobalSetting(KEY, value.toInt()) } },
            store = TimezoneLockPrefs(context),
        )
    }
}

/** Device-local on purpose (not in the backup): the kept value belongs to this head unit. */
class TimezoneLockPrefs(context: Context) : TimezoneLockStore {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, false)
        set(value) { prefs.edit().putBoolean(KEY_ENABLED, value).apply() }

    override var previous: String?
        get() = prefs.getString(KEY_PREVIOUS, null)
        set(value) { prefs.edit().putString(KEY_PREVIOUS, value).apply() }

    companion object {
        const val PREFS_NAME = "timezone_lock"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_PREVIOUS = "previous"
    }
}
