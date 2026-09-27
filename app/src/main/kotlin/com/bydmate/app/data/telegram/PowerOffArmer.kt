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
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The report the app last handed to the daemon, kept on disk: what the next start re-sends when
 * the daemon could not. toString never shows the chat or the text.
 */
data class ArmedRecord(val id: String, val chatId: Long, val text: String, val armedAtMs: Long) {
    override fun toString(): String = "ArmedRecord(id=$id, len=${text.length})"
}

interface ArmedStore {
    fun load(): ArmedRecord?
    fun save(record: ArmedRecord)
    fun clear()
}

/**
 * App side of the power-off report (3.19, phase B). While the car is on and the report is switched
 * on, keeps the daemon armed with the current report ([TelegramReporter.powerOffReport]): checked
 * every [REFRESH_MS], re-armed only when the text changed or the daemon lost it, at once when the
 * settings change; disarmed when the report is switched off or the bot disconnected. Every report
 * is also kept on disk ([ArmedStore]).
 *
 * On the first start of the process (the app dies at every power-off) it asks the daemon how that
 * stored report fared: sent -> forgotten; failed, lost by the daemon (reboot) or no daemon to ask ->
 * queued in the outbox with its power-off time (the daemon's, else the last refresh). Asked before
 * the helper bootstrap, which replaces a daemon of another version and would lose its answer; asked
 * again after the bootstrap when no daemon answered.
 *
 * Log tag `TgReport`; ids, lengths and states only, never the token, the chat or the text.
 */
