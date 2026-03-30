package com.bydmate.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bydmate.app.data.local.DataThinningWorker
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.repository.SettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration as OsmdroidConfig
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BYDMateApp : Application(), Configuration.Provider {

    @Inject lateinit var historyImporter: HistoryImporter
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        initOsmdroid()
        appScope.launch {
            // One-time cleanup of existing duplicates from v2.0.0
            historyImporter.cleanupDuplicates()
            // Only sync if setup is completed (prevents duplicates during first wizard run)
            if (settingsRepository.isSetupCompleted()) {
                historyImporter.sync()
            }
        }
        scheduleDataThinning()
    }

    private fun initOsmdroid() {
        OsmdroidConfig.getInstance().apply {
            userAgentValue = packageName
            val basePath = File(filesDir, "osmdroid")
            basePath.mkdirs()
            osmdroidBasePath = basePath
            val tilePath = File(basePath, "tiles")
            tilePath.mkdirs()
            osmdroidTileCache = tilePath
            tileFileSystemCacheMaxBytes = 100L * 1024 * 1024
            tileFileSystemCacheTrimBytes = 80L * 1024 * 1024
            load(this@BYDMateApp, getSharedPreferences("osmdroid", MODE_PRIVATE))
        }
    }

    private fun scheduleDataThinning() {
        val request = PeriodicWorkRequestBuilder<DataThinningWorker>(
            1, TimeUnit.DAYS
        ).build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            DataThinningWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}
