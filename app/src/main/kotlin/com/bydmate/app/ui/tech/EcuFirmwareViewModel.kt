package com.bydmate.app.ui.tech

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.dtc.DtcSendResult
import com.bydmate.app.data.dtc.EcuFirmwareRepository
import com.bydmate.app.data.dtc.FirmwareLabels
import com.bydmate.app.data.dtc.FirmwareUiState
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The firmware window's own state: open, a bot bound (read on open), a send on its way and its result. */
data class FirmwareWindowState(
    val open: Boolean = false,
    val telegramReady: Boolean = false,
    val sending: Boolean = false,
    val sendResult: DtcSendResult? = null,
)

/**
 * The block firmware row and window of «Диагностика». The screen merges [firmware] into
 * TechPanelUiState, so the scan, the tyre jobs and the check see each other. Opening the screen
 * compares the head-unit build only; the blocks are read on «Проверить сейчас» alone.
 */
@HiltViewModel
class EcuFirmwareViewModel @Inject constructor(
    private val firmwareRepository: EcuFirmwareRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    val firmware: StateFlow<FirmwareUiState> = firmwareRepository.state

    private val _window = MutableStateFlow(FirmwareWindowState())
    val window: StateFlow<FirmwareWindowState> = _window.asStateFlow()

    init {
        firmwareRepository.refreshHuState()
    }

    /** A tap on the row: the window with the last saved check; nothing is read. */
    fun openDialog() {
        val st = firmware.value
        Log.i(TAG, "ui row tap hu_changed=${st.huChanged} checks=${st.history.size} phase=${st.phase}")
        _window.update { it.copy(open = true, sendResult = null) }
        viewModelScope.launch {
            val ready = runCatching { settingsRepository.getTgBackupConfig().configured }.getOrDefault(false)
            _window.update { it.copy(telegramReady = ready) }
        }
    }

    fun closeDialog() {
        Log.i(TAG, "ui window closed")
        _window.update { it.copy(open = false) }
    }

    /** «Проверить сейчас»; the repository checks P again and ignores a tap while a check runs. */
    fun check(gear: Int?, serviceRunning: Boolean) {
        Log.i(TAG, "ui check tap gear=$gear service=$serviceRunning")
        firmwareRepository.check()
    }

    /** One send per tap, nothing queued; a tap while one is on its way does nothing. */
    fun send(labels: FirmwareLabels) {
        if (_window.value.sending) return
        Log.i(TAG, "ui telegram tap")
        _window.update { it.copy(sending = true, sendResult = null) }
        viewModelScope.launch {
            val result = firmwareRepository.sendLast(labels)
            _window.update { it.copy(sending = false, sendResult = result) }
        }
    }

    private companion object {
        const val TAG = "EcuFw"
    }
}