@Singleton
@Suppress("TooManyFunctions") // start-up check, arming loop and the store, kept in one place
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

        /** A report still being sent (the car was switched on again within 8 s) is asked again. */
        const val SENDING_POLL_MS = 1_000L
        const val SENDING_POLLS = 10

        private const val PREFS = "tg_offreport"
        private const val KEY_ARMED = "armed"
    }

    /** Test seams. */
    internal var store: ArmedStore = PrefsArmedStore(context)
    internal var clock: () -> Long = System::currentTimeMillis
    internal var pause: suspend (Long) -> Unit = { delay(it) }

    private sealed interface Answer {
        data class Known(val status: OffReportStatus) : Answer
        /** Alive, but it does not know the verbs: a daemon of an older build. */
        data object Outdated : Answer
        data object Unreachable : Answer
    }

    private val checked = AtomicBoolean(false)
    private val bootstrapped = CompletableDeferred<Unit>()
    @Volatile private var askAfterBootstrap: ArmedRecord? = null
    private val pokes = Channel<Unit>(Channel.CONFLATED)

    // Owned by the loop in [run].
    private var current: PowerOffReport? = null
    private var disarmChecked = false

    /**
     * Start-up check against the daemon that is up right now, before the helper bootstrap may
     * replace it. Once per process: a service restart inside a live process is no power cycle.
     */
    suspend fun checkBeforeBootstrap() {
        if (!checked.compareAndSet(false, true)) return
        guarded("check") {
            val record = store.load() ?: return@guarded
            if (!settings.isTgReportOffEnabled()) {
                store.clear()
                Log.i(TAG, "offreport outcome id=${record.id} action=drop reason=report_off")
                return@guarded
            }
            val answer = ask(record.id)
            if (answer == Answer.Unreachable) {
                Log.i(TAG, "offreport outcome id=${record.id} daemon unreachable, asking after the bootstrap")
                askAfterBootstrap = record
            } else {
                settle(record, answer)
            }
        }
    }

    /** The helper bootstrap ran (whatever its result): the arming loop may start. */
    fun bootstrapAttempted() {
        bootstrapped.complete(Unit)
    }

    /** The arming loop, for the service's lifetime. Starts once [bootstrapAttempted] was called. */
    suspend fun run() {
        bootstrapped.await()
        askAfterBootstrap?.let { record ->
            askAfterBootstrap = null
            guarded("check") { settle(record, ask(record.id)) }
        }
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
        val now = clock()
        store.save(ArmedRecord(report.id, report.chatId, report.text, now))
        val changed = report !== current
        current = report
        disarmChecked = false
        if (changed || !daemonHolds(report.id)) arm(report, now)
    }

    private suspend fun arm(report: PowerOffReport, now: Long) {
        val ok = helper.offReportArm(report.id, report.token, report.chatId, report.text)
        val verdict = when {
            ok -> PowerOffArmState.DAEMON_OK
            helper.isAlive() -> PowerOffArmState.DAEMON_OUTDATED
            else -> PowerOffArmState.DAEMON_UNKNOWN
        }
        if (ok) {
            state.armedAtMs = now
            Log.i(TAG, "offreport arm id=${report.id} len=${report.text.length} rc=ok")
        } else {
            state.armedAtMs = 0L
            // Once per change of verdict: the text changes every tick while driving.
            if (verdict != state.daemon) {
                val why = if (verdict == PowerOffArmState.DAEMON_OUTDATED) "daemon outdated" else "daemon unreachable"
                Log.w(TAG, "offreport arm id=${report.id} rc=$why: the report goes out at the next start")
            }
        }
        state.daemon = verdict
    }

    /** False only when the daemon answers and does not hold [id] armed (it restarted). */
    private suspend fun daemonHolds(id: String): Boolean {
        val answer = ask(id) as? Answer.Known ?: return true
        val held = answer.status.queried.state == OffReportState.ARMED
        if (!held) Log.i(TAG, "offreport daemon lost id=$id, re-arming")
        return held
    }

    /** Report switched off or bot gone: drop the daemon's copy and the one on disk. */
    private suspend fun disarm() {
        val had = current
        // The first tick of a process disarms too: the daemon may hold a report of the last one.
        if (had == null && disarmChecked) return
        disarmChecked = true
        current = null
        store.clear()
        state.armedAtMs = 0L
        val ok = helper.offReportDisarm()
        if (had != null || ok) Log.i(TAG, "offreport disarm id=${had?.id ?: "-"} rc=${if (ok) "ok" else "no_daemon"}")
    }

    private suspend fun ask(id: String): Answer {
        val status = helper.offReportStatus(id)
        if (status != null) {
            status.last?.let { state.last = it }
            return Answer.Known(status)
        }
        return if (helper.isAlive()) Answer.Outdated else Answer.Unreachable
    }

    /** What to do with the stored report of the last power cycle, from the daemon's answer. */
    private suspend fun settle(record: ArmedRecord, first: Answer) {
        var answer = first
        var polls = 0
        while (answer is Answer.Known && answer.status.queried.state == OffReportState.SENDING && polls < SENDING_POLLS) {
            pause(SENDING_POLL_MS)
            answer = ask(record.id)
            polls++
        }
        when (answer) {
            Answer.Outdated -> {
                state.daemon = PowerOffArmState.DAEMON_OUTDATED
                Log.w(TAG, "offreport outcome id=${record.id}: daemon outdated")
                resend(record, record.armedAtMs, "daemon_outdated")
            }
            Answer.Unreachable -> resend(record, record.armedAtMs, "daemon_unreachable")
            is Answer.Known -> settleKnown(record, answer.status)
        }
    }

    private suspend fun settleKnown(record: ArmedRecord, status: OffReportStatus) {
        val q = status.queried
        when (q.state) {
            OffReportState.SENT -> {
                store.clear()
                Log.i(
                    TAG,
                    "offreport outcome id=${record.id} state=sent attempts=${q.attempts} " +
                        "after_ms=${q.sentAtMs - q.powerOffMs} action=drop",
                )
            }
            // Armed and never fired while the listener is live: the app restarted on a running
            // car, the report is still to come. With no listener the daemon cannot tell.
            OffReportState.ARMED -> if (status.listening > 0) {
                Log.i(TAG, "offreport outcome id=${record.id} state=armed action=keep")
            } else {
                resend(record, record.armedAtMs, "armed_no_listener")
            }
            OffReportState.FAILED, OffReportState.SENDING -> {
                val at = q.powerOffMs.takeIf { it > 0L } ?: record.armedAtMs
                resend(record, at, "${OffReportState.name(q.state)} attempts=${q.attempts} rc=${q.rc}")
            }
            else -> resend(record, record.armedAtMs, "unknown_to_daemon")
        }
    }

    /** Into the outbox with the time filled in; the header already says when, so no late mark. */
    private suspend fun resend(record: ArmedRecord, powerOffMs: Long, reason: String) {
        Log.i(TAG, "offreport outcome id=${record.id} action=resend reason=$reason")
        reporter.enqueue(
            OutboxEntry(
                id = record.id,
                chatId = record.chatId,
                createdMs = powerOffMs,
                text = TelegramReportBuilder.fillTime(record.text, powerOffMs),
                lateMark = false,
            )
        )
        store.clear()
        reporter.drainOutbox("power_off")
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

    private class PrefsArmedStore(context: Context) : ArmedStore {
        private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun load(): ArmedRecord? {
            val raw = prefs.getString(KEY_ARMED, null) ?: return null
            return try {
                val o = JSONObject(raw)
                ArmedRecord(o.getString("id"), o.getLong("chat"), o.getString("text"), o.getLong("at"))
            } catch (e: JSONException) {
                Log.w(TAG, "offreport stored report unreadable, cleared: ${e.javaClass.simpleName}")
                clear()
                null
            }
        }

        override fun save(record: ArmedRecord) {
            val json = JSONObject().put("id", record.id).put("chat", record.chatId)
                .put("text", record.text).put("at", record.armedAtMs)
            prefs.edit().putString(KEY_ARMED, json.toString()).apply()
        }

        override fun clear() {
            prefs.edit().remove(KEY_ARMED).apply()
        }
    }
}
