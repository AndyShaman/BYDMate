package com.bydmate.app.ui.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.split.AppSet
import com.bydmate.app.split.AppSetLauncher
import com.bydmate.app.split.AppSetStore
import com.bydmate.app.split.SplitPreferences
import com.bydmate.app.split.SplitPreferencesImpl
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** What the driver sees under a set after pressing «Запустить», and whether the sets are the split in use. */
@HiltViewModel
class AppSetsViewModel @Inject constructor(
    val store: AppSetStore,
    private val launcher: AppSetLauncher,
    private val splitPreferences: SplitPreferences,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val _lastRun = MutableStateFlow<AppSetLauncher.Outcome?>(null)
    val lastRun: StateFlow<AppSetLauncher.Outcome?> = _lastRun.asStateFlow()

    private val _splitOn = MutableStateFlow(splitPreferences.isFeatureEnabled())

    /**
     * «Разделение экрана 1/3 + 2/3» is on: it is the split in use and the sets rest
     * ([com.bydmate.app.split.appSetsActive]). Follows its switch in the same section live.
     */
    val splitOn: StateFlow<Boolean> = _splitOn.asStateFlow()

    private val splitFile = context.getSharedPreferences(SplitPreferencesImpl.PREFS_NAME, Context.MODE_PRIVATE)

    // A field on purpose: SharedPreferences holds its listeners weakly.
    private val splitListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SplitPreferencesImpl.KEY_FEATURE_ENABLED) _splitOn.value = splitPreferences.isFeatureEnabled()
    }

    init {
        splitFile.registerOnSharedPreferenceChangeListener(splitListener)
    }

    override fun onCleared() {
        splitFile.unregisterOnSharedPreferenceChangeListener(splitListener)
    }

    fun run(set: AppSet) {
        if (_splitOn.value) return
        viewModelScope.launch { _lastRun.value = launcher.launch(set) }
    }
}
