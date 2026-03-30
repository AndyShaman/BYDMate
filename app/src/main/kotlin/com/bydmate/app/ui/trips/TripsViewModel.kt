package com.bydmate.app.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import javax.inject.Inject

enum class TripPeriod { TODAY, WEEK, MONTH, YEAR, ALL }
enum class TripFilter { ALL, TRIPS_ONLY, STOPS_ONLY }

data class MonthGroup(
    val yearMonth: String,     // "2026-03"
    val label: String,         // "Март 2026"
    val totalKm: Double,
    val totalKwh: Double,
    val avgConsumption: Double,
    val totalCost: Double,
    val days: List<DayGroup>
)

data class DayGroup(
    val date: String,          // "30.03"
    val dayOfWeek: String,     // "Пн"
    val totalKm: Double,
    val totalKwh: Double,
    val avgConsumption: Double,
    val totalCost: Double,
    val trips: List<TripEntity>
)

data class TripsUiState(
    val period: TripPeriod = TripPeriod.WEEK,
    val filter: TripFilter = TripFilter.ALL,
    val months: List<MonthGroup> = emptyList(),
    val expandedMonths: Set<String> = emptySet(),
    val expandedDays: Set<String> = emptySet(),
    val selectedTrip: TripEntity? = null,
    val selectedTripPoints: List<TripPointEntity> = emptyList(),
    val currencySymbol: String = "Br",
    // Legacy compat
    val trips: List<TripEntity> = emptyList(),
    val totalKm: Double = 0.0,
    val totalKwh: Double = 0.0,
    val avgConsumption: Double = 0.0,
    val totalCost: Double = 0.0,
    val periodLabel: String = "",
    val expandedTripId: Long? = null,
    val expandedTripPoints: List<TripPointEntity> = emptyList()
)

