package com.bydmate.app.data.dtc

import android.content.SharedPreferences
import android.util.Log
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramSinkException
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.DtcScanner
import com.bydmate.app.helper.dtc.DtcWire
import com.bydmate.app.helper.dtc.EcuFirmwareWire
import com.bydmate.app.helper.dtc.FirmwareStatus
import com.bydmate.app.ui.automation.isSampleFresh
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class FirmwarePhase { IDLE, CHECKING }

data class FirmwareUiState(
    val phase: FirmwarePhase = FirmwarePhase.IDLE,
    val error: DtcScanError? = null,
    val done: Int = 0,
    val total: Int = 0,
    val history: List<FirmwareCheck> = emptyList(),
    /** Diff of the newest check against the one before it; empty without a check. */
    val rows: List<FirmwareRow> = emptyList(),
    val huChanged: Boolean = false,
)

/**
 * «Проверить сейчас» of the block firmware (CONTEXT.md «Прошивки блоков (2026-11-20)»): never polls
 * blocks by itself; only [check] on a fresh P sample starts the daemon job. [huBuild] = Build.FINGERPRINT.
 * The last [EcuFirmwareDiff.HISTORY] checks and the head-unit baseline live in prefs `ecu_firmware`
 * (this car only, not in the backup).
 */
