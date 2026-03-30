package com.bydmate.app.ui.welcome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.remote.DiPlusDbReader
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.TrackingService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WelcomeUiState(
    val step: Int = 1,
    val batteryCapacity: String = SettingsRepository.DEFAULT_BATTERY_CAPACITY,
    val currency: String = SettingsRepository.DEFAULT_CURRENCY,
    val currencySymbol: String = "Br",
    val homeTariff: String = SettingsRepository.DEFAULT_HOME_TARIFF,
    val dcTariff: String = SettingsRepository.DEFAULT_DC_TARIFF,
    val tripCostMode: String = "home", // "home", "dc", "custom"
    val customTariff: String = SettingsRepository.DEFAULT_HOME_TARIFF,
    val isLoading: Boolean = false,
    val importStatus: String? = null,
    val isComplete: Boolean = false
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val tripRepository: TripRepository,
    private val historyImporter: HistoryImporter,
    private val diPlusDbReader: DiPlusDbReader
) : ViewModel() {

    private val _uiState = MutableStateFlow(WelcomeUiState())
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    fun setBatteryCapacity(value: String) = _uiState.update { it.copy(batteryCapacity = value) }
    fun setHomeTariff(value: String) = _uiState.update { it.copy(homeTariff = value) }
    fun setDcTariff(value: String) = _uiState.update { it.copy(dcTariff = value) }
    fun setCustomTariff(value: String) = _uiState.update { it.copy(customTariff = value) }
    fun setTripCostMode(mode: String) = _uiState.update { it.copy(tripCostMode = mode) }

    fun setCurrency(code: String) {
        val currency = SettingsRepository.CURRENCIES.find { it.code == code }
            ?: SettingsRepository.CURRENCIES.first()
        _uiState.update { it.copy(currency = currency.code, currencySymbol = currency.symbol) }
    }

    fun nextStep() = _uiState.update { it.copy(step = 2) }
    fun prevStep() = _uiState.update { it.copy(step = 1) }

    fun startBydMate() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, importStatus = "Сохранение настроек...") }

            // Save all settings
            val state = _uiState.value
            settingsRepository.setString(SettingsRepository.KEY_BATTERY_CAPACITY, state.batteryCapacity)
            settingsRepository.setString(SettingsRepository.KEY_CURRENCY, state.currency)
            settingsRepository.setString(SettingsRepository.KEY_HOME_TARIFF, state.homeTariff)
            settingsRepository.setString(SettingsRepository.KEY_DC_TARIFF, state.dcTariff)

            val tariffValue = when (state.tripCostMode) {
                "home" -> "home"
                "dc" -> "dc"
                else -> state.customTariff
            }
            settingsRepository.setString(SettingsRepository.KEY_TRIP_COST_TARIFF, tariffValue)

            // Detect upgrade vs fresh install
            val tripCount = tripRepository.getTripCount()
            val isUpgrade = tripCount > 0

            _uiState.update { it.copy(importStatus = if (isUpgrade) "Обновление данных..." else "Импорт поездок...") }

            val tariff = settingsRepository.getTripCostTariff()

            if (isUpgrade) {
                historyImporter.deduplicateWithExisting()
            } else {
                historyImporter.syncFromEnergyData()
            }
            historyImporter.enrichWithDiPlus()
            historyImporter.calculateMissingCosts(tariff)
            historyImporter.attachGpsPoints()

            // Import charges
            diPlusDbReader.importChargingLog()

            // Mark setup complete
            settingsRepository.setSetupCompleted()

            // Start tracking service
            TrackingService.start(appContext)

            _uiState.update { it.copy(isLoading = false, isComplete = true) }
        }
    }
}
