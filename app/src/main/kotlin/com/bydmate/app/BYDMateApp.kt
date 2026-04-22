package com.bydmate.app

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Bundle
import android.provider.Settings
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.bydmate.app.data.local.DataThinningWorker
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
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
        registerActivityLifecycleCallbacks(WidgetLifecycleCallbacks(this))
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

    private class WidgetLifecycleCallbacks(private val app: Context) : ActivityLifecycleCallbacks {
        private var resumedCount = 0

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}

        override fun onActivityResumed(activity: Activity) {
            resumedCount++
            if (resumedCount == 1) {
                // Foregrounded → hide widget (service + data subscription stay alive elsewhere)
                WidgetController.detach()
            }
        }

        override fun onActivityPaused(activity: Activity) {
            resumedCount--
            if (resumedCount <= 0) {
                resumedCount = 0
                // Backgrounded → show widget if enabled + permitted
                val prefs = WidgetPreferences(app)
                if (prefs.isEnabled() && Settings.canDrawOverlays(app)) {
                    WidgetController.attach(app)
                }
            }
        }

        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
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
