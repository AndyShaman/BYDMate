package com.bydmate.app.ui.charges

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.dao.ChargePointDao
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.repository.ChargeRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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

/** UI state for the Charges screen. */
data class ChargesUiState(
    val charges: List<ChargeEntity> = emptyList(),
    val periodLabel: String = "",
    val sessionCount: Int = 0,
    val totalKwh: Double = 0.0,
    val totalCost: Double = 0.0,
    val typeFilter: String? = null,
    val expandedChargeId: Long? = null,
    val expandedChargePoints: List<ChargePointEntity> = emptyList()
)

/** Period filter for charges list. */
enum class Period { WEEK, MONTH }

@HiltViewModel
class ChargesViewModel @Inject constructor(
    private val chargeRepository: ChargeRepository,
    private val chargePointDao: ChargePointDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChargesUiState())
    val uiState: StateFlow<ChargesUiState> = _uiState.asStateFlow()

    private var currentPeriod: Period = Period.WEEK
    private var chargesJob: Job? = null

    init {
        loadCharges()
    }

    /** Switch between WEEK and MONTH period. */
    fun setPeriod(period: Period) {
        if (period == currentPeriod) return
        currentPeriod = period
        // Collapse any expanded card on period change
        _uiState.update { it.copy(expandedChargeId = null, expandedChargePoints = emptyList()) }
        loadCharges()
    }

    /** Set type filter: null = all, "AC", "DC". */
    fun setTypeFilter(type: String?) {
        if (type == _uiState.value.typeFilter) return
        _uiState.update { it.copy(typeFilter = type, expandedChargeId = null, expandedChargePoints = emptyList()) }
        loadCharges()
    }

    /** Toggle expanded state for a charge card, loading charge points on expand. */
    fun toggleExpanded(chargeId: Long) {
        val current = _uiState.value.expandedChargeId
        if (current == chargeId) {
            // Collapse
            _uiState.update { it.copy(expandedChargeId = null, expandedChargePoints = emptyList()) }
        } else {
            // Expand and load charge points
            _uiState.update { it.copy(expandedChargeId = chargeId, expandedChargePoints = emptyList()) }
            loadChargePoints(chargeId)
        }
    }

    /** Load charge points for the expanded charge session. */
    private fun loadChargePoints(chargeId: Long) {
        viewModelScope.launch {
            val points = chargePointDao.getByChargeId(chargeId)
            _uiState.update { state ->
                // Only update if this charge is still expanded
                if (state.expandedChargeId == chargeId) {
                    state.copy(expandedChargePoints = points)
                } else {
                    state
                }
            }
        }
    }

    /** Reload charges from the database based on current period and type filter. */
    private fun loadCharges() {
        chargesJob?.cancel()
        val (from, to) = periodRange(currentPeriod)
        val label = periodLabel(currentPeriod, from, to)

        chargesJob = viewModelScope.launch {
            // Load summary for the period
            val summary = chargeRepository.getPeriodSummary(from, to)

            // Observe charges in the date range
            chargeRepository.getChargesByDateRange(from, to).collect { allCharges ->
                val typeFilter = _uiState.value.typeFilter
                val filtered = if (typeFilter != null) {
                    allCharges.filter { it.type == typeFilter }
                } else {
                    allCharges
                }

                // Recalculate summary for filtered results
                val filteredCount = filtered.size
                val filteredKwh = filtered.sumOf { it.kwhCharged ?: 0.0 }
                val filteredCost = filtered.sumOf { it.cost ?: 0.0 }

                _uiState.update {
                    it.copy(
                        charges = filtered,
                        periodLabel = label,
                        sessionCount = filteredCount,
                        totalKwh = filteredKwh,
                        totalCost = filteredCost
                    )
                }
            }
        }
    }

    /** Calculate start/end timestamps for the given period. */
    private fun periodRange(period: Period): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        // End of today
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val to = cal.timeInMillis

        // Start of period
        when (period) {
            Period.WEEK -> cal.add(Calendar.DAY_OF_YEAR, -7)
            Period.MONTH -> cal.add(Calendar.MONTH, -1)
        }
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val from = cal.timeInMillis

        return Pair(from, to)
    }

    /** Format a human-readable label for the period. */
    private fun periodLabel(period: Period, from: Long, to: Long): String {
        val sdf = SimpleDateFormat("dd.MM", Locale.getDefault())
        val fromStr = sdf.format(Date(from))
        val toStr = sdf.format(Date(to))
        return when (period) {
            Period.WEEK -> "Неделя: $fromStr – $toStr"
            Period.MONTH -> "Месяц: $fromStr – $toStr"
        }
    }
}
