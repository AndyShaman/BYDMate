package com.bydmate.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

enum class MapPeriod { DAY, WEEK, MONTH }

data class TripRoute(
    val points: List<TripPointEntity>,
    val kwhPer100km: Double?
)

data class MapUiState(
    val tripRoutes: List<TripRoute> = emptyList(),
    val charges: List<ChargeEntity> = emptyList(),
    val isLoading: Boolean = true,
    val period: MapPeriod = MapPeriod.WEEK,
    val panelExpanded: Boolean = true,
    val totalKm: Double = 0.0,
    val totalKwh: Double = 0.0,
    val avgConsumption: Double = 0.0,
    val trips: List<TripEntity> = emptyList()
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val chargeRepository: ChargeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        loadData()
    }

    fun setPeriod(period: MapPeriod) {
        _uiState.update { it.copy(period = period, isLoading = true) }
        loadData()
    }

    fun togglePanel() {
        _uiState.update { it.copy(panelExpanded = !it.panelExpanded) }
    }

    private fun loadData() {
        viewModelScope.launch {
            val (start, end) = periodRange(_uiState.value.period)

            tripRepository.getTripsByDateRange(start, end).collect { trips ->
                val tripIds = trips.map { it.id }
                val pointsByTrip = tripRepository.getTripPointsByTripIds(tripIds)
                val allRoutes = trips.mapNotNull { trip ->
                    val points = pointsByTrip[trip.id]
                    if (!points.isNullOrEmpty()) TripRoute(points, trip.kwhPer100km) else null
                }
                val totalKm = trips.mapNotNull { it.distanceKm }.sum()
                val totalKwh = trips.mapNotNull { it.kwhConsumed }.sum()
                val avg = if (totalKm > 0) totalKwh / totalKm * 100.0 else 0.0

                _uiState.update {
                    it.copy(
                        tripRoutes = allRoutes,
                        trips = trips,
                        totalKm = totalKm,
                        totalKwh = totalKwh,
                        avgConsumption = avg
                    )
                }
            }
        }

        viewModelScope.launch {
            val (start, end) = periodRange(_uiState.value.period)
            chargeRepository.getChargesByDateRange(start, end).collect { charges ->
                _uiState.update { it.copy(charges = charges, isLoading = false) }
            }
        }
    }

    private fun periodRange(period: MapPeriod): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val end = cal.timeInMillis
        val days = when (period) {
            MapPeriod.DAY -> 1
            MapPeriod.WEEK -> 7
            MapPeriod.MONTH -> 30
        }
        cal.add(Calendar.DAY_OF_YEAR, -days)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return Pair(cal.timeInMillis, end)
    }
}
