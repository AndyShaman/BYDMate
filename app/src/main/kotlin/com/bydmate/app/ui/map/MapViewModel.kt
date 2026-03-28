package com.bydmate.app.ui.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.ChargeEntity
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

/**
 * UI state for the Map screen.
 * Contains GPS routes grouped by trip and charge location markers.
 */
data class MapUiState(
    val tripPoints: List<List<TripPointEntity>> = emptyList(),
    val charges: List<ChargeEntity> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class MapViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val chargeRepository: ChargeRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    init {
        loadLastWeekData()
    }

    /**
     * Load trip points and charge sessions from the last 7 days.
     * Trip points are grouped by trip ID so each route can be drawn as a separate polyline.
     */
    private fun loadLastWeekData() {
        viewModelScope.launch {
            val (weekStart, weekEnd) = lastWeekRange()

            // Collect trips from last week, then load points for each
            tripRepository.getTripsByDateRange(weekStart, weekEnd).collect { trips ->
                val allRoutes = mutableListOf<List<TripPointEntity>>()
                for (trip in trips) {
                    val points = tripRepository.getTripPoints(trip.id)
                    if (points.isNotEmpty()) {
                        allRoutes.add(points)
                    }
                }
                _uiState.update { it.copy(tripPoints = allRoutes) }
            }
        }

        viewModelScope.launch {
            val (weekStart, weekEnd) = lastWeekRange()

            // Collect charges from last week for location markers
            chargeRepository.getChargesByDateRange(weekStart, weekEnd).collect { charges ->
                _uiState.update { it.copy(charges = charges, isLoading = false) }
            }
        }
    }

    /** Returns start and end timestamps (millis) for the last 7 days. */
    private fun lastWeekRange(): Pair<Long, Long> {
        val cal = Calendar.getInstance()

        // End: now
        val end = cal.timeInMillis

        // Start: 7 days ago at midnight
        cal.add(Calendar.DAY_OF_YEAR, -7)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis

        return Pair(start, end)
    }
}
