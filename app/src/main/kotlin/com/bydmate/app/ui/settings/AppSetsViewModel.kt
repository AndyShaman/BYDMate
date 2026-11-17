package com.bydmate.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.split.AppSet
import com.bydmate.app.split.AppSetLauncher
import com.bydmate.app.split.AppSetStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the driver sees under a set after pressing «Запустить». */
@HiltViewModel
class AppSetsViewModel @Inject constructor(
    val store: AppSetStore,
    private val launcher: AppSetLauncher,
) : ViewModel() {
    private val _lastRun = MutableStateFlow<AppSetLauncher.Outcome?>(null)
    val lastRun: StateFlow<AppSetLauncher.Outcome?> = _lastRun.asStateFlow()

    fun run(set: AppSet) {
        viewModelScope.launch { _lastRun.value = launcher.launch(set) }
    }
}
