package com.bydmate.app.split

import android.content.Context
import android.content.SharedPreferences
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.platform.LegacyHeadUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Открывать при запуске» of the «Разделение экрана» section, every firmware: one switch for
 * whichever mechanism is chosen ([appSetsActive]). «Окна BYDMate» and «Родное разделение» open the
 * saved pair ([startSplit], SplitSessionManager.startLastPair), «Пополам 1:1» (Android 10 head
 * units) the first app set. The split switched off opens nothing.
 *
 * It opens once per car start, whatever the speed: the service gets to it some 30 s after the
 * start, when the car is often already moving (2026-11-21: 10 and 15 km/h, nothing opened).
 * A car start is the BOOT_COMPLETED that BootReceiver records ([recordCarStart]): the head unit
 * mostly sleeps rather than reboots when the car is off, and sends BOOT_COMPLETED again on waking
 * (2026-11-22: three car starts in 8 min, 18 h of uptime, Settings.Global.BOOT_COUNT unchanged).
 * Neither the boot count nor a ten-minute window (it blocked a car start 3 min after
 * an opening) tell car starts apart. The service restarting itself seconds after a start (the
 * accessibility recovery), an APK update or an unlock are not car starts and open nothing.
 * The BYD camera on screen (reverse, parking) is waited out first, so the split never lands beside it.
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
    /** The last car start ([recordCarStart]), 0 when none was recorded. */
    private val carStart: () -> Long = { prefs?.getLong(KEY_CAR_START, 0L) ?: 0L },
) {
    /** A car start that BOOT_COMPLETED brings to a running service must not open it twice. */
    private val running = AtomicBoolean(false)

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

    /**
     * [OFF]: this switch or the split's is off; [NO_SET]: nothing to open (no set, or no saved pair for
     * the main method); [NO_START]: no car start recorded yet (installed or updated since the last one);
     * [REPEAT]: opened for this car start already; [BUSY]: the same check is already waiting;
     * [CAMERA]: the BYD camera did not close within AppSetLauncher.CAMERA_WAIT_MS.
     */
    enum class Result { OFF, NO_SET, NO_START, REPEAT, BUSY, CAMERA, STARTED, FAILED }

    /** The switch. Before it existed each set had its own «open at start»: such a set turns it on. */
    fun isEnabled(): Boolean = prefs?.getBoolean(KEY_ENABLED, store.autostartSet() != null) ?: false

    fun setEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_ENABLED, enabled)?.apply()
    }

    suspend fun runIfDue(): Result {
        if (!isEnabled()) return Result.OFF
        val sets = appSetsActive(legacyHeadUnit(), store.setsOn.value)
        if (!sets && !splitFeatureEnabled()) return Result.OFF
        val mode = if (sets) "sets" else "pair"
        val set = if (sets) store.defaultSet() ?: return trace(mode, Result.NO_SET) else null
        val start = carStart()
        if (start <= 0L) return trace(mode, Result.NO_START)
        if (prefs?.getLong(KEY_LAST_START, 0L) == start) return trace(mode, Result.REPEAT)
        if (!running.compareAndSet(false, true)) return trace(mode, Result.BUSY)
        try {
            pause(START_DELAY_MS)
            if (!launcher.cameraClosed()) return trace(mode, Result.CAMERA)
            // Marked before the launch: a second service start must not open it twice, whatever the outcome.
            prefs?.edit()?.putLong(KEY_LAST_MS, now())?.putLong(KEY_LAST_START, start)?.apply()
            return trace(mode, if (set != null) openSet(set) else openSplitPair())
        } finally {
            running.set(false)
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
        /** The car start ([KEY_CAR_START]) the split was last opened for. */
        const val KEY_LAST_START = "last_start_ms"
        /** Epoch ms of the last car start, written by BootReceiver. */
        const val KEY_CAR_START = "car_start_ms"
        const val KEY_ENABLED = "enabled"

        /** BOOT_COMPLETED and QUICKBOOT_POWERON of one start can both come: closer than this they are one start. */
        const val SAME_START_MS = 60_000L

        /** BootReceiver, on BOOT_COMPLETED / QUICKBOOT_POWERON: a car start. False when it is the same start again. */
        fun recordCarStart(context: Context, nowMs: Long = System.currentTimeMillis()): Boolean =
            recordCarStart(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE), nowMs)

        internal fun recordCarStart(prefs: SharedPreferences, nowMs: Long): Boolean {
            val last = prefs.getLong(KEY_CAR_START, 0L)
            if (last > 0L && nowMs - last in 0 until SAME_START_MS) return false
            // commit, not apply: the service this broadcast starts reads it right away, maybe in a new process.
            return prefs.edit().putLong(KEY_CAR_START, nowMs).commit()
        }

        /** Lets the head unit finish booting and the recovery restart pass before the launch. */
        const val START_DELAY_MS = 15_000L
    }
}
