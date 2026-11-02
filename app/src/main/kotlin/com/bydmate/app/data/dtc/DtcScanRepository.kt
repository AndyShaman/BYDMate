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
import com.bydmate.app.helper.dtc.DtcEraseResult
import com.bydmate.app.helper.dtc.DtcScanStatus
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.ui.automation.isSampleFresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DtcScanPhase { IDLE, SCANNING }

/**
 * [NO_DIAG_SERVICE]: the firmware has no diagnostic manager (daemon reason `guard_absent`);
 * [ERASE_NEEDS_SCAN]: an erase was asked without a scan of the last ten minutes.
 */
enum class DtcScanError {
    NOT_PARK, UNAVAILABLE, NO_DIAG_SERVICE, ABORTED, DAEMON_UNREACHABLE, DAEMON_OUTDATED, STATUS_LOST,
    ERASE_NEEDS_SCAN,
}

/**
 * [error] is the reason the last attempt ended without a result; the saved scan is untouched.
 * [firmwareHint]: with [DtcScanError.NO_DIAG_SERVICE] on a model whose newer firmware has the service.
 */
data class DtcScanState(
    val phase: DtcScanPhase = DtcScanPhase.IDLE,
    val done: Int = 0,
    val total: Int = 18,
    val error: DtcScanError? = null,
    val firmwareHint: Boolean = false,
)

enum class DtcErasePhase { ERASING, ENDED }

/**
 * The last erase of this process: progress while [phase] is ERASING, then one [results] entry per
 * requested block and the daemon's stop [reason]. [error]: the erase did not run, or its end is unknown.
 */
data class DtcEraseState(
    val phase: DtcErasePhase,
    val done: Int = 0,
    val total: Int = 0,
    val results: List<DtcEraseResult> = emptyList(),
    val reason: String = "",
    val error: DtcScanError? = null,
)

/**
 * The raw answers of the last finished scan and when DONE was seen (epoch ms).
 * [eraseLocked]: blocks of unknown erase result since this scan, not erased again until a new one.
 */
class DtcSavedScan(
    val at: Long,
    val total: Int,
    val results: List<DtcAddressResult>,
    val eraseLocked: Set<Int> = emptySet(),
)

enum class DtcSendOutcome { SENT, NO_BOT, FAILED }

data class DtcSendResult(val outcome: DtcSendOutcome, val error: TelegramError? = null)

/**
 * Owner of the manual scan at app scope. One attempt per tap, no automatic start or send.
 * Seams ([liveSample], [serviceRunning], [elapsedNow], [wallNow], [gearAddress]) let tests
 * run it without the service; without a [describer] the codes go out without descriptions.
 */
