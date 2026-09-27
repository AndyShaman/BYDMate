package com.bydmate.app.data.telegram

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App side of the power-off report (3.19, phase B). The helper daemon alone delivers the report,
 * at the power-off and later from its disk (see helper/offreport/OffReport); the app only keeps it
 * armed. While the car is on and the report is switched on, the current report
 * ([TelegramReporter.powerOffReport]) is checked every [REFRESH_MS] and armed when it changed, at
 * once when the settings change, and again every [REARM_MS] so a restarted daemon gets it back
 * (each arm also lets the daemon deliver what is pending). When the report is switched off or the
 * bot disconnected, the daemon is disarmed (it drops the pending reports too), retried every tick
 * until it confirms. The first tick of a process disarms too when the report is off: the daemon may
 * hold a report of the last one.
 *
 * Log tag `TgReport`; ids, lengths and states only, never the token, the chat or the text.
 */
@Singleton
class PowerOffArmer @Inject constructor(
    private val reporter: TelegramReporter,
    private val helper: HelperClient,
    private val settings: SettingsRepository,
    private val state: PowerOffArmState,
) {
    companion object {
        private const val TAG = "TgReport"
        const val REFRESH_MS = 10_000L

        /** A report unchanged this long is armed again: a daemon restarted meanwhile gets it back. */
        const val REARM_MS = 60_000L

        /** Arm lines on every change of verdict, else at most this often (the text moves every tick). */
        const val ARM_LOG_EVERY_MS = 60_000L
    }

    /** Test seam. */
    internal var clock: () -> Long = System::currentTimeMillis

    private val bootstrapped = CompletableDeferred<Unit>()
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    // Owned by the loop in [run].
    private var current: PowerOffReport? = null
    private var armedId: String? = null
    private var lastArmAt = 0L
    private var lastArmLog = 0L
    private var disarmPending = true
    private var disarmFailLogged = false

    /** The helper bootstrap ran (whatever its result): the loop may start. */
    fun bootstrapAttempted() {
        bootstrapped.complete(Unit)
    }

    /** The arming loop, for the service's lifetime. */
    suspend fun run() {
        bootstrapped.await()
        coroutineScope {
            launch { settingsChanges().collect { pokes.trySend(Unit) } }
            while (true) {
                guarded("refresh") { refresh() }
                withTimeoutOrNull(REFRESH_MS) { pokes.receive() }
            }
        }
    }

    private suspend fun refresh() {
        val report = reporter.powerOffReport(current)
        if (report == null) {
            disarm()
            return
        }
        disarmPending = false
        current = report
        val now = clock()
        if (report.id != armedId || now - lastArmAt >= REARM_MS) arm(report, now)
    }

    private suspend fun arm(report: PowerOffReport, now: Long) {
        val ok = helper.offReportArm(report.id, report.token, report.chatId, report.text, report.lateMark)
        val verdict = when {
            ok -> PowerOffArmState.DAEMON_OK
            helper.isAlive() -> PowerOffArmState.DAEMON_OUTDATED
            else -> PowerOffArmState.DAEMON_UNKNOWN
        }
        if (ok) {
            armedId = report.id
            lastArmAt = now
        } else {
            armedId = null
        }
        state.armedAtMs = if (ok) now else 0L
        if (verdict != state.daemon || now - lastArmLog >= ARM_LOG_EVERY_MS) {
            lastArmLog = now
            val rc = when (verdict) {
                PowerOffArmState.DAEMON_OK -> "ok"
                PowerOffArmState.DAEMON_OUTDATED -> "daemon outdated"
                else -> "daemon unreachable"
            }
            Log.i(TAG, "offreport arm id=${report.id} len=${report.text.length} rc=$rc")
        }
        state.daemon = verdict
    }

    /**
     * Report switched off or bot gone: the daemon must drop its copy (it holds the token) and the
     * pending reports. The local state goes only once the daemon confirmed; until then every tick
     * tries again.
     */
    private suspend fun disarm() {
        if (current == null && !disarmPending) return
        disarmPending = true
        val had = current
        if (helper.offReportDisarm()) {
            current = null
            armedId = null
            disarmPending = false
            disarmFailLogged = false
            state.armedAtMs = 0L
            Log.i(TAG, "offreport disarm id=${had?.id ?: "-"} rc=ok")
        } else if (had != null && !disarmFailLogged) {
            disarmFailLogged = true
            Log.w(TAG, "offreport disarm id=${had.id} rc=not_confirmed, retrying every tick")
        }
    }

    private fun settingsChanges(): Flow<List<String?>> = combine(
        settings.observeString(SettingsRepository.KEY_TG_REPORT_OFF_ENABLED),
        settings.observeString(SettingsRepository.KEY_TG_REPORT_OFF_FIELDS),
        settings.observeString(SettingsRepository.KEY_TG_BACKUP_TOKEN),
        settings.observeString(SettingsRepository.KEY_TG_BACKUP_CHAT_ID),
    ) { enabled, fields, token, chat -> listOf(enabled, fields, token, chat) }
        .distinctUntilChanged()
        .drop(1)

    /** A failure costs this step only; the loop and the service go on. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun guarded(step: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "offreport $step failed: ${e.javaClass.simpleName}")
        }
    }
}
