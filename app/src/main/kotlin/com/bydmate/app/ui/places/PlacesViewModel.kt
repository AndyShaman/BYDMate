package com.bydmate.app.ui.places

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.PlaceEntity
import com.bydmate.app.data.repository.PlaceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
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
        viewModelScope.launch { placeRepository.delete(place) }
    }
}
