package com.bydmate.app.split

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.platform.LegacyHeadUnit
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

/**
 * «Открывать при запуске» of the «Разделение экрана» section, every firmware: one switch for
 * whichever mechanism is chosen ([appSetsActive]). «Окна BYDMate» and «Родное разделение» open the
 * saved pair ([startSplit], SplitSessionManager.startLastPair), «Пополам 1:1» (Android 10 head
 * units) the first app set. The split switched off opens nothing.
 *
 * It opens at every start, whatever the speed: the head unit boots with the car and the service
 * gets to it some 30 s later, when the car is often already moving (2026-11-21: 10 and 15 km/h,
 * nothing opened). The service restarts itself seconds after a start there (the accessibility
 * recovery), so it opens once per head-unit boot ([bootCount], Settings.Global.BOOT_COUNT): every
 * car start boots the head unit anew, the recovery restart is the same boot. A ten-minute window
 * ([REPEAT_WINDOW_MS]) blocked a real car start 90 s after an earlier opening (2026-11-21 21:35);
 * it is kept only where the boot count cannot be read. The BYD camera on screen (reverse,
 * parking) is waited out first, so the split never lands beside it.
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
    /** The head unit's boot number, null when it cannot be read. */
    private val bootCount: () -> Int? = { null },
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
        bootCount = { readBootCount(context) },
    )

    /**
     * [OFF]: this switch or the split's is off; [NO_SET]: nothing to open (no set, or no saved pair for
     * the main method); [CAMERA]: the BYD camera did not close within AppSetLauncher.CAMERA_WAIT_MS.
     */
    enum class Result { OFF, NO_SET, REPEAT, CAMERA, STARTED, FAILED }

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
        val boot = bootCount()
        if (openedAlready(boot)) return trace(mode, Result.REPEAT)
        pause(START_DELAY_MS)
        if (!launcher.cameraClosed()) return trace(mode, Result.CAMERA)
        // Marked before the launch: a second service start must not open it twice, whatever the outcome.
        prefs?.edit()?.putLong(KEY_LAST_MS, now())?.putInt(KEY_LAST_BOOT, boot ?: -1)?.apply()
        return trace(mode, if (set != null) openSet(set) else openSplitPair())
    }

    /** Opened in this boot already; without a boot number, within [REPEAT_WINDOW_MS]. */
    private fun openedAlready(boot: Int?): Boolean {
        if (boot != null) return prefs?.getInt(KEY_LAST_BOOT, -1) == boot
        val last = prefs?.getLong(KEY_LAST_MS, 0L) ?: 0L
        return last > 0L && now() - last in 0 until REPEAT_WINDOW_MS
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
        const val KEY_LAST_BOOT = "last_boot"

        /** Settings.Global.BOOT_COUNT, null when the head unit does not give it. */
        private fun readBootCount(context: Context): Int? =
            kotlin.runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) }
                .getOrNull()?.takeIf { it >= 0 }
        const val KEY_ENABLED = "enabled"

        /** Without a boot number: the accessibility recovery restarts the service ~13 s after a start; this outlasts it. */
        const val REPEAT_WINDOW_MS = 10 * 60 * 1000L

        /** Lets the head unit finish booting and the recovery restart pass before the launch. */
        const val START_DELAY_MS = 15_000L
    }
}
