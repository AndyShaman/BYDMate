package com.bydmate.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.repository.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlacesViewModel @Inject constructor(
    private val placeRepository: PlaceRepository
) : ViewModel() {

    val places: StateFlow<List<PlaceEntity>> =
        placeRepository.getAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    // Map: placeId -> number of rules that reference it (0 = safe to delete).
    // Populated lazily when places list changes or after a delete attempt.
    private val _usageCounts = MutableStateFlow<Map<Long, Int>>(emptyMap())
    val usageCounts: StateFlow<Map<Long, Int>> = _usageCounts.asStateFlow()

    // Emitted when delete is blocked: carries the rule count so UI can show a message.
    private val _deleteBlocked = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val deleteBlocked = _deleteBlocked.asSharedFlow()

    init {
        // Refresh usage counts whenever the places list changes.
        viewModelScope.launch {
            places.collect { list ->
                refreshUsageCounts(list.map { it.id })
            }
        }
    }

    fun save(id: Long?, name: String, lat: Double, lon: Double, radiusM: Int) {
        viewModelScope.launch {
            if (id == null || id == 0L) {
                placeRepository.insert(
                    PlaceEntity(name = name.trim(), lat = lat, lon = lon, radiusM = radiusM)
                )
            } else {
                val existing = placeRepository.getById(id) ?: return@launch
                placeRepository.update(
                    existing.copy(name = name.trim(), lat = lat, lon = lon, radiusM = radiusM)
                )
            }
        }
    }

    fun delete(place: PlaceEntity) {
        viewModelScope.launch {
            val count = placeRepository.countRulesUsingPlace(place.id)
            if (count > 0) {
                // Refresh counts so UI reflects the current state, then block deletion.
                refreshUsageCounts(places.value.map { it.id })
                _deleteBlocked.emit(count)
                return@launch
            }
            placeRepository.delete(place)
        }
    }

    private suspend fun refreshUsageCounts(ids: List<Long>) {
        val map = ids.associateWith { id -> placeRepository.countRulesUsingPlace(id) }
        _usageCounts.value = map
    }
}