@Suppress("LongParameterList") // seams for tests, as DtcScanRepository
class EcuFirmwareRepository(
    private val helper: HelperClient,
    private val prefs: SharedPreferences,
    private val settings: SettingsRepository,
    private val sink: TelegramBackupSink,
    private val scope: CoroutineScope,
    private val liveSample: () -> TimedSnapshot?,
    private val serviceRunning: () -> Boolean,
    private val elapsedNow: () -> Long,
    private val wallNow: () -> Long,
    private val huBuild: () -> String,
    private val gearAddress: () -> FidAddress = { FidAddresses.of("gear") },
) {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<FirmwareUiState> get() = _state

    /** App start / screen open: compares [huBuild] (stores the baseline when there is none). No car request. */
    fun refreshHuState() {
        val build = huBuild()
        var baseline = prefs.getString(KEY_BASELINE, null)
        if (baseline == null && build.isNotBlank()) {
            prefs.edit().putString(KEY_BASELINE, build).apply()
            baseline = build
            Log.i(TAG, "hu baseline stored build=$build")
        }
        val history = _state.value.history
        val changed = EcuFirmwareDiff.huChanged(history, baseline, build)
        Log.i(TAG, "hu refresh build=$build baseline=${baseline ?: "-"} last_check_build=${history.lastOrNull()?.huBuild ?: "-"} changed=$changed")
        _state.update { it.copy(huChanged = changed) }
    }

    /** «Проверить сейчас»: on P starts the firmware job and, when DONE, saves the check (history of 10). */
    fun check() {
        val current = _state.value
        if (current.phase != FirmwarePhase.IDLE) {
            Log.i(TAG, "check tap ignored phase=${current.phase}")
            return
        }
        val sample = liveSample()
        val fresh = sample != null && isSampleFresh(sample, serviceRunning(), elapsedNow())
        val gear = sample?.data?.gear
        if (!fresh || gear != GEAR_PARK) {
            Log.i(TAG, "check tap refused not_park gear=$gear fresh=$fresh")
            _state.update { it.copy(error = DtcScanError.NOT_PARK) }
            return
        }
        Log.i(TAG, "check tap gear=$gear history=${current.history.size}")
        _state.update { it.copy(phase = FirmwarePhase.CHECKING, error = null, done = 0, total = DtcWire.ADDRESSES.size) }
        scope.launch { run() }
    }

    private suspend fun run() {
        val gear = gearAddress()
        val reply = runCatching { helper.firmwareStart(gear.device, gear.fid) }.getOrNull()
        Log.i(TAG, "check start reply=${replyName(reply)} gear=${gear.device}/${gear.fid}")
        when (reply) {
            DtcScanner.START_OK -> poll()
            null -> fail(DtcScanError.DAEMON_UNREACHABLE)
            START_OUTDATED -> fail(DtcScanError.DAEMON_OUTDATED)
            // Busy: a scan, an erase or a tyre job holds the daemon; -1: refused. Nothing was read.
            else -> fail(DtcScanError.ABORTED)
        }
    }

    /** Once a second until the daemon reports a terminal state; three lost polls in a row, or another job, end it. */
    private suspend fun poll() {
        var lost = 0
        while (true) {
            delay(POLL_MS)
            val status = runCatching { helper.firmwareStatus() }.getOrNull()
            if (status == null) {
                if (++lost >= MAX_LOST_POLLS) {
                    Log.w(TAG, "status lost polls=$lost")
                    return fail(DtcScanError.STATUS_LOST)
                }
                continue
            }
            lost = 0
            when (status.state) {
                DtcWire.STATE_RUNNING -> _state.update { it.copy(done = status.done, total = status.total) }
                DtcWire.STATE_DONE -> return finish(status)
                DtcWire.STATE_IDLE -> {
                    // The daemon was restarted, or another job replaced this one.
                    Log.w(TAG, "poll saw IDLE, check lost")
                    return fail(DtcScanError.STATUS_LOST)
                }
                else -> {
                    Log.w(TAG, "check end state=${status.state} reason=${status.reason.ifEmpty { "-" }} done=${status.done}/${status.total}")
                    return fail(errorOf(status))
                }
            }
        }
    }

    private fun finish(status: FirmwareStatus) {
        if (status.results.none { EcuFirmwareWire.parse(it.f195Uds) != null }) {
            // A run that read no version at all is not a check: the history and the head-unit highlight stay.
            Log.w(
                TAG,
                "check end no parseable f195, not saved: blocks=${status.results.size} " +
                    "f194=${status.results.count { it.f194Uds != null }} f195=${status.results.count { it.f195Uds != null }}",
            )
            return fail(DtcScanError.ABORTED)
        }
        val check = FirmwareCheck(wallNow(), huBuild(), status.results.map { FirmwareBlock(it.addr, it.f194Uds, it.f195Uds) })
        val history = EcuFirmwareDiff.append(_state.value.history, check)
        val json = EcuFirmwareDiff.toJson(history)
        prefs.edit().putString(KEY_HISTORY, json).apply()
        val rows = rowsOf(history)
        rows.forEach { r ->
            Log.i(
                TAG,
                "check row ${EcuFirmwareText.addr(r.addr)} ${r.change} block=${r.name.block} " +
                    "before=${r.before?.version ?: "-"}/${r.before?.date ?: "-"} after=${r.after?.version ?: "-"}/${r.after?.date ?: "-"}",
            )
        }
        val s = FirmwareSections(rows)
        Log.i(
            TAG,
            "check saved at=${check.at} hu=${check.huBuild} blocks=${check.blocks.size} updated=${s.updated.size} " +
                "unchanged=${s.unchanged.size} first=${s.first.size} silent=${s.silent.size} history=${history.size} json_len=${json.length}",
        )
        val changed = EcuFirmwareDiff.huChanged(history, prefs.getString(KEY_BASELINE, null), huBuild())
        _state.update {
            it.copy(phase = FirmwarePhase.IDLE, error = null, done = status.done, total = status.total, history = history, rows = rows, huChanged = changed)
        }
    }

    private fun fail(error: DtcScanError) {
        Log.i(TAG, "check end error=$error")
        _state.update { it.copy(phase = FirmwarePhase.IDLE, error = error) }
    }

    /** «Отправить в Telegram»: one plain message (≤4096 chars) with the newest diff, one attempt, never queued; NO_BOT / FAILED as the scan send. */
    suspend fun sendLast(labels: FirmwareLabels = FirmwareLabels.ENGLISH): DtcSendResult {
        val config = runCatching { settings.getTgBackupConfig() }.getOrNull()
        val chatId = config?.chatId
        if (config == null || !config.configured || chatId == null) {
            Log.i(TAG, "telegram skipped no_bot")
            return DtcSendResult(DtcSendOutcome.NO_BOT)
        }
        val st = _state.value
        val last = st.history.lastOrNull() ?: run {
            Log.i(TAG, "telegram skipped no_check")
            return DtcSendResult(DtcSendOutcome.FAILED)
        }
        val previous = st.history.getOrNull(st.history.size - 2)
        val text = EcuFirmwareText.build(
            st.rows, DtcScanRepository.formatShort(last.at), previous?.let { DtcScanRepository.formatShort(it.at) }, labels,
        )
        return sink.sendMessage(config.token, chatId, text, null, false).fold(
            onSuccess = {
                Log.i(TAG, "telegram rc=ok len=${text.length}")
                DtcSendResult(DtcSendOutcome.SENT)
            },
            onFailure = { e ->
                val error = (e as? TelegramSinkException)
                Log.w(TAG, "telegram rc=${error?.key ?: e.javaClass.simpleName} len=${text.length}")
                DtcSendResult(DtcSendOutcome.FAILED, error?.error)
            },
        )
    }

    private fun initialState(): FirmwareUiState {
        val history = EcuFirmwareDiff.fromJson(prefs.getString(KEY_HISTORY, null))
        return FirmwareUiState(history = history, rows = rowsOf(history))
    }

    companion object {
        const val PREFS_NAME = "ecu_firmware"
        private const val KEY_HISTORY = "history"
        private const val KEY_BASELINE = "hu_baseline"
        private const val TAG = "EcuFw"
        private const val GEAR_PARK = 1
        private const val POLL_MS = 1_000L
        private const val MAX_LOST_POLLS = 3
        private const val START_OUTDATED = -2

        /** The newest check against the one before it; empty without a check. */
        private fun rowsOf(history: List<FirmwareCheck>): List<FirmwareRow> =
            history.lastOrNull()?.let { EcuFirmwareDiff.diff(history.getOrNull(history.size - 2), it) }.orEmpty()

        /**
         * The `--- ecu firmware ---` dump section: the baseline and every saved check, raw per
         * address. Never starts a check.
         */
        fun dumpLines(prefs: SharedPreferences): List<String> {
            val lines = mutableListOf("hu baseline: ${prefs.getString(KEY_BASELINE, null) ?: "none"}")
            val history = EcuFirmwareDiff.fromJson(prefs.getString(KEY_HISTORY, null))
            if (history.isEmpty()) lines += "checks: none"
            history.forEachIndexed { i, c ->
                val at = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(c.at))
                lines += "check ${i + 1}/${history.size}: at=$at hu=${c.huBuild} blocks=${c.blocks.size}"
            }
            history.lastOrNull()?.let { last ->
                val rows = rowsOf(history).associateBy { it.addr }
                last.blocks.forEach { b ->
                    lines += "  ${EcuFirmwareText.addr(b.addr)} ${rows[b.addr]?.change ?: "-"} " +
                        "f194=${hex(b.f194Uds) ?: "-"} f195=${hex(b.f195Uds) ?: "-"} v=${rows[b.addr]?.after?.version ?: "-"}"
                }
            }
            return lines
        }

        private fun hex(b: ByteArray?): String? = b?.joinToString("") { "%02X".format(it) }

        private fun errorOf(status: FirmwareStatus): DtcScanError = when {
            status.state == DtcWire.STATE_UNAVAILABLE && status.reason == "guard_absent" -> DtcScanError.NO_DIAG_SERVICE
            status.state == DtcWire.STATE_UNAVAILABLE -> DtcScanError.UNAVAILABLE
            status.reason.startsWith("not_park") -> DtcScanError.NOT_PARK
            else -> DtcScanError.ABORTED
        }

        private fun replyName(reply: Int?): String = when (reply) {
            null -> "null(unreachable)"
            DtcScanner.START_BUSY -> "1(busy)"
            DtcScanner.START_REFUSED -> "-1(refused)"
            START_OUTDATED -> "-2(outdated)"
            else -> reply.toString()
        }
    }
}
