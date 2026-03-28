package com.bydmate.app.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.dao.TripPointDao
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.repository.TripRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject

/** Period filter for the trips list. */
enum class Period { WEEK, MONTH }

/** UI state exposed to the Trips screen. */
data class TripsUiState(
    val trips: List<TripEntity> = emptyList(),
    val periodLabel: String = "",
    val totalKm: Double = 0.0,
    val totalKwh: Double = 0.0,
    val avgConsumption: Double = 0.0,
    val expandedTripId: Long? = null,
    val expandedTripPoints: List<TripPointEntity> = emptyList()
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TripsViewModel @Inject constructor(
    private val tripRepository: TripRepository,
    private val tripPointDao: TripPointDao
) : ViewModel() {

    /** Currently selected period filter. */
    private val _period = MutableStateFlow(Period.WEEK)

    /** Mutable state holding expansion info (managed separately from reactive trips flow). */
    private val _expansion = MutableStateFlow<Pair<Long?, List<TripPointEntity>>>(null to emptyList())

    /** Main UI state derived from period selection + trips flow + expansion state. */
    val uiState: StateFlow<TripsUiState> = _period
        .flatMapLatest { period ->
            val (from, to) = dateRangeFor(period)
            val label = periodLabel(period)
            tripRepository.getTripsByDateRange(from, to).map { trips ->
                Triple(trips, label, period)
            }
        }
        .map { (trips, label, _) ->
            // Calculate period totals
            val totalKm = trips.sumOf { it.distanceKm ?: 0.0 }
            val totalKwh = trips.sumOf { it.kwhConsumed ?: 0.0 }
            val avgConsumption = if (totalKm > 0.0) totalKwh / totalKm * 100.0 else 0.0
            val (expandedId, expandedPoints) = _expansion.value

            TripsUiState(
                trips = trips,
                periodLabel = label,
                totalKm = totalKm,
                totalKwh = totalKwh,
                avgConsumption = avgConsumption,
                expandedTripId = expandedId,
                expandedTripPoints = expandedPoints
            )
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = TripsUiState()
        )

    /** Switch the active period filter. */
    fun setPeriod(period: Period) {
        _period.value = period
        // Collapse any expanded trip when switching periods
        _expansion.value = null to emptyList()
    }

    /** Toggle trip detail expansion; loads trip points when expanding. */
    fun toggleTripExpansion(tripId: Long) {
        val current = _expansion.value
        if (current.first == tripId) {
            // Collapse
            _expansion.update { null to emptyList() }
        } else {
            // Expand: load trip points asynchronously
            _expansion.update { tripId to emptyList() }
            viewModelScope.launch {
                val points = tripPointDao.getByTripId(tripId)
                // Only update if this trip is still expanded
                _expansion.update { (id, _) ->
                    if (id == tripId) tripId to points else id to emptyList()
                }
            }
        }
    }

    // -- Date range helpers --

    /**
     * Returns a pair of epoch-millisecond timestamps (from, to) for the given period,
     * ending at "now" and starting either 7 or 30 days ago at midnight.
     */
    private fun dateRangeFor(period: Period): Pair<Long, Long> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            when (period) {
                Period.WEEK -> add(Calendar.DAY_OF_YEAR, -7)
                Period.MONTH -> add(Calendar.DAY_OF_YEAR, -30)
            }
            // Start of that day
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis to now
    }

    /** Human-readable label for the active period. */
    private fun periodLabel(period: Period): String = when (period) {
        Period.WEEK -> "7 дней"
        Period.MONTH -> "30 дней"
    }
}