@Suppress("LongParameterList", "TooManyFunctions") // seams for tests; scan and erase share the daemon and the saved scan
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
    private val speedAddress: () -> FidAddress = { FidAddresses.of("speed") },
) {
    private val _state = MutableStateFlow(DtcScanState())
    val state: StateFlow<DtcScanState> = _state

    private val _last = MutableStateFlow(load())
    val last: StateFlow<DtcSavedScan?> = _last

    private val _erase = MutableStateFlow<DtcEraseState?>(null)
    val erase: StateFlow<DtcEraseState?> = _erase

    /**
     * When the last erase whose outcome is unknown ended (epoch ms), kept across restarts.
     * While it is newer than [last], the saved codes may be gone and only a new scan tells.
     */
    private val _erasedAt = MutableStateFlow(prefs.getLong(KEY_ERASED_AT, 0L).takeIf { it > 0L })
    val erasedAt: StateFlow<Long?> = _erasedAt

    /** [erase] and [erasedAt] together, for the screen. */
    fun eraseUpdates(): Flow<Pair<DtcEraseState?, Long?>> = combine(_erase, _erasedAt) { e, at -> e to at }

    private var autotype: Int? = null

    private val erasing: Boolean get() = _erase.value?.phase == DtcErasePhase.ERASING

    /** A tap on «Сканировать»; ignored while a scan or an erase runs. */
    fun start() {
        val current = _state.value
        if (current.phase == DtcScanPhase.SCANNING || erasing) {
            Log.i(TAG, "tap ignored scanning=${current.phase == DtcScanPhase.SCANNING} erasing=$erasing")
            return
        }
        val gear = parkGear()
        if (gear != GEAR_PARK) {
            _state.value = current.copy(error = DtcScanError.NOT_PARK)
            return
        }
        Log.i(TAG, "tap gear=$gear state=${current.phase}")
        _state.value = DtcScanState(phase = DtcScanPhase.SCANNING, total = DtcWire.ADDRESSES.size)
        scope.launch { run() }
    }

    /** The live gear when the sample is fresh, null otherwise; a refusal is logged. */
    private fun parkGear(): Int? {
        val sample = liveSample()
        val fresh = sample != null && isSampleFresh(sample, serviceRunning(), elapsedNow())
        val gear = sample?.data?.gear
        if (!fresh || gear != GEAR_PARK) {
            Log.i(TAG, "tap refused not_park gear=$gear fresh=$fresh")
            return null
        }
        return gear
    }

    private suspend fun run() {
        autotype = CarAutoType.read(helper)
        Log.i(TAG, "scan autotype=${autotype ?: "?"}")
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
            if (status.mode == DtcWire.MODE_ERASE) {
                // An erase holds the daemon (e.g. started before the app was restarted): never a scan.
                Log.w(TAG, "scan poll saw an erase state=${STATE_NAMES[status.state] ?: "IDLE"}, scan ended")
                return fail(DtcScanError.ABORTED)
            }
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
        _erase.value = null
        _state.value = DtcScanState(total = status.total)
    }

    private fun fail(error: DtcScanError) {
        val hint = error == DtcScanError.NO_DIAG_SERVICE && dtcFirmwareHint(autotype)
        _state.value = _state.value.copy(phase = DtcScanPhase.IDLE, error = error, firmwareHint = hint)
    }

    /**
     * A confirmed tap on «Стереть все» or «стереть ›»: [addrs] must all have a shown code in the
     * saved scan, which must be at most ten minutes old and taken after the last erase of unknown
     * outcome; on P only.
     * The saved codes go to the log first; ignored while a scan or an erase runs.
     */
    fun startErase(addrs: IntArray) {
        val list = addrs.joinToString(",") { "0x%X".format(it) }
        if (_state.value.phase == DtcScanPhase.SCANNING || erasing) {
            Log.i(TAG, "erase tap ignored addrs=$list")
            return
        }
        val saved = _last.value
        val eligible = DtcErasePlan.eligible(saved, wallNow())
        if (saved == null || !eraseAllowed(saved, eligible, addrs)) {
            Log.i(
                TAG,
                "erase refused needs_scan addrs=$list eligible=${eligible?.joinToString(",") { "0x%X".format(it) } ?: "-"} " +
                    "scan_at=${saved?.at ?: "-"} erased_at=${_erasedAt.value ?: "-"} " +
                    "locked=${saved?.eraseLocked?.sorted()?.joinToString(",") { "0x%X".format(it) }?.ifEmpty { null } ?: "-"}",
            )
            _erase.value = DtcEraseState(DtcErasePhase.ENDED, error = DtcScanError.ERASE_NEEDS_SCAN)
            return
        }
        if (parkGear() != GEAR_PARK) {
            _erase.value = DtcEraseState(DtcErasePhase.ENDED, error = DtcScanError.NOT_PARK)
            return
        }
        _erase.value = DtcEraseState(DtcErasePhase.ERASING, total = addrs.size)
        scope.launch { runErase(addrs.copyOf(), saved) }
    }

    /** Every one of [addrs] has a shown code in [saved], fresh and taken after the last erase of unknown outcome. */
    private fun eraseAllowed(saved: DtcSavedScan, eligible: IntArray?, addrs: IntArray): Boolean {
        val erasedSince = (_erasedAt.value ?: 0L) >= saved.at
        return eligible != null && !erasedSince && addrs.isNotEmpty() && addrs.all { it in eligible }
    }

    private suspend fun runErase(addrs: IntArray, saved: DtcSavedScan) {
        val s = DtcParser.summarize(saved.results)
        Log.i(
            TAG,
            "dtc erase before scan_at=${formatShort(saved.at)} addrs=${addrs.joinToString(",") { "0x%X".format(it) }} " +
                "now=${s.now} old=${s.old} pending=${s.pending} rows=[" +
                s.found.joinToString(",") { "0x%X:%s/%02X".format(it.addr, it.row.code, it.row.status) } + "]",
        )
        val gear = gearAddress()
        val speed = speedAddress()
        val reply = runCatching { helper.dtcEraseStart(gear.device, gear.fid, speed.device, speed.fid, addrs) }.getOrNull()
        Log.i(TAG, "erase start reply=${replyName(reply)} gear=${gear.device}/${gear.fid} speed=${speed.device}/${speed.fid}")
        when (reply) {
            START_OK -> pollErase()
            // No reply: the daemon may have started the clears after the call timed out.
            null -> endErase(DtcScanError.STATUS_LOST, mayHaveErased = true)
            START_OUTDATED -> endErase(DtcScanError.DAEMON_OUTDATED)
            // Nothing was sent: busy = a scan or an erase holds the daemon, -1 = it refused the list.
            else -> endErase(DtcScanError.ABORTED)
        }
    }

    /** As the scan's poll; any state other than this erase's own ends it as lost. */
    private suspend fun pollErase() {
        var lost = 0
        while (true) {
            delay(POLL_MS)
            val status = runCatching { helper.dtcScanStatus() }.getOrNull()
            if (status == null) {
                if (++lost >= MAX_LOST_POLLS) {
                    Log.w(TAG, "erase status lost polls=$lost")
                    return endErase(DtcScanError.STATUS_LOST, mayHaveErased = true)
                }
                continue
            }
            lost = 0
            when {
                status.mode != DtcWire.MODE_ERASE || status.state == DtcWire.STATE_IDLE -> {
                    Log.w(TAG, "erase poll saw mode=${status.mode} state=${STATE_NAMES[status.state] ?: "IDLE"}, erase lost")
                    return endErase(DtcScanError.STATUS_LOST, mayHaveErased = true)
                }
                status.state == DtcWire.STATE_RUNNING ->
                    _erase.value = _erase.value?.copy(done = status.done, total = status.total)
                else -> return finishErase(status)
            }
        }
    }

    private fun finishErase(status: DtcScanStatus) {
        status.erase.forEach { r ->
            Log.i(
                TAG,
                "erase result addr=0x%X verdict=%s confirm=%s nrc=%s reason=%s reread=%s".format(
                    r.addr, dtcEraseVerdict(r), r.confirm, r.nrc?.let { "%02X".format(it) } ?: "-",
                    r.reason.ifEmpty { "-" },
                    rereadRows(r)?.joinToString(",", "[", "]") { "${it.code}/%02X".format(it.status) } ?: "-",
                ),
            )
        }
        val error = if (status.state == DtcWire.STATE_UNAVAILABLE) errorOf(status) else null
        Log.i(
            TAG,
            "erase end state=${STATE_NAMES[status.state] ?: "IDLE"} reason=${status.reason.ifEmpty { "-" }} " +
                "blocks=${status.erase.size} error=${error ?: "-"}",
        )
        if (status.erase.isNotEmpty()) applyErase(status.erase)
        _erase.value = DtcEraseState(
            DtcErasePhase.ENDED, status.done, status.total, status.erase, status.reason, error,
        )
    }

    /** The erase ended without results; [mayHaveErased] when a clear may have gone out. */
    private fun endErase(error: DtcScanError, mayHaveErased: Boolean = false) {
        Log.i(TAG, "erase end error=$error may_have_erased=$mayHaveErased")
        if (mayHaveErased) markErased()
        _erase.value = (_erase.value ?: DtcEraseState(DtcErasePhase.ENDED)).copy(phase = DtcErasePhase.ENDED, error = error)
    }

    /**
     * Each re-read block replaces its entry in the saved scan, which keeps its time;
     * blocks of unknown result join its erase locks.
     */
    private fun applyErase(erase: List<DtcEraseResult>) {
        val saved = _last.value ?: return
        val scan = DtcSavedScan(
            saved.at, saved.total, dtcScanAfterErase(saved.results, erase), saved.eraseLocked + dtcEraseLocks(erase),
        )
        Log.i(
            TAG,
            "erase applied addrs=${erase.joinToString(",") { "0x%X".format(it.addr) }} " +
                "rows_before=${DtcParser.summarize(saved.results).found.size} " +
                "rows_after=${DtcParser.summarize(scan.results).found.size} " +
                "locked=${scan.eraseLocked.sorted().joinToString(",") { "0x%X".format(it) }.ifEmpty { "-" }}",
        )
        prefs.edit().putString(KEY_LAST, toJson(scan)).apply()
        _last.value = scan
    }

    private fun markErased() {
        val at = wallNow()
        prefs.edit().putLong(KEY_ERASED_AT, at).apply()
        _erasedAt.value = at
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
        private const val KEY_ERASED_AT = "erased_at"
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
            status.state == DtcWire.STATE_UNAVAILABLE && status.reason == "guard_absent" -> DtcScanError.NO_DIAG_SERVICE
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
            return JSONObject().put("v", 1).put("at", scan.at).put("total", scan.total).put("answers", answers)
                .put("locked", JSONArray(scan.eraseLocked.sorted())).toString()
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
            // A scan saved before the erase locks has no "locked".
            val eraseLocked = o.optJSONArray("locked")?.let { l -> (0 until l.length()).map { l.getInt(it) }.toSet() }.orEmpty()
            return DtcSavedScan(o.getLong("at"), o.getInt("total"), results, eraseLocked)
        }

        private fun hex(b: ByteArray?): String? = b?.joinToString("") { "%02X".format(it) }

        private fun unhex(s: String): ByteArray? =
            if (s.isEmpty()) null else ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
