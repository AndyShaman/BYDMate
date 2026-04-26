package com.bydmate.app.ui.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.dao.BatterySnapshotDao
import com.bydmate.app.data.local.dao.ChargeSummary
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class ChargesPeriod { TODAY, WEEK, MONTH, YEAR, ALL }
enum class ChargeTypeFilter { ALL, AC, DC }

data class ChargesMonthGroup(
    val yearMonth: String,
    val label: String,
    val totalKwh: Double,
    val sessionCount: Int,
    val totalCost: Double,
    val days: List<ChargesDayGroup>
)

data class ChargesDayGroup(
    val date: String,
    val label: String,
    val dayOfWeek: String,
    val totalKwh: Double,
    val sessionCount: Int,
    val totalCost: Double,
    val charges: List<ChargeEntity>
)

data class ChargesUiState(
    val period: ChargesPeriod = ChargesPeriod.MONTH,
    val typeFilter: ChargeTypeFilter = ChargeTypeFilter.ALL,
    val months: List<ChargesMonthGroup> = emptyList(),
    val expandedMonths: Set<String> = emptySet(),
    val expandedDays: Set<String> = emptySet(),
    val periodSummary: ChargeSummary = ChargeSummary(0, 0.0, 0.0),
    val currencySymbol: String = "BYN",
    val autoserviceEnabled: Boolean = false,
    val autoserviceConnected: Boolean = false,
    val autoserviceAllSentinel: Boolean = false,
    val hasLegacyCharges: Boolean = false,
    val lifetimeAcKwh: Double = 0.0,
    val lifetimeDcKwh: Double = 0.0,
    val lifetimeTotalKwh: Double = 0.0,
    val equivCycles: Double = 0.0,
    val nominalCapacityKwh: Double = 72.9,
    val sohSeries: List<Float> = emptyList(),
    val capacitySeries: List<Float> = emptyList(),
    val selectedChargeForAction: ChargeEntity? = null,
    val editingCharge: ChargeEntity? = null,
    val deleteConfirmCharge: ChargeEntity? = null,
    val homeTariff: Double = 0.20,
    val dcTariff: Double = 0.73,
)

