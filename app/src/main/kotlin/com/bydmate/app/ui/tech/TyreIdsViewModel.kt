package com.bydmate.app.ui.tech

import android.util.Log
import androidx.lifecycle.ViewModel
import com.bydmate.app.data.tpms.TpmsRepository
import com.bydmate.app.data.tpms.TpmsUiState
import com.bydmate.app.data.tpms.TyreSetName
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * The tyre-sensor ID window of «Машина»: its open flag and the repository's state. The screen
 * merges [tpms] into TechPanelUiState, so the fault-code scan and the tyre jobs see each other.
 */
@HiltViewModel
class TyreIdsViewModel @Inject constructor(
    private val tpmsRepository: TpmsRepository,
) : ViewModel() {

    val tpms: StateFlow<TpmsUiState> = tpmsRepository.state

    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    /**
     * A tap on the car with the tyre pressures: opens the window, and on P the repository reads the
     * IDs. While a fault-code scan or erase holds the daemon nothing is read; the last read shows.
     */
    fun openDialog(gear: Int?, serviceRunning: Boolean, dtcBusy: Boolean) {
        Log.i(TAG, "ui car tap gear=$gear service=$serviceRunning dtc_busy=$dtcBusy")
        _open.update { true }
        if (!dtcBusy) tpmsRepository.onWindowOpened()
    }

    fun closeDialog() {
        Log.i(TAG, "ui window closed")
        _open.update { false }
        tpmsRepository.onWindowClosed()
    }

    /** The open window saw a new sample or a job end: after an incomplete write the repository reads once on P. */
    fun onParkAgain() = tpmsRepository.onParkAgain()

    fun takeFromCar(name: TyreSetName) = tpmsRepository.takeFromCar(name)

    fun editSet(name: TyreSetName, wheel: Int, raw: String?) = tpmsRepository.editSet(name, wheel, raw)

    fun write(target: List<String?>, written: TyreSetName?, wheel: Int?, shown: List<Int?>) = tpmsRepository.write(target, written, wheel, shown)

    fun undo(shown: List<Int?>) = tpmsRepository.undoLastWrite(shown)

    /** One line per step of the window the repository does not see (screens, confirmations, drags). */
    fun logStep(step: String) {
        Log.i(TAG, "ui $step")
    }

    private companion object {
        const val TAG = "Tpms"
    }
}
