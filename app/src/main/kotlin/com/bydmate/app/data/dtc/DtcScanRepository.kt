package com.bydmate.app.data.dtc

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramError
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.DtcAddressResult
import com.bydmate.app.helper.dtc.DtcScanStatus
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.ui.automation.isSampleFresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DtcScanPhase { IDLE, SCANNING }

enum class DtcScanError {
    NOT_PARK, UNAVAILABLE, ABORTED, DAEMON_UNREACHABLE, DAEMON_OUTDATED, STATUS_LOST,
}

/** [error] is the reason the last attempt ended without a result; the saved scan is untouched. */
data class DtcScanState(
    val phase: DtcScanPhase = DtcScanPhase.IDLE,
    val done: Int = 0,
    val total: Int = 18,
    val error: DtcScanError? = null,
)

/** The raw answers of the last finished scan and when DONE was seen (epoch ms). */
class DtcSavedScan(val at: Long, val total: Int, val results: List<DtcAddressResult>)

enum class DtcSendOutcome { SENT, NO_BOT, FAILED }

data class DtcSendResult(val outcome: DtcSendOutcome, val error: TelegramError? = null)

/**
 * Owner of the manual scan at app scope. One attempt per tap, no automatic start or send.
 * Seams ([liveSample], [serviceRunning], [elapsedNow], [wallNow], [gearAddress]) let tests
 * run it without the service; without a [describer] the codes go out without descriptions.
 */
