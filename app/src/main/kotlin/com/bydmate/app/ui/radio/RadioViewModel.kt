package com.bydmate.app.ui.radio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.data.local.entity.RadioStationEntity
import com.bydmate.app.data.radio.RadioDirectoryRepository
import com.bydmate.app.data.radio.RadioSearchResult
import com.bydmate.app.data.repository.RadioRepository
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
class RadioViewModel @Inject constructor(
    private val radioRepository: RadioRepository,
    private val directory: RadioDirectoryRepository,
) : ViewModel() {

    /** Directory search: idle until the user opens the finder and types something. */
    data class SearchState(
        val query: String = "",
        val loading: Boolean = false,
        val results: List<RadioSearchResult> = emptyList(),
        val searched: Boolean = false,
    )

    private val _search = MutableStateFlow(SearchState())
    val search: StateFlow<SearchState> = _search.asStateFlow()

    val directoryLabels: List<String> get() = directory.sourceLabels

    val stations: StateFlow<List<RadioStationEntity>> =
        radioRepository.getAll().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    /** Drives the Radio tab and the dashboard control. False until the user opts in. */
    val enabled: StateFlow<Boolean> =
        radioRepository.observeEnabled().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /** Swaps built-in stations to their lighter mount; user-added stations are unaffected. */
    val dataSaver: StateFlow<Boolean> =
        radioRepository.observeDataSaver().stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = false
        )

    /** How many built-in stations the last "restore" actually re-added (0 = nothing was missing). */
    private val _presetsRestored = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val presetsRestored = _presetsRestored.asSharedFlow()

    init {
        viewModelScope.launch {
            // No-op unless the feature is on: an opted-out user gets no rows written at all.
            if (radioRepository.isEnabled()) radioRepository.syncPresets()
        }
    }

    fun setEnabled(value: Boolean) {
        viewModelScope.launch { radioRepository.setEnabled(value) }
    }

    fun setDataSaver(value: Boolean) {
        viewModelScope.launch { radioRepository.setDataSaver(value) }
    }

    fun restorePresets() {
        viewModelScope.launch { _presetsRestored.emit(radioRepository.restorePresets()) }
    }

    fun onQueryChange(value: String) {
        _search.value = _search.value.copy(query = value)
    }

    fun runSearch() {
        val query = _search.value.query.trim()
        if (query.isEmpty()) return
        _search.value = _search.value.copy(loading = true)
        viewModelScope.launch {
            val found = directory.search(query)
            _search.value = _search.value.copy(loading = false, results = found, searched = true)
        }
    }

    fun clearSearch() {
        _search.value = SearchState()
    }

    /**
     * Adds a station picked from a directory, keeping its lighter stream when the directory
     * published one — that is what lets "Экономить трафик" work for stations we did not ship.
     */
    fun addFromDirectory(result: RadioSearchResult) {
        viewModelScope.launch {
            radioRepository.insert(
                RadioStationEntity(
                    name = result.name.trim(),
                    url = result.url.trim(),
                    iconUrl = result.iconUrl?.trim()?.takeIf { it.isNotEmpty() },
                    lowBitrateUrl = result.lowBitrateUrl?.trim()?.takeIf { it.isNotEmpty() },
                )
            )
        }
    }

    fun save(id: Long?, name: String, url: String, iconUrl: String?) {
        val cleanName = name.trim()
        val cleanUrl = url.trim()
        val cleanIcon = iconUrl?.trim()?.takeIf { it.isNotEmpty() }
        viewModelScope.launch {
            if (id == null || id == 0L) {
                radioRepository.insert(
                    RadioStationEntity(name = cleanName, url = cleanUrl, iconUrl = cleanIcon)
                )
            } else {
                val existing = radioRepository.getById(id) ?: return@launch
                radioRepository.update(
                    existing.copy(name = cleanName, url = cleanUrl, iconUrl = cleanIcon)
                )
            }
        }
    }

    fun delete(station: RadioStationEntity) {
        viewModelScope.launch { radioRepository.delete(station) }
    }
}
