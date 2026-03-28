package com.bydmate.app

import android.app.Application
import com.bydmate.app.data.local.HistoryImporter
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class BYDMateApp : Application() {

    @Inject lateinit var historyImporter: HistoryImporter

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        appScope.launch {
            historyImporter.sync()
        }
    }
}