@Suppress("LongParameterList") // seams for tests
class DtcScanRepository(
    private val helper: HelperClient,
    private val prefs: SharedPreferences,
    private val settings: SettingsRepository,
    private val sink: TelegramBackupSink,
    private val scope: CoroutineScope,
    private val liveSample: () -> TimedSnapshot?,
    private val serviceRunning: () -> Boolean,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
    private val gearAddress: () -> FidAddress = { FidAddresses.of("gear") },
    private val describer: DtcDescriber? = null,
) {
    private val _state = MutableStateFlow(DtcScanState())
    val state: StateFlow<DtcScanState> = _state

    private val _last = MutableStateFlow(load())
    val last: StateFlow<DtcSavedScan?> = _last

    /** A tap on «Сканировать»; ignored while a scan runs. */
    fun start() {
        val current = _state.value
        if (current.phase == DtcScanPhase.SCANNING) {
            Log.i(TAG, "tap ignored scanning")
            return
        }
        val sample = liveSample()
        val fresh = sample != null && isSampleFresh(sample, serviceRunning(), elapsedNow())
        val gear = sample?.data?.gear
        if (!fresh || gear != GEAR_PARK) {
            Log.i(TAG, "tap refused not_park gear=$gear fresh=$fresh")
            _state.value = current.copy(error = DtcScanError.NOT_PARK)
            return
        }
        Log.i(TAG, "tap gear=$gear fresh=$fresh state=${current.phase}")
        _state.value = DtcScanState(phase = DtcScanPhase.SCANNING, total = DtcWire.ADDRESSES.size)
        scope.launch { run() }
    }

    private suspend fun run() {
        Log.i(TAG, "scan autotype=${CarAutoType.read(helper) ?: "?"}")
        val address = gearAddress()
        val reply = runCatching { helper.dtcScanStart(address.device, address.fid) }.getOrNull()
        Log.i(TAG, "start reply=${replyName(reply)}")
        when (reply) {
            START_OK, START_BUSY -> poll()
            null -> fail(DtcScanError.DAEMON_UNREACHABLE)
            START_OUTDATED -> fail(DtcScanError.DAEMON_OUTDATED)
            else -> fail(DtcScanError.ABORTED)
        }
    }

    /** Once a second until the daemon reports a terminal state; three lost polls in a row end it. */
    private suspend fun poll() {
        var lost = 0
        while (true) {
            delay(POLL_MS)
            val status = runCatching { helper.dtcScanStatus() }.getOrNull()
            if (status == null) {
                if (++lost >= MAX_LOST_POLLS) {
                    Log.w(TAG, "status lost polls=$lost")
                    return fail(DtcScanError.STATUS_LOST)
                }
                continue
            }
            lost = 0
            when (status.state) {
                DtcWire.STATE_RUNNING ->
                    _state.value = _state.value.copy(done = status.done, total = status.total)
                DtcWire.STATE_DONE -> return finish(status)
                else -> {
                    Log.w(TAG, "end state=${STATE_NAMES[status.state] ?: "IDLE"} reason=${status.reason.ifEmpty { "-" }} done=${status.done}/${status.total}")
                    return fail(errorOf(status))
                }
            }
        }
    }

    private fun finish(status: DtcScanStatus) {
        val scan = DtcSavedScan(wallNow(), status.total, status.results)
        val s = DtcParser.summarize(scan.results)
        Log.i(
            TAG,
            "done answered=${s.answered}/${s.total} now=${s.now} old=${s.old} pending=${s.pending} " +
                "not_tested=${notTested(scan.results)} rows=[" +
                s.found.joinToString(",") { "0x%X:%s/%02X".format(it.addr, it.row.code, it.row.status) } + "]",
        )
        val json = toJson(scan)
        prefs.edit().putString(KEY_LAST, json).apply()
        Log.i(TAG, "saved at=${scan.at} json_len=${json.length}")
        _last.value = scan
        _state.value = DtcScanState(total = status.total)
    }

    private fun fail(error: DtcScanError) {
        _state.value = _state.value.copy(phase = DtcScanPhase.IDLE, error = error)
    }

    /** One plain-text attempt, never queued. */
    suspend fun sendLastScan(labels: DtcLabels): DtcSendResult {
        val config = runCatching { settings.getTgBackupConfig() }.getOrNull()
        val chatId = config?.chatId
        if (config == null || !config.configured || chatId == null) {
            Log.i(TAG, "telegram skipped no_bot")
            return DtcSendResult(DtcSendOutcome.NO_BOT)
        }
        val scan = _last.value ?: run {
            Log.i(TAG, "telegram skipped no_scan")
            return DtcSendResult(DtcSendOutcome.FAILED)
        }
        val summary = DtcParser.summarize(scan.results)
        val descriptions = describe(summary)
        val text = DtcTelegramText.build(summary, formatShort(scan.at), labels, { descriptions[it] }, labels.zh)
        return sink.sendMessage(config.token, chatId, text, null, false).fold(
            onSuccess = {
                Log.i(TAG, "telegram rc=ok len=${text.length}")
                DtcSendResult(DtcSendOutcome.SENT)
            },
            onFailure = { e ->
                val error = (e as? TelegramSinkException)
                Log.w(TAG, "telegram rc=${error?.key ?: e.javaClass.simpleName}")
                DtcSendResult(DtcSendOutcome.FAILED, error?.error)
            },
        )
    }

    /** Descriptions of the found codes by model + block + code; empty without a [describer]. */
    suspend fun describe(summary: DtcScanSummary): Map<DtcFound, DtcDescription> =
        describer?.describe(summary.found).orEmpty()

    private fun load(): DtcSavedScan? {
        val json = prefs.getString(KEY_LAST, null) ?: return null
        return runCatching { fromJson(json) }
            .onFailure { Log.w(TAG, "load failed ${it.javaClass.simpleName} (prefs ignored)") }
            .getOrNull()
    }

    companion object {
        const val PREFS_NAME = "dtc_scan"
        private const val KEY_LAST = "last"
        private const val TAG = "DtcScan"
        private const val GEAR_PARK = 1
        private const val POLL_MS = 1_000L
        private const val MAX_LOST_POLLS = 3
        private const val START_OK = 0
        private const val START_BUSY = 1
        private const val START_OUTDATED = -2
        private const val DUMP_HEX = 64

        /** `28.10 16:34`, the time a sent scan carries. */
        fun formatShort(at: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(at))

        /**
         * The `--- dtc scan ---` dump section: the saved raw answers per address and the daemon's
         * status as read now. Never starts a scan.
         */
        fun dumpLines(prefs: SharedPreferences, daemon: DtcScanStatus?): List<String> {
            val lines = mutableListOf<String>()
            val scan = prefs.getString(KEY_LAST, null)?.let { runCatching { fromJson(it) }.getOrNull() }
            if (scan == null) {
                lines += "last: none"
            } else {
                val s = DtcParser.summarize(scan.results)
                val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(scan.at))
                lines += "last: at=$at answered=${s.answered}/${s.total} now=${s.now} old=${s.old} pending=${s.pending}"
                scan.results.forEach { r ->
                    val count = r.countUds?.let { DtcParser.parseCount(it) }
                    lines += "addr 0x%X o=%s fmt=%s count=%s list=%s id=%s".format(
                        r.addr, r.outcome, count?.format?.let { "%02X".format(it) } ?: "-",
                        count?.count ?: "-", dumpHex(r.listUds), dumpHex(r.idUds),
                    )
                }
            }
            lines += if (daemon == null) {
                "daemon: unreachable"
            } else {
                "daemon: state=${STATE_NAMES[daemon.state] ?: "IDLE"} done=${daemon.done}/${daemon.total} reason=${daemon.reason.ifEmpty { "-" }}"
            }
            return lines
        }

        private fun dumpHex(b: ByteArray?): String {
            val hex = hex(b) ?: return "-"
            return if (hex.length > DUMP_HEX) hex.take(DUMP_HEX) + "…" else hex
        }

        private fun errorOf(status: DtcScanStatus): DtcScanError = when {
            status.state == DtcWire.STATE_UNAVAILABLE -> DtcScanError.UNAVAILABLE
            status.state == DtcWire.STATE_ABORTED && status.reason.startsWith("not_park") -> DtcScanError.NOT_PARK
            status.state == DtcWire.STATE_ABORTED -> DtcScanError.ABORTED
            // IDLE while we wait: the daemon was restarted and the job is gone.
            else -> DtcScanError.STATUS_LOST
        }

        private val STATE_NAMES = mapOf(
            DtcWire.STATE_RUNNING to "RUNNING", DtcWire.STATE_DONE to "DONE",
            DtcWire.STATE_UNAVAILABLE to "UNAVAILABLE", DtcWire.STATE_ABORTED to "ABORTED",
        )

        private fun replyName(reply: Int?): String = when (reply) {
            null -> "null(unreachable)"
            START_BUSY -> "1(busy)"
            START_OUTDATED -> "-2(outdated)"
            else -> reply.toString()
        }

        /** Records the blocks returned that carry no shown status (not tested or cleared). */
        private fun notTested(results: List<DtcAddressResult>): Int = results
            .filter { it.outcome == DtcWire.OUTCOME_OK }
            .sumOf { r ->
                val list = r.listUds ?: return@sumOf 0
                val parsed = DtcParser.parseList(list, r.countUds?.let { DtcParser.parseCount(it) }?.format)
                parsed.records - parsed.rows.size
            }

        private fun toJson(scan: DtcSavedScan): String {
            val answers = JSONArray()
            scan.results.forEach { r ->
                answers.put(
                    JSONObject()
                        .put("a", r.addr)
                        .put("o", r.outcome)
                        .putOpt("c", hex(r.countUds))
                        .putOpt("l", hex(r.listUds))
                        .putOpt("i", hex(r.idUds)),
                )
            }
            return JSONObject().put("v", 1).put("at", scan.at).put("total", scan.total).put("answers", answers).toString()
        }

        private fun fromJson(text: String): DtcSavedScan {
            val o = JSONObject(text)
            val answers = o.getJSONArray("answers")
            val results = (0 until answers.length()).map { i ->
                val a = answers.getJSONObject(i)
                DtcAddressResult(
                    a.getInt("a"), a.getString("o"),
                    unhex(a.optString("c")), unhex(a.optString("l")), unhex(a.optString("i")),
                )
            }
            return DtcSavedScan(o.getLong("at"), o.getInt("total"), results)
        }

        private fun hex(b: ByteArray?): String? = b?.joinToString("") { "%02X".format(it) }

        private fun unhex(s: String): ByteArray? =
            if (s.isEmpty()) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
