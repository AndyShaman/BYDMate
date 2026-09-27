package com.bydmate.app.data.telegram

import android.content.Context
import android.util.Log
import com.bydmate.app.R
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.backup.TgBackupConfig
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.util.AppStrings
import com.bydmate.app.util.appLanguageTag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A report entry waiting for the network; [chatId] is the chat it was built for. [lateMark] = add
 * «(записано в HH:MM)» when it goes out late; the power-off report carries its time in the header
 * already, so phase B queues it with false.
 */
data class OutboxEntry(
    val id: String,
    val chatId: Long,
    val createdMs: Long,
    val text: String,
    val lateMark: Boolean = true,
)

/**
 * The ready power-off report phase B hands to the helper daemon: bot, chat and the text with
 * [TelegramReportBuilder.TIME_PLACEHOLDER] in its header. toString never shows the secrets.
 */
data class PowerOffReport(val id: String, val token: String, val chatId: Long, val text: String) {
    override fun toString(): String = "PowerOffReport(id=$id, len=${text.length})"
}

/**
 * Telegram reports (3.19): builds them from the live state, sends them through the backup bot and
 * keeps the ones that met no network in a small outbox on disk ([SettingsRepository.KEY_TG_REPORT_OUTBOX]).
 * The log gets ids, field names, lengths and error keys only: never the token, the chat, the text
 * or the coordinates, since users post their logs in public issues.
 */
