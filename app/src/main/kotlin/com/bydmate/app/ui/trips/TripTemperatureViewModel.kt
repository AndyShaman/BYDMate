package com.bydmate.app.ui.trips

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.domain.trips.TripTemperatureStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TripTemperatureUiState(
    val loading: Boolean = true,
    val stats: TripTemperatureStats = TripTemperatureStats(emptyList()),
)

/**
 * Feeds the «Расход и температура» screen: one read of the whole trip table on open, all of the
 * maths in [TripTemperatureStats]. The period is deliberately «everything» — a temperature
 * picture needs a year, not the week the list happens to show.
 */
@HiltViewModel
class TripTemperatureViewModel @Inject constructor(
    private val tripRepository: TripRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripTemperatureUiState())
    val uiState: StateFlow<TripTemperatureUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val trips = tripRepository.getAllTrips().first()
        val withTemp = trips.mapNotNull { trip ->
            val temp = tripTemperature(trip) ?: return@mapNotNull null
            val per100 = trip.kwhPer100km ?: return@mapNotNull null
            TripTemperatureStats.Trip(temp, per100, trip.distanceKm ?: 0.0)
        }
        val stats = TripTemperatureStats(withTemp)
        val range = stats.observedRange?.let { (lo, hi) -> "$lo..$hi" } ?: "-"
        Log.i(
            TAG,
            "temp chart: trips=${trips.size} with_temp=${withTemp.size} shown=${stats.shown} " +
                "range=$range bins=${stats.filledBins} state=${stats.state} " +
                "medians=${stats.medianCold ?: "-"}/${stats.medianMild ?: "-"}/${stats.medianWarm ?: "-"}"
        )
        _uiState.value = TripTemperatureUiState(loading = false, stats = stats)
    }

    internal companion object {
        const val TAG = "TripTemperature"

        /**
         * Outside temperature of a drive: the mean of the two ends, or whichever end is known.
         * Trips recorded before the app collected temperature have neither and drop out.
         */
        fun tripTemperature(trip: TripEntity): Double? {
            val start = trip.exteriorTemp
            val end = trip.exteriorTempEnd
            return when {
                start != null && end != null -> (start + end) / 2.0
                start != null -> start.toDouble()
                end != null -> end.toDouble()
                else -> null
            }
        }
    }
}
