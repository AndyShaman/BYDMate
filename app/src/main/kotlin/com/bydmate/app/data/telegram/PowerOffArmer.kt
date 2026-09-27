package com.bydmate.app.data.telegram

import android.content.Context
import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App side of the power-off report (3.19, phase B). While the car is on and the report is switched
 * on, keeps the daemon armed with the current report ([TelegramReporter.powerOffReport]): checked
 * every [REFRESH_MS], re-armed only when the text changed or the daemon lost it, at once when the
 * settings change; disarmed (retried until the daemon confirms) when the report is switched off or
 * the bot disconnected. Each new text goes to disk ([PowerOffStore], the last two), and a heartbeat
 * is written at most every [HEARTBEAT_EVERY_MS].
 *
 * On the first start of the process (the app dies at every power-off), after the helper bootstrap,
 * it settles the stored reports by the daemon's last fired outcome (captured before a replace by
 * [PowerOffOutcomeCapture], else asked live):
 * - fired for one of them: sent -> forgotten; failed -> that report re-sent with the daemon's
 *   power-off time; still sending -> asked again every [SENDING_POLL_MS] for [SENDING_WAIT_MS],
 *   never sent in parallel with the daemon.
 * - nothing fired (reboot, old or missing daemon, a listener that never fired): no proof of a
 *   power-off, so only a gap over [STOP_GAP_MS] since the heartbeat counts as a real stop, and the
 *   newest report goes out under the neutral «последнее состояние машины на HH:MM» header.
 *   A shorter gap is an app restart on a running car: dropped.
 * Re-sent reports carry the outbox's «(записано в HH:MM)» mark when they go out late.
 *
 * Log tag `TgReport`; ids, lengths and states only, never the token, the chat or the text.
 */