@Singleton
@Suppress("TooManyFunctions") // send, outbox on disk and the dump, kept in one place
class TelegramReporter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sink: TelegramBackupSink,
    private val settings: SettingsRepository,
    private val tripDao: TripDao,
    private val appStrings: AppStrings,
    private val offState: PowerOffArmState,
) {
    companion object {
        private const val TAG = "TgReport"
        const val OUTBOX_MAX = 10
        /** A report sent later than this after it was built gets «(записано в HH:MM)». */
        const val LATE_MARK_AFTER_MS = 2 * 60_000L
        private const val PARSE_MODE = "HTML"
        private const val RECENT_TRIPS = 5
    }

    sealed interface SendResult {
        data object Sent : SendResult
        /** No network or Telegram busy: the report waits in the outbox. */
        data object Queued : SendResult
        data object NotConnected : SendResult
        /** A failure that will not heal by retrying: [key] as in [TelegramSinkException.key]. */
        data class Failed(val key: String) : SendResult
    }

    /** Test seams: the live state, the clock and the interface language. */
    internal var inputs: suspend () -> ReportInputs = { liveInputs() }
    internal var clock: () -> Long = System::currentTimeMillis
    internal var language: () -> String = { context.appLanguageTag() }

    @Suppress("SpreadOperator") // at most three format arguments per string
    private val strings = ReportStrings { id, args -> appStrings.get(id, *args) }
    private val outboxMutex = Mutex()

    /**
     * Bumped by every [drainOutbox] call. [deliver] reads it before sending: if it moved while the
     * send was in flight, a network-regain or after-send drain ran and found the queue empty
     * (the failed report was not enqueued yet), so it runs [drainOutbox] once more itself instead
     * of leaving the report stuck until the next trigger.
     */
    private val drainGeneration = AtomicInteger(0)

    /** The `telegram_report` automation action: header «BYDMate: <rule name>». */
    suspend fun sendRuleReport(ruleName: String?, fields: Set<ReportField>, customText: String): SendResult {
        val config = settings.getTgBackupConfig()
        val id = newId()
        val chatId = config.chatId
        if (!config.configured || chatId == null) {
            Log.w(TAG, "rule report id=$id skipped: bot not connected")
            return SendResult.NotConnected
        }
        val built = build(id, "rule", TelegramReportBuilder.ruleHeader(ruleName), customText, fields)
        return deliver(id, config, chatId, built.report.text, built.builtAtMs)
    }

    /**
     * Phase B seam: the power-off report to arm the daemon with, or null when the report is off in
     * the settings or no bot is connected (then the daemon is disarmed). Rebuilt every ~10 s while
     * the car is on: when bot, chat and text are those of [previous], [previous] itself comes back
     * (same id, no build line in the log), so the caller re-arms only on a real change.
     */
    suspend fun powerOffReport(previous: PowerOffReport? = null): PowerOffReport? {
        if (!settings.isTgReportOffEnabled()) return null
        val config = settings.getTgBackupConfig()
        val chatId = config.chatId
        if (!config.configured || chatId == null) return null
        val header = TelegramReportBuilder.powerOffHeader(strings)
        val fields = settings.getTgReportOffFields()
        val report = TelegramReportBuilder.build(header, "", fields, inputs(), language(), strings, clock())
        previous?.takeIf { it.token == config.token && it.chatId == chatId && it.text == report.text }
            ?.let { return it }
        val id = newId()
        logBuild(id, "power_off", fields, "", report)
        return PowerOffReport(id, config.token, chatId, report.text)
    }

    /**
     * Sends what the outbox holds, oldest first: at service start, when the internet comes back and
     * after every report that went through. Stops at the first temporary failure; a report whose bot
     * was disconnected or whose chat changed is dropped, so it never lands in someone else's chat.
     */
    suspend fun drainOutbox(reason: String) {
        drainGeneration.incrementAndGet()
        outboxMutex.withLock {
            var queue = loadOutbox()
            if (queue.isEmpty()) return
            Log.i(TAG, "outbox drain reason=$reason size=${queue.size}")
            val config = settings.getTgBackupConfig()
            while (queue.isNotEmpty()) {
                val entry = queue.first()
                if (!config.configured || config.chatId != entry.chatId) {
                    val why = if (config.configured) "chat_changed" else "not_connected"
                    Log.w(TAG, "outbox drop id=${entry.id} reason=$why")
                    queue = queue.drop(1)
                    saveOutbox(queue)
                    continue
                }
                val now = clock()
                val late = entry.lateMark && now - entry.createdMs > LATE_MARK_AFTER_MS
                val text = if (late) entry.text + "\n" + lateMark(entry.createdMs, now) else entry.text
                val result = sink.sendMessage(config.token, entry.chatId, text, PARSE_MODE)
                val failure = result.exceptionOrNull()
                val ageS = (now - entry.createdMs) / 1000
                when {
                    failure == null -> {
                        Log.i(TAG, "outbox sent id=${entry.id} age_s=$ageS late_mark=$late")
                        queue = queue.drop(1)
                    }
                    isTransient(failure) -> {
                        Log.w(TAG, "outbox keep id=${entry.id} rc=${errorKey(failure)} age_s=$ageS left=${queue.size}")
                        return
                    }
                    else -> {
                        Log.w(TAG, "outbox drop id=${entry.id} reason=${errorKey(failure)}")
                        queue = queue.drop(1)
                    }
                }
                saveOutbox(queue)
            }
        }
    }

    /** «BYDMate: последнее состояние машины на HH:MM», the header of a re-send without proof. */
    fun lastStateHeader(atMs: Long): String =
        appStrings.get(R.string.tg_report_header_last_state, TelegramReportBuilder.formatTime(atMs))

    /**
     * Puts a report in the outbox; past [OUTBOX_MAX] the oldest ones go. An id already queued is
     * ignored, so a re-send repeated after a crash between two writes is still one message. [createdMs] is when the report
     * describes the car: phase B passes the power-off time of a report the daemon could not send.
     */
    suspend fun enqueue(entry: OutboxEntry) {
        outboxMutex.withLock {
            val current = loadOutbox()
            if (current.any { it.id == entry.id }) {
                Log.i(TAG, "outbox add id=${entry.id} skipped: already queued")
                return
            }
            val queue = (current + entry).sortedBy { it.createdMs }
            val kept = queue.takeLast(OUTBOX_MAX)
            saveOutbox(kept)
            Log.i(TAG, "outbox add id=${entry.id} size=${kept.size} evicted=${queue.size - kept.size}")
        }
    }

    suspend fun outboxSize(): Int = loadOutbox().size

    /**
     * The dump header lines. `armed` is the age of the report the daemon holds, `daemon` whether it
     * took the last arm (ok), is too old for it (outdated) or was not reached (-); `last_off` is the
     * daemon's last power-off as the arming loop last read it.
     */
    suspend fun diagnosticsLines(): List<String> {
        val armedAt = offState.armedAtMs
        val armedAge = if (armedAt > 0L) "${(clock() - armedAt) / 1000}s" else "-"
        return listOf(
            "telegram report: off=${if (settings.isTgReportOffEnabled()) "on" else "off"} " +
                "fields=[${ReportField.toCsv(settings.getTgReportOffFields())}] armed=$armedAge " +
                "daemon=${offState.daemon} outbox=${outboxSize()}",
            "telegram report last_off: ${offState.lastOffLine()}",
            "telegram report listener: ${offState.listenerLine()}",
        )
    }

    private suspend fun deliver(id: String, config: TgBackupConfig, chatId: Long, text: String, builtAtMs: Long): SendResult {
        val generationBeforeSend = drainGeneration.get()
        val failure = sink.sendMessage(config.token, chatId, text, PARSE_MODE).exceptionOrNull()
        return when {
            failure == null -> {
                Log.i(TAG, "send id=$id rc=ok")
                drainOutbox("after_send")
                SendResult.Sent
            }
            isTransient(failure) -> {
                Log.w(TAG, "send id=$id rc=${errorKey(failure)} -> outbox")
                enqueue(OutboxEntry(id, chatId, builtAtMs, text))
                // A drain that ran while the send was in flight found this report not queued yet
                // (see drainGeneration doc): run it once more so it is not stuck until the next trigger.
                if (drainGeneration.get() != generationBeforeSend) drainOutbox("late_edge")
                SendResult.Queued
            }
            else -> {
                Log.w(TAG, "send id=$id rc=${errorKey(failure)} permanent")
                SendResult.Failed(errorKey(failure))
            }
        }
    }

    /** [BuiltReport] plus the moment it describes the car: what [deliver] stamps an outbox entry with. */
    private data class Built(val report: BuiltReport, val builtAtMs: Long)

    private suspend fun build(
        id: String,
        source: String,
        header: String,
        customText: String,
        fields: Set<ReportField>,
    ): Built {
        val builtAtMs = clock()
        val report = TelegramReportBuilder.build(header, customText, fields, inputs(), language(), strings, builtAtMs)
        logBuild(id, source, fields, customText, report)
        return Built(report, builtAtMs)
    }

    private fun logBuild(id: String, source: String, fields: Set<ReportField>, customText: String, report: BuiltReport) {
        Log.i(
            TAG,
            "build id=$id src=$source fields=[${ReportField.toCsv(fields)}] " +
                "taken=[${report.taken.joinToString(",") { it.id }}] " +
                "skipped=[${report.skipped.joinToString(",") { it.id }}] " +
                "custom=${customText.isNotBlank()} len=${report.text.length}",
        )
    }

    /** «(записано в 18:42)», with the date when the report is from another day. */
    private fun lateMark(createdMs: Long, nowMs: Long): String {
        val stamp = if (sameDay(createdMs, nowMs)) TelegramReportBuilder.formatTime(createdMs)
        else TelegramReportBuilder.formatDateTime(createdMs)
        return "<i>${TelegramReportBuilder.escape(appStrings.get(R.string.tg_report_recorded_at, stamp))}</i>"
    }

    private fun sameDay(a: Long, b: Long): Boolean {
        val ca = Calendar.getInstance().apply { timeInMillis = a }
        val cb = Calendar.getInstance().apply { timeInMillis = b }
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
    }

    private fun isTransient(e: Throwable): Boolean = (e as? TelegramSinkException)?.transient ?: true

    private fun errorKey(e: Throwable): String = (e as? TelegramSinkException)?.key ?: e.javaClass.simpleName

    private suspend fun loadOutbox(): List<OutboxEntry> {
        val raw = settings.getTgReportOutbox()
        if (raw.isBlank()) return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val o = array.getJSONObject(i)
                OutboxEntry(
                    o.getString("id"), o.getLong("chat"), o.getLong("created"), o.getString("text"),
                    o.optBoolean("late", true),
                )
            }
        } catch (e: JSONException) {
            Log.w(TAG, "outbox unreadable, cleared: ${e.javaClass.simpleName}")
            settings.setTgReportOutbox("")
            emptyList()
        }
    }

    private suspend fun saveOutbox(queue: List<OutboxEntry>) {
        val array = JSONArray()
        queue.forEach { e ->
            array.put(
                JSONObject().put("id", e.id).put("chat", e.chatId).put("created", e.createdMs)
                    .put("text", e.text).put("late", e.lateMark)
            )
        }
        settings.setTgReportOutbox(if (queue.isEmpty()) "" else array.toString())
    }

    private fun newId(): String = UUID.randomUUID().toString().take(8)

    /** The trip under way only when its counters cover the whole session, else the last recorded trip. */
    private suspend fun liveInputs(): ReportInputs {
        val location = TrackingService.lastLocation.value
        val startedAt = TrackingService.sessionStartedAt.value
        val km = TrackingService.tripDistanceKm.value
        val live = if (startedAt != null && km != null && TrackingService.liveWholeSession.value) {
            LiveTrip(km, TrackingService.tripKwhConsumed.value, startedAt)
        } else null
        val lastTrip = tripDao.getRecent(RECENT_TRIPS).first().firstOrNull { (it.distanceKm ?: 0.0) > 0.0 }
        return ReportInputs(
            data = TrackingService.lastData.value,
            rangeKm = TrackingService.lastRangeKm.value,
            latitude = location?.latitude,
            longitude = location?.longitude,
            liveTrip = live,
            lastTrip = lastTrip,
        )
    }
}
