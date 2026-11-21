package com.bydmate.app.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.split.AppSet
import com.bydmate.app.split.AppSetAutostart
import com.bydmate.app.split.AppSetLauncher
import com.bydmate.app.split.AppSetStore
import com.bydmate.app.split.SplitPreferences
import com.bydmate.app.cluster.ClusterProjectionManager
import com.bydmate.app.cluster.ClusterEntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The app sets of the «Разделение экрана» section (Android 10 head units): what the driver sees
 * under a set after pressing «Запустить», «Пополам 1:1» ([AppSetStore.setsMode]) and «Открывать
 * при запуске», one switch for every mechanism.
 */
@HiltViewModel
class AppSetsViewModel @Inject constructor(
    val store: AppSetStore,
    private val launcher: AppSetLauncher,
    private val autostart: AppSetAutostart,
    splitPreferences: SplitPreferences,
    @ApplicationContext context: Context,
) : ViewModel() {
    private val _lastRun = MutableStateFlow<AppSetLauncher.Outcome?>(null)
    val lastRun: StateFlow<AppSetLauncher.Outcome?> = _lastRun.asStateFlow()

    init {
        // The screen may open before the service ran it: the choice shows what the head unit runs.
        store.migrateSetsMode(splitPreferences) {
            val ep = EntryPointAccessors.fromApplication(context, ClusterEntryPoint::class.java)
            ClusterProjectionManager.realignFreeformFlag(context, ep.helperClient(), ep.helperBootstrap(), force = true)
        }
    }

    fun autostartEnabled(): Boolean = autostart.isEnabled()

    fun setAutostart(enabled: Boolean) = autostart.setEnabled(enabled)

    fun run(set: AppSet) {
        viewModelScope.launch { _lastRun.value = launcher.launch(set) }
    }
}
