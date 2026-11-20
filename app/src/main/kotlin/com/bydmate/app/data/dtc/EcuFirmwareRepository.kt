package com.bydmate.app.data.dtc

import android.content.SharedPreferences
import com.bydmate.app.data.backup.TelegramBackupSink
import com.bydmate.app.data.loop.TimedSnapshot
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.nativestack.FidAddress
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.data.vehicle.HelperClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

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
 */
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
    val state: StateFlow<FirmwareUiState> get() = TODO()

    /** App start / screen open: compares [huBuild] (stores the baseline when there is none). No car request. */
    fun refreshHuState() { TODO() }

    /** «Проверить сейчас»: on P starts the firmware job and, when DONE, saves the check (history of 10). */
    fun check() { TODO() }

    /** «Отправить в Telegram»: one plain message (≤4096 chars) with the newest diff, one attempt, never queued; NO_BOT / FAILED as the scan send. */
    suspend fun sendLast(): DtcSendResult = TODO()
}
