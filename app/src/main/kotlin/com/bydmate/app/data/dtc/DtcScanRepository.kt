@file:Suppress("UnusedParameter", "UnusedPrivateProperty", "FunctionOnlyReturningConstant", "EmptyFunctionBlock") // compile stub, remove with the implementation

package com.bydmate.app.data.dtc

import android.content.SharedPreferences
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.backup.TelegramError
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.helper.dtc.DtcAddressResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

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
 * run it without the service.
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
) {
    private val _state = MutableStateFlow(DtcScanState())
    val state: StateFlow<DtcScanState> = _state

    private val _last = MutableStateFlow<DtcSavedScan?>(null)
    val last: StateFlow<DtcSavedScan?> = _last

    /** A tap on «Сканировать»; ignored while a scan runs. */
    fun start() {}

    /** One plain-text attempt, never queued. */
    suspend fun sendLastScan(labels: DtcLabels): DtcSendResult = DtcSendResult(DtcSendOutcome.FAILED)
}