@HiltViewModel
class TripsViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val tripPointDao: TripPointDao,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripsUiState())
    val uiState: StateFlow<TripsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            val symbol = settingsRepository.getCurrencySymbol()
            _uiState.update { it.copy(currencySymbol = symbol) }
        }
        loadTrips()
    }

    fun setPeriod(period: TripPeriod) {
        _uiState.update { it.copy(period = period) }
        loadTrips()
    }

    @Suppress("UNUSED_PARAMETER")
    fun setPeriod(period: Period) {
        // Legacy compat
        setPeriod(when (period) {
            Period.WEEK -> TripPeriod.WEEK
            Period.MONTH -> TripPeriod.MONTH
        })
    }

    fun setFilter(filter: TripFilter) {
        _uiState.update { it.copy(filter = filter) }
        loadTrips()
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

    fun selectTrip(trip: TripEntity) {
        _uiState.update { it.copy(selectedTrip = trip, selectedTripPoints = emptyList()) }
        viewModelScope.launch {
            val points = tripPointDao.getByTripId(trip.id)
            _uiState.update { state ->
                if (state.selectedTrip?.id == trip.id) state.copy(selectedTripPoints = points)
                else state
            }
        }
    }

    fun clearSelectedTrip() {
        _uiState.update { it.copy(selectedTrip = null, selectedTripPoints = emptyList()) }
    }

    fun toggleTripExpansion(tripId: Long) {
        // Legacy compat
    }

    private fun loadTrips() {
        viewModelScope.launch {
            val state = _uiState.value
            val (from, to) = dateRangeFor(state.period)

            tripRepository.getTripsByDateRange(from, to).collect { allTrips ->
                val filtered = when (state.filter) {
                    TripFilter.ALL -> allTrips
                    TripFilter.TRIPS_ONLY -> allTrips.filter { (it.distanceKm ?: 0.0) > 0 }
                    TripFilter.STOPS_ONLY -> allTrips.filter { (it.distanceKm ?: 0.0) == 0.0 }
                }

                val months = groupIntoMonths(filtered)
                val totalKm = filtered.sumOf { it.distanceKm ?: 0.0 }
                val totalKwh = filtered.sumOf { it.kwhConsumed ?: 0.0 }
                val avgConsumption = if (totalKm > 0) totalKwh / totalKm * 100.0 else 0.0
                val totalCost = filtered.sumOf { it.cost ?: 0.0 }

                // Auto-expand first month and first day
                val autoExpandMonth = months.firstOrNull()?.yearMonth
                val autoExpandDay = months.firstOrNull()?.days?.firstOrNull()?.date

                _uiState.update { s ->
                    s.copy(
                        months = months,
                        trips = filtered,
                        totalKm = totalKm,
                        totalKwh = totalKwh,
                        avgConsumption = avgConsumption,
                        totalCost = totalCost,
                        expandedMonths = if (s.expandedMonths.isEmpty() && autoExpandMonth != null)
                            setOf(autoExpandMonth) else s.expandedMonths,
                        expandedDays = if (s.expandedDays.isEmpty() && autoExpandDay != null)
                            setOf(autoExpandDay) else s.expandedDays
                    )
                }
            }
        }
    }

    private fun groupIntoMonths(trips: List<TripEntity>): List<MonthGroup> {
        val monthKeyFmt = SimpleDateFormat("yyyy-MM", Locale.US)
        val dayKeyFmt = SimpleDateFormat("dd.MM", Locale.US)
        val dayOfWeekFmt = SimpleDateFormat("EEE", Locale("ru"))
        val monthLabelFmt = SimpleDateFormat("LLLL yyyy", Locale("ru"))

        val monthMap = linkedMapOf<String, MutableList<TripEntity>>()
        for (trip in trips) {
            val key = monthKeyFmt.format(Date(trip.startTs))
            monthMap.getOrPut(key) { mutableListOf() }.add(trip)
        }

        return monthMap.map { (monthKey, monthTrips) ->
            val dayMap = linkedMapOf<String, MutableList<TripEntity>>()
            for (trip in monthTrips) {
                val dayKey = dayKeyFmt.format(Date(trip.startTs))
                dayMap.getOrPut(dayKey) { mutableListOf() }.add(trip)
            }

            val days = dayMap.map { (dayKey, dayTrips) ->
                val km = dayTrips.sumOf { it.distanceKm ?: 0.0 }
                val kwh = dayTrips.sumOf { it.kwhConsumed ?: 0.0 }
                DayGroup(
                    date = dayKey,
                    dayOfWeek = dayOfWeekFmt.format(Date(dayTrips.first().startTs)),
                    totalKm = km,
                    totalKwh = kwh,
                    avgConsumption = if (km > 0) kwh / km * 100.0 else 0.0,
                    totalCost = dayTrips.sumOf { it.cost ?: 0.0 },
                    trips = dayTrips
                )
            }

            val km = monthTrips.sumOf { it.distanceKm ?: 0.0 }
            val kwh = monthTrips.sumOf { it.kwhConsumed ?: 0.0 }
            MonthGroup(
                yearMonth = monthKey,
                label = monthLabelFmt.format(Date(monthTrips.first().startTs))
                    .replaceFirstChar { it.uppercase() },
                totalKm = km,
                totalKwh = kwh,
                avgConsumption = if (km > 0) kwh / km * 100.0 else 0.0,
                totalCost = monthTrips.sumOf { it.cost ?: 0.0 },
                days = days
            )
        }
    }

    private fun dateRangeFor(period: TripPeriod): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        return when (period) {
            TripPeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            TripPeriod.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            TripPeriod.MONTH -> {
                cal.add(Calendar.DAY_OF_YEAR, -30)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            TripPeriod.YEAR -> {
                cal.add(Calendar.YEAR, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis to now
            }
            TripPeriod.ALL -> 0L to now
        }
    }
}

// Legacy enum for backward compatibility
enum class Period { WEEK, MONTH }