@Singleton
@Suppress("TooManyFunctions") // start-up settle, arming loop and disarm, kept in one place
class PowerOffArmer @Inject constructor(
    @ApplicationContext context: Context,
    private val reporter: TelegramReporter,
    private val helper: HelperClient,
    private val settings: SettingsRepository,
    private val state: PowerOffArmState,
) {
    companion object {
        private const val TAG = "TgReport"
        const val REFRESH_MS = 10_000L
        const val HEARTBEAT_EVERY_MS = 60_000L

        /** An app gone this long was a real stop: the quickest power cycle plus restart is far shorter. */
        const val STOP_GAP_MS = 3 * 60_000L

        /** A report the daemon is still sending (the car was switched on again at once) is asked again. */
        const val SENDING_POLL_MS = 10_000L
        const val SENDING_WAIT_MS = 2 * 60_000L

        /** Arm lines on every change of verdict, else at most this often (the text moves every tick). */
        const val ARM_LOG_EVERY_MS = 60_000L
    }

    /** Test seams. */
    internal var store: PowerOffStore = PrefsPowerOffStore(context)
    internal var clock: () -> Long = System::currentTimeMillis
    internal var pause: suspend (Long) -> Unit = { delay(it) }

    private val checked = AtomicBoolean(false)
    private val bootstrapped = CompletableDeferred<Unit>()
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    // Owned by the loop in [run].
    private var current: PowerOffReport? = null
    private var disarmPending = true
    private var disarmFailLogged = false
    private var lastHeartbeat = 0L
    private var lastArmLog = 0L

    /** The helper bootstrap ran (whatever its result): the start-up check and the loop may start. */
    fun bootstrapAttempted() {
        bootstrapped.complete(Unit)
    }

    /** Start-up check, then the arming loop, for the service's lifetime. */
    suspend fun run() {
        bootstrapped.await()
        coroutineScope {
            // Once per process: a service restart inside a live process is no power cycle.
            if (checked.compareAndSet(false, true)) {
                guarded("check") { settleLastCycle { wait -> launch { guarded("wait") { wait() } } } }
            }
            launch { settingsChanges().collect { pokes.trySend(Unit) } }
            while (true) {
                guarded("refresh") { refresh() }
                withTimeoutOrNull(REFRESH_MS) { pokes.receive() }
            }
        }
    }

    // --- start-up check ---

    private suspend fun settleLastCycle(background: (suspend () -> Unit) -> Unit) {
        val records = store.records()
        val captured = store.takeCaptured()
        if (records.isEmpty()) return
        if (!settings.isTgReportOffEnabled()) {
            store.clearRecords()
            Log.i(TAG, "offreport outcome ids=${ids(records)} action=drop reason=report_off")
            return
        }
        val live = helper.offReportStatus(records.first().id)
        live?.let { state.note(it) }
        val fired = listOfNotNull(captured, live?.last).firstOrNull { o -> records.any { it.id == o.id } }
        if (fired == null) {
            settleWithoutProof(records.first(), noProofReason(live))
            return
        }
        val record = records.first { it.id == fired.id }
        when {
            fired.state == OffReportState.SENT -> {
                store.clearRecords()
                Log.i(
                    TAG,
                    "offreport outcome id=${fired.id} state=sent attempts=${fired.attempts} " +
                        "after_ms=${fired.sentAtMs - fired.powerOffMs} action=drop",
                )
            }
            // Live and still sending: the daemon may yet deliver it, so no parallel send.
            fired.state == OffReportState.SENDING && fired !== captured -> {
                store.clearRecords()
                Log.i(TAG, "offreport outcome id=${fired.id} state=sending action=wait")
                background { awaitSending(record) }
            }
            // Failed, or caught mid-send by a daemon replace: the power-off is proven.
            else -> resend(
                record,
                "${OffReportState.name(fired.state)} attempts=${fired.attempts} rc=${fired.rc}",
                text = TelegramReportBuilder.fillTime(record.text, fired.powerOffMs),
                createdMs = fired.powerOffMs,
            )
        }
    }

    /** No fired outcome for our reports: only a long absence of the app is taken as a stop. */
    private suspend fun settleWithoutProof(newest: ArmedRecord, reason: String) {
        val heartbeat = store.heartbeat()
        val gap = if (heartbeat > 0L) clock() - heartbeat else -1L
        if (heartbeat <= 0L || gap <= STOP_GAP_MS) {
            store.clearRecords()
            Log.i(TAG, "offreport outcome id=${newest.id} action=drop reason=$reason gap_s=${gap / 1000}")
            return
        }
        val at = maxOf(heartbeat, newest.createdMs)
        resend(
            newest,
            "$reason gap_s=${gap / 1000} header=last_state",
            text = TelegramReportBuilder.replaceHeader(newest.text, reporter.lastStateHeader(at)),
            createdMs = at,
        )
    }

    private suspend fun awaitSending(record: ArmedRecord) {
        var waited = 0L
        while (waited < SENDING_WAIT_MS) {
            pause(SENDING_POLL_MS)
            waited += SENDING_POLL_MS
            val q = helper.offReportStatus(record.id)?.queried ?: continue
            when (q.state) {
                OffReportState.SENT -> {
                    Log.i(TAG, "offreport outcome id=${record.id} state=sent after_wait_s=${waited / 1000} action=drop")
                    return
                }
                OffReportState.FAILED -> {
                    resend(
                        record, "failed attempts=${q.attempts} rc=${q.rc}",
                        text = TelegramReportBuilder.fillTime(record.text, q.powerOffMs),
                        createdMs = q.powerOffMs,
                    )
                    return
                }
                OffReportState.SENDING -> Unit
                else -> {
                    Log.w(TAG, "offreport outcome id=${record.id} state=${OffReportState.name(q.state)} while waiting: leave")
                    return
                }
            }
        }
        Log.w(TAG, "offreport outcome id=${record.id} still sending after ${SENDING_WAIT_MS / 1000}s: leave, no send")
    }

    /** Outbox first, then the stored reports go (synchronously), then a drain. */
    private suspend fun resend(record: ArmedRecord, reason: String, text: String, createdMs: Long) {
        Log.i(TAG, "offreport outcome id=${record.id} action=resend reason=$reason")
        reporter.enqueue(OutboxEntry(id = record.id, chatId = record.chatId, createdMs = createdMs, text = text))
        store.clearRecords()
        reporter.drainOutbox("power_off")
    }

    private suspend fun noProofReason(live: OffReportStatus?): String = when {
        live != null -> "daemon_${OffReportState.name(live.queried.state)}"
        helper.isAlive() -> {
            state.daemon = PowerOffArmState.DAEMON_OUTDATED
            Log.w(TAG, "offreport: daemon outdated")
            "daemon_outdated"
        }
        else -> "daemon_unreachable"
    }

    // --- arming loop ---

    private suspend fun refresh() {
        val now = clock()
        if (now - lastHeartbeat >= HEARTBEAT_EVERY_MS) {
            lastHeartbeat = now
            store.setHeartbeat(now)
        }
        val report = reporter.powerOffReport(current)
        if (report == null) {
            disarm()
            return
        }
        disarmPending = false
        val changed = report !== current
        if (changed) store.pushRecord(ArmedRecord(report.id, report.chatId, report.text, now))
        current = report
        if (changed || !daemonHolds(report.id)) arm(report, now)
    }

    private suspend fun arm(report: PowerOffReport, now: Long) {
        val ok = helper.offReportArm(report.id, report.token, report.chatId, report.text)
        val verdict = when {
            ok -> PowerOffArmState.DAEMON_OK
            helper.isAlive() -> PowerOffArmState.DAEMON_OUTDATED
            else -> PowerOffArmState.DAEMON_UNKNOWN
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

    /** False only when the daemon answers and does not hold [id] armed (it restarted). */
    private suspend fun daemonHolds(id: String): Boolean {
        val status = helper.offReportStatus(id) ?: return true
        state.note(status)
        val held = status.queried.state == OffReportState.ARMED
        if (!held) Log.i(TAG, "offreport daemon lost id=$id, re-arming")
        return held
    }

    /**
     * Report switched off or bot gone: the daemon must drop its copy (it holds the token). The local
     * state goes only once the daemon confirmed; until then every tick tries again. The first tick of
     * a process disarms too: the daemon may hold a report of the last one.
     */
    private suspend fun disarm() {
        if (current == null && !disarmPending) return
        disarmPending = true
        val had = current
        if (helper.offReportDisarm()) {
            current = null
            disarmPending = false
            disarmFailLogged = false
            store.clearRecords()
            state.armedAtMs = 0L
            Log.i(TAG, "offreport disarm id=${had?.id ?: "-"} rc=ok")
        } else if (had != null && !disarmFailLogged) {
            disarmFailLogged = true
            Log.w(TAG, "offreport disarm id=${had.id} rc=no_daemon, retrying every tick")
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

    private fun ids(records: List<ArmedRecord>): String = records.joinToString(",") { it.id }

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
