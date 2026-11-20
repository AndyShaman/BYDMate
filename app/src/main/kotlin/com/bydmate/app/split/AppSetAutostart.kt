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
 * «Открывать при запуске» of the «Разделение экрана» section on the Android 10 head units: one
 * switch for whichever method is chosen ([appSetsActive]). The main method opens its saved pair
 * ([startSplit], SplitSessionManager.startLastPair), the alternative one the first app set.
 *
 * Two guards keep it from firing when it should not. The service restarts itself seconds after a
 * start there (the accessibility recovery), so a split already started within [REPEAT_WINDOW_MS]
 * is not started again. And it starts only with the car standing: the speed must be read and be 0.
 */
@Singleton
class AppSetAutostart @Suppress("LongParameterList") constructor( // each guard injectable for the tests
    private val store: AppSetStore,
    private val launcher: AppSetLauncher,
    private val prefs: SharedPreferences?,
    private val legacyHeadUnit: () -> Boolean = { LegacyHeadUnit.isAndroid10 },
    private val splitFeatureEnabled: () -> Boolean = { false },
    private val startSplit: suspend () -> SplitStartResult? = { null },
    private val now: () -> Long = System::currentTimeMillis,
    private val pause: suspend (Long) -> Unit = { delay(it) },
) {
    @Inject constructor(
        store: AppSetStore,
        launcher: AppSetLauncher,
        splitPreferences: SplitPreferences,
        splitManager: dagger.Lazy<SplitSessionManager>,
        @ApplicationContext context: Context,
    ) : this(
        store, launcher, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
        splitFeatureEnabled = { splitPreferences.isFeatureEnabled() },
        startSplit = { splitManager.get().startLastPair() },
    )

    /** [OFF]: the switch is off; [NO_SET]: nothing to open (no set, or no saved pair for the main method). */
    enum class Result { NOT_LEGACY, OFF, NO_SET, REPEAT, MOVING, NO_SPEED, STARTED, FAILED }

    /** The switch. Before it existed each set had its own «open at start»: such a set turns it on. */
    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, store.autostartSet() != null) ?: false

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    /** [speedKmh] is the car's speed when known, null before the first reading. */
    suspend fun runIfDue(speedKmh: () -> Int?): Result {
        if (!legacyHeadUnit()) return Result.NOT_LEGACY
        if (!isEnabled()) return Result.OFF
        val sets = appSetsActive(legacyHeadUnit = true, splitFeatureEnabled = splitFeatureEnabled())
        val mode = if (sets) "alternative" else "main"
        val set = if (sets) store.defaultSet() ?: return trace(mode, Result.NO_SET) else null
        val last = prefs?.getLong(KEY_LAST_MS, 0L) ?: 0L
        if (last > 0L && now() - last in 0 until REPEAT_WINDOW_MS) return trace(mode, Result.REPEAT)
        pause(START_DELAY_MS)
        standingOrNot(speedKmh)?.let { return trace(mode, it) }
        // Marked before the launch: a second service start must not open it twice, whatever the outcome.
        prefs?.edit()?.putLong(KEY_LAST_MS, now())?.apply()
        return trace(mode, if (set != null) openSet(set) else openSplitPair())
    }

    /** Null when the car stands still; else why nothing opens (no speed reading, or moving). */
    private suspend fun standingOrNot(speedKmh: () -> Int?): Result? {
        var speed = speedKmh()
        var waited = 0L
        while (speed == null && waited < SPEED_WAIT_MS) {
            pause(SPEED_POLL_MS)
            waited += SPEED_POLL_MS
            speed = speedKmh()
        }
        return when {
            speed == null -> Result.NO_SPEED
            speed > 0 -> Result.MOVING
            else -> null
        }
    }

    private suspend fun openSet(set: AppSet): Result =
        if (launcher.launch(set) == AppSetLauncher.Outcome.OK) Result.STARTED else Result.FAILED

    private suspend fun openSplitPair(): Result = when (startSplit()) {
        null -> Result.NO_SET
        SplitStartResult.OK -> Result.STARTED
        else -> Result.FAILED
    }

    private fun trace(mode: String, result: Result): Result {
        Trace.event(TraceArea.APP, "appset-autostart", "mode" to mode, "result" to result.name.lowercase())
        return result
    }

    companion object {
        const val PREFS_NAME = "bydmate_app_sets_autostart"
        const val KEY_LAST_MS = "last_ms"
        const val KEY_ENABLED = "enabled"

        /** The accessibility recovery restarts the service ~13 s after a start; this outlasts it. */
        const val REPEAT_WINDOW_MS = 10 * 60 * 1000L

        /** Lets the head unit finish booting and the recovery restart pass before the launch. */
        const val START_DELAY_MS = 15_000L
        const val SPEED_WAIT_MS = 20_000L
        const val SPEED_POLL_MS = 1_000L
    }
}
