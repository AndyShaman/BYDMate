package com.bydmate.app.split

import android.content.Context
import android.content.SharedPreferences
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.platform.LegacyHeadUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Opens the set marked «open at start» when the service starts, on the Android 10 head units.
 *
 * Two guards keep it from firing when it should not. The service restarts itself seconds after a
 * start there (the accessibility recovery), so a set already started within [REPEAT_WINDOW_MS]
 * is not started again. And it starts only with the car standing: the speed must be read and be 0.
 */
@Singleton
class AppSetAutostart(
    private val store: AppSetStore,
    private val launcher: AppSetLauncher,
    private val prefs: SharedPreferences?,
    private val legacyHeadUnit: () -> Boolean = { LegacyHeadUnit.isAndroid10 },
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    @Inject constructor(store: AppSetStore, launcher: AppSetLauncher, @ApplicationContext context: Context) :
        this(store, launcher, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    enum class Result { NO_SET, NOT_LEGACY, REPEAT, MOVING, NO_SPEED, STARTED, FAILED }

    /** [speedKmh] is the car's speed when known, null before the first reading. */
    suspend fun runIfDue(speedKmh: () -> Int?): Result {
        if (!legacyHeadUnit()) return Result.NOT_LEGACY
        val set = store.autostartSet() ?: return Result.NO_SET
        val last = prefs?.getLong(KEY_LAST_MS, 0L) ?: 0L
        if (last > 0L && now() - last in 0 until REPEAT_WINDOW_MS) return trace(set, Result.REPEAT)
        pause(START_DELAY_MS)
        var speed = speedKmh()
        var waited = 0L
        while (speed == null && waited < SPEED_WAIT_MS) {
            pause(SPEED_POLL_MS)
            waited += SPEED_POLL_MS
            speed = speedKmh()
        }
        if (speed == null) return trace(set, Result.NO_SPEED)
        if (speed > 0) return trace(set, Result.MOVING)
        // Marked before the launch: a second service start must not open it twice, whatever the outcome.
        prefs?.edit()?.putLong(KEY_LAST_MS, now())?.apply()
        return trace(set, if (launcher.launch(set) == AppSetLauncher.Outcome.OK) Result.STARTED else Result.FAILED)
    }

    private fun trace(set: AppSet, result: Result): Result {
        Trace.event(TraceArea.APP, "appset-autostart", "set" to set.name, "result" to result.name.lowercase())
        return result
    }

    companion object {
        const val PREFS_NAME = "bydmate_app_sets_autostart"
        const val KEY_LAST_MS = "last_ms"

        /** The accessibility recovery restarts the service ~13 s after a start; this outlasts it. */
        const val REPEAT_WINDOW_MS = 10 * 60 * 1000L

        /** Lets the head unit finish booting and the recovery restart pass before the launch. */
        const val START_DELAY_MS = 15_000L
        const val SPEED_WAIT_MS = 20_000L
        const val SPEED_POLL_MS = 1_000L
    }
}
