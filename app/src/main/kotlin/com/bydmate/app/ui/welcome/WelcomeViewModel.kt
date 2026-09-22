package com.bydmate.app.ui.welcome

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.R
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.service.TrackingService
import com.bydmate.app.util.appLocalizedContext
import com.bydmate.app.util.applyAppLanguage
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
    val language: String = "ru",
    val batteryCapacity: String = SettingsRepository.DEFAULT_BATTERY_CAPACITY,
    val currency: String = SettingsRepository.DEFAULT_CURRENCY,
    val currencySymbol: String = "BYN",
    /** True once the user picked a currency; language changes stop overriding it. */
    val currencyTouched: Boolean = false,
    val homeTariff: String = SettingsRepository.DEFAULT_HOME_TARIFF,
    val dcTariff: String = SettingsRepository.DEFAULT_DC_TARIFF,
    val isLoading: Boolean = false,
    val importStatus: String? = null,
    val isComplete: Boolean = false
)

@HiltViewModel
class WelcomeViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settingsRepository: SettingsRepository,
    private val localePreferences: LocalePreferences,
    private val tripRepository: TripRepository,
    private val historyImporter: HistoryImporter
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        (localePreferences.getLanguage() ?: "ru").let { lang ->
            val currency = findCurrency(currencyForLanguage(lang))
            WelcomeUiState(language = lang, currency = currency.code, currencySymbol = currency.symbol)
        }
    )
    val uiState: StateFlow<WelcomeUiState> = _uiState.asStateFlow()

    fun setBatteryCapacity(value: String) = _uiState.update { it.copy(batteryCapacity = value) }
    fun setHomeTariff(value: String) = _uiState.update { it.copy(homeTariff = value) }
    fun setDcTariff(value: String) = _uiState.update { it.copy(dcTariff = value) }

    fun setCurrency(code: String) {
        val currency = findCurrency(code)
        _uiState.update {
            it.copy(currency = currency.code, currencySymbol = currency.symbol, currencyTouched = true)
        }
    }

    /** Applies the language right away so the following wizard steps render in it. */
    fun setLanguage(lang: String) {
        applyAppLanguage(appContext, localePreferences, lang)
        _uiState.update { state ->
            if (state.currencyTouched) {
                state.copy(language = lang)
            } else {
                // Follow the language until the user picks a currency: Chinese implies CNY
                val currency = findCurrency(currencyForLanguage(lang))
                state.copy(language = lang, currency = currency.code, currencySymbol = currency.symbol)
            }
        }
    }

    fun nextStep() = _uiState.update { it.copy(step = (it.step + 1).coerceAtMost(TOTAL_STEPS)) }
    fun prevStep() = _uiState.update { it.copy(step = (it.step - 1).coerceAtLeast(1)) }

    fun startBydMate() {
        viewModelScope.launch {
            // @ApplicationContext is not localized — resolve status strings via the app-selected language.
            val ctx = appContext.appLocalizedContext()
            _uiState.update { it.copy(isLoading = true, importStatus = ctx.getString(R.string.welcome_saving_settings_status)) }

            // Save all settings
            val state = _uiState.value
            settingsRepository.setString(SettingsRepository.KEY_BATTERY_CAPACITY, state.batteryCapacity)
            settingsRepository.setString(SettingsRepository.KEY_CURRENCY, state.currency)
            settingsRepository.setString(SettingsRepository.KEY_HOME_TARIFF, state.homeTariff)
            settingsRepository.setString(SettingsRepository.KEY_DC_TARIFF, state.dcTariff)

            // Detect upgrade vs fresh install
            val tripCount = tripRepository.getTripCount()
            val isUpgrade = tripCount > 0

            _uiState.update {
                it.copy(importStatus = if (isUpgrade)
                    ctx.getString(R.string.welcome_updating_data_status)
                else
                    ctx.getString(R.string.welcome_importing_trips_status))
            }

            if (isUpgrade) {
                historyImporter.deduplicateWithExisting()
            }
            historyImporter.runSync()

            // Mark setup complete
            settingsRepository.setSetupCompleted()

            // Start tracking service
            TrackingService.start(appContext)

            _uiState.update { it.copy(isLoading = false, isComplete = true) }
        }
    }

    companion object {
        const val TOTAL_STEPS = 3

        private fun currencyForLanguage(lang: String): String =
            if (lang == "zh") "CNY" else SettingsRepository.DEFAULT_CURRENCY

        private fun findCurrency(code: String): SettingsRepository.Currency =
            SettingsRepository.CURRENCIES.find { it.code == code } ?: SettingsRepository.CURRENCIES.first()
    }
}
