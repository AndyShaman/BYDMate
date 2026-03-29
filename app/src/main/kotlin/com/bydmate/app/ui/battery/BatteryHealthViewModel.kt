package com.bydmate.app.ui.battery

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class BatteryHealthUiState(
    val charges: List<ChargeEntity> = emptyList(),
    val snapshots: List<BatterySnapshotEntity> = emptyList(),
    val avgDelta: Double? = null,
    val minVoltage12v: Double? = null,
    val currentDelta: Double? = null,
    val currentVoltage12v: Double? = null,
    val currentSoh: Double? = null,
    val currentCapacity: Double? = null,
    val isLoading: Boolean = true
)

@HiltViewModel
class BatteryHealthViewModel @Inject constructor(
    private val chargeRepository: ChargeRepository,
    private val batteryHealthRepository: BatteryHealthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(BatteryHealthUiState())
    val uiState: StateFlow<BatteryHealthUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    private fun loadData() {
        viewModelScope.launch {
            val charges = chargeRepository.getRecentChargesWithBatteryData()

            val deltas = charges.mapNotNull { c ->
                if (c.cellVoltageMax != null && c.cellVoltageMin != null)
                    c.cellVoltageMax - c.cellVoltageMin else null
            }
            val voltages12v = charges.mapNotNull { it.voltage12v }

            _uiState.update {
                it.copy(
                    charges = charges,
                    avgDelta = if (deltas.isNotEmpty()) deltas.average() else null,
                    minVoltage12v = voltages12v.minOrNull(),
                    currentDelta = deltas.firstOrNull(),
                    currentVoltage12v = voltages12v.firstOrNull()
                )
            }

            // Load battery snapshots
            batteryHealthRepository.getRecent(50).collect { snapshots ->
                val lastSnapshot = snapshots.firstOrNull()
                _uiState.update {
                    it.copy(
                        snapshots = snapshots,
                        currentSoh = lastSnapshot?.sohPercent,
                        currentCapacity = lastSnapshot?.calculatedCapacityKwh,
                        isLoading = false
                    )
                }
            }
        }
    }
}