@HiltViewModel
class ChargesViewModel @Inject constructor(
    private val chargeRepository: ChargeRepository,
    private val batterySnapshotDao: BatterySnapshotDao,
    private val settingsRepository: SettingsRepository,
    private val batteryStateRepository: BatteryStateRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargesUiState())
    val uiState: StateFlow<ChargesUiState> = _uiState.asStateFlow()

    private var loadJob: Job? = null

    init {
        viewModelScope.launch {
            val symbol = settingsRepository.getCurrencySymbol()
            val nominal = settingsRepository.getBatteryCapacity()
            val home = settingsRepository.getHomeTariff()
            val dc = settingsRepository.getDcTariff()
            _uiState.update {
                it.copy(
                    currencySymbol = symbol,
                    nominalCapacityKwh = nominal,
                    homeTariff = home,
                    dcTariff = dc,
                )
            }
        }
        loadAutoserviceState()
        loadAll()
        loadLifetimeStats()
        loadHealthSeries()
    }

    fun setPeriod(period: ChargesPeriod) {
        _uiState.update {
            it.copy(period = period, expandedMonths = emptySet(), expandedDays = emptySet())
        }
        loadAll(period = period)
    }

    fun setTypeFilter(filter: ChargeTypeFilter) {
        _uiState.update {
            it.copy(typeFilter = filter, expandedMonths = emptySet(), expandedDays = emptySet())
        }
        loadAll(typeFilter = filter)
    }

    fun toggleMonth(yearMonth: String) {
        _uiState.update { state ->
            val expanded = state.expandedMonths.toMutableSet()
            if (yearMonth in expanded) expanded.remove(yearMonth) else expanded.add(yearMonth)
            state.copy(expandedMonths = expanded)
        }
    }

    fun toggleDay(date: String) {
        _uiState.update { state ->
            val expanded = state.expandedDays.toMutableSet()
            if (date in expanded) expanded.remove(date) else expanded.add(date)
            state.copy(expandedDays = expanded)
        }
    }

    private fun loadAll(
        period: ChargesPeriod = _uiState.value.period,
        typeFilter: ChargeTypeFilter = _uiState.value.typeFilter
    ) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val (from, to) = dateRangeFor(period)

            chargeRepository.getChargesByDateRange(from, to).collect { rawCharges ->
                val nonEmpty = rawCharges.filter { (it.kwhCharged ?: 0.0) >= 0.05 }
                val filtered = when (typeFilter) {
                    ChargeTypeFilter.ALL -> nonEmpty
                    ChargeTypeFilter.AC -> nonEmpty.filter { it.gunState == 2 }
                    ChargeTypeFilter.DC -> nonEmpty.filter { it.gunState in setOf(3, 4) }
                }

                val months = groupChargesByMonthDay(filtered)

                val periodSummary = ChargeSummary(
                    sessionCount = filtered.size,
                    totalKwh = filtered.sumOf { it.kwhCharged ?: 0.0 },
                    totalCost = filtered.sumOf { it.cost ?: 0.0 }
                )

                val autoExpandMonth = months.firstOrNull()?.yearMonth
                val autoExpandDay = months.firstOrNull()?.days?.firstOrNull()?.date

                _uiState.update { s ->
                    s.copy(
                        months = months,
                        periodSummary = periodSummary,
                        expandedMonths = if (s.expandedMonths.isEmpty() && autoExpandMonth != null)
                            setOf(autoExpandMonth) else s.expandedMonths,
                        expandedDays = if (s.expandedDays.isEmpty() && autoExpandDay != null)
                            setOf(autoExpandDay) else s.expandedDays
                    )
                }
            }
        }
    }

    internal fun groupChargesByMonthDay(charges: List<ChargeEntity>): List<ChargesMonthGroup> {
        val monthKeyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
        val dayKeyFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val dayOfWeekFmt = SimpleDateFormat("EEE", Locale("ru"))
        val monthLabelFmt = SimpleDateFormat("LLLL yyyy", Locale("ru"))

        val todayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val todayKey = dayKeyFmt.format(todayCal.time)
        val yesterdayCal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, -1)
        }
        val yesterdayKey = dayKeyFmt.format(yesterdayCal.time)

        val monthMap = linkedMapOf<String, MutableList<ChargeEntity>>()
        for (charge in charges) {
            val key = monthKeyFmt.format(Date(charge.startTs))
            monthMap.getOrPut(key) { mutableListOf() }.add(charge)
        }

        return monthMap.entries
            .sortedByDescending { it.key }
            .map { (monthKey, monthCharges) ->
                val dayMap = linkedMapOf<String, MutableList<ChargeEntity>>()
                for (charge in monthCharges) {
                    val dk = dayKeyFmt.format(Date(charge.startTs))
                    dayMap.getOrPut(dk) { mutableListOf() }.add(charge)
                }

                val days = dayMap.entries
                    .sortedByDescending { it.key }
                    .map { (dayKey, dayCharges) ->
                        val dow = dayOfWeekFmt.format(Date(dayCharges.first().startTs))
                        val dayLabel = when (dayKey) {
                            todayKey -> "сегодня ($dow)"
                            yesterdayKey -> "вчера"
                            else -> {
                                val ddMmm = SimpleDateFormat("dd MMM", Locale("ru")).format(Date(dayCharges.first().startTs))
                                "$ddMmm ($dow)"
                            }
                        }
                        ChargesDayGroup(
                            date = dayKey,
                            label = dayLabel,
                            dayOfWeek = dow,
                            totalKwh = dayCharges.sumOf { it.kwhCharged ?: 0.0 },
                            sessionCount = dayCharges.size,
                            totalCost = dayCharges.sumOf { it.cost ?: 0.0 },
                            charges = dayCharges
                        )
                    }

                val firstTs = monthCharges.first().startTs
                ChargesMonthGroup(
                    yearMonth = monthKey,
                    label = monthLabelFmt.format(Date(firstTs)).replaceFirstChar { it.uppercase() },
                    totalKwh = monthCharges.sumOf { it.kwhCharged ?: 0.0 },
                    sessionCount = monthCharges.size,
                    totalCost = monthCharges.sumOf { it.cost ?: 0.0 },
                    days = days
                )
            }
    }

    private fun loadAutoserviceState() {
        viewModelScope.launch {
            val enabled = settingsRepository.isAutoserviceEnabled()
            val legacyExists = chargeRepository.hasLegacyCharges()

            val disconnectedDefault = com.bydmate.app.domain.battery.BatteryState(
                socNow = null, voltage12v = null, sohPercent = null,
                lifetimeKm = null, lifetimeKwh = null, autoserviceAvailable = false
            )
            val state = runCatching { batteryStateRepository.refresh() }
                .getOrDefault(disconnectedDefault)

            val connected = state.autoserviceAvailable
            val allSentinel = connected &&
                    state.socNow == null && state.lifetimeKm == null && state.lifetimeKwh == null

            _uiState.update {
                it.copy(
                    autoserviceEnabled = enabled,
                    autoserviceConnected = connected,
                    autoserviceAllSentinel = allSentinel,
                    hasLegacyCharges = legacyExists
                )
            }
        }
    }

    private fun loadLifetimeStats() {
        viewModelScope.launch {
            val stats = runCatching { chargeRepository.getLifetimeStats() }.getOrNull()
                ?: return@launch
            val nominal = _uiState.value.nominalCapacityKwh
            val equiv = if (nominal > 0) stats.totalKwhAdded / nominal else 0.0
            _uiState.update {
                it.copy(
                    lifetimeAcKwh = stats.acKwh,
                    lifetimeDcKwh = stats.dcKwh,
                    lifetimeTotalKwh = stats.totalKwhAdded,
                    equivCycles = equiv
                )
            }
        }
    }

    private fun loadHealthSeries() {
        viewModelScope.launch {
            val snapshots = batterySnapshotDao.getAll().first().reversed()
            val soh = snapshots.mapNotNull { it.sohPercent?.toFloat() }
            val cap = snapshots.mapNotNull { it.calculatedCapacityKwh?.toFloat() }
            _uiState.update { it.copy(sohSeries = soh, capacitySeries = cap) }
        }
    }

    private fun dateRangeFor(period: ChargesPeriod): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        return when (period) {
            ChargesPeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ChargesPeriod.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ChargesPeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ChargesPeriod.YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            ChargesPeriod.ALL -> 0L to now
        }
    }

    fun onLongPressCharge(charge: ChargeEntity) {
        _uiState.update { it.copy(selectedChargeForAction = charge) }
    }

    fun onDismissActionSheet() {
        _uiState.update { it.copy(selectedChargeForAction = null) }
    }

    fun onEditCharge() {
        _uiState.update { it.copy(editingCharge = it.selectedChargeForAction, selectedChargeForAction = null) }
    }

    fun onDismissEdit() {
        _uiState.update { it.copy(editingCharge = null) }
    }

    fun onCreateNewCharge() {
        val now = System.currentTimeMillis()
        val draft = ChargeEntity(
            id = 0L,
            startTs = now,
            endTs = now + 3_600_000L,
            type = "AC",
            gunState = 2,
            detectionSource = "manual",
        )
        _uiState.update { it.copy(editingCharge = draft) }
    }

    fun onSaveEdit(updated: ChargeEntity) {
        viewModelScope.launch {
            if (updated.id == 0L) {
                chargeRepository.insertCharge(updated)
            } else {
                chargeRepository.updateCharge(updated)
            }
            _uiState.update { it.copy(editingCharge = null) }
        }
    }

    fun onConfirmDeletePrompt() {
        _uiState.update { it.copy(deleteConfirmCharge = it.selectedChargeForAction, selectedChargeForAction = null) }
    }

    fun onDismissDeleteConfirm() {
        _uiState.update { it.copy(deleteConfirmCharge = null) }
    }

    fun onConfirmDelete() {
        viewModelScope.launch {
            val charge = _uiState.value.deleteConfirmCharge ?: return@launch
            chargeRepository.deleteCharge(charge)
            _uiState.update { it.copy(deleteConfirmCharge = null) }
        }
    }
}
