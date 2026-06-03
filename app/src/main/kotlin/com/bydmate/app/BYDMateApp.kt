package com.bydmate.app

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import org.lsposed.hiddenapibypass.HiddenApiBypass
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.bydmate.app.data.local.DataThinningWorker
import com.bydmate.app.data.local.HistoryImporter
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.decideLanguage
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.ui.widget.WidgetController
import com.bydmate.app.ui.widget.WidgetPreferences
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.osmdroid.config.Configuration as OsmdroidConfig
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.MapTileIndex
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltAndroidApp
class BYDMateApp : Application(), Configuration.Provider {

    @Inject lateinit var historyImporter: HistoryImporter
    @Inject lateinit var workerFactory: HiltWorkerFactory
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var chargeDao: ChargeDao
    @Inject lateinit var localePreferences: LocalePreferences
    @Inject lateinit var insightsManager: InsightsManager

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Allow in-process ServiceManager.getService() on Android 9+ to reach the helper
        // binder service without hidden-API restrictions (UnsatisfiedLinkError / NoSuchMethodError).
        // Guarded: under JVM/Robolectric unit tests the HiddenApiBypass static initializer throws
        // (no Android ART runtime), which would otherwise crash onCreate for every test that
        // instantiates this Application. Swallow there; on a real device the call succeeds.
        if (Build.VERSION.SDK_INT >= 28) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("Landroid/os/ServiceManager;")
            }.onFailure {
                android.util.Log.w("BYDMateApp", "hidden-api exemption unavailable", it)
            }
        }
        bootstrapLocale()
        initOsmdroid()
        appScope.launch {
            // v2.8.1: clear stale "DIPLUS" data_source value from pre-native-stack
            // builds. One-shot, gated by its own flag.
            settingsRepository.migrateDataSourceIfNeeded()

            if (!settingsRepository.isInsightCacheV2MigrationDone()) {
                insightsManager.migrateLegacyCache()
                settingsRepository.setInsightCacheV2MigrationDone()
            }
            // One-shot migration: remove phantom autoservice rows created by the
            // lifetime_kwh driving-counter bug in v2.4.15/v2.4.16.
            if (!settingsRepository.isMigrationV2_4_17Done()) {
                val removed = chargeDao.deletePhantomAutoserviceRows()
                settingsRepository.setMigrationV2_4_17Done()
                android.util.Log.i("BYDMateApp", "v2.4.17 migration: removed $removed phantom autoservice rows")
            }
            // One-time cleanup of existing duplicates from v2.0.0
            historyImporter.cleanupDuplicates()
            // Only sync if setup is completed (prevents duplicates during first wizard run)
            if (settingsRepository.isSetupCompleted()) {
                historyImporter.runSync()
            }
        }
        scheduleDataThinning()
        registerActivityLifecycleCallbacks(WidgetLifecycleCallbacks(this))
    }

    private fun bootstrapLocale() {
        // Already decided on a previous launch: AppCompat autoStoreLocales handles
        // applying the saved locale before this point, nothing to do.
        if (localePreferences.getLanguage() != null) return

        val setupCompleted = if (localePreferences.isSetupCompletedMirror()) {
            true
        } else {
            // First v2.8.0 launch - older versions didn't write the mirror flag.
            // Single Room read, gated to one-time, far below the ANR threshold.
            runBlocking { settingsRepository.isSetupCompleted() }
        }

        val lang = decideLanguage(setupCompleted)
        localePreferences.setLanguage(lang)
        if (setupCompleted) localePreferences.markSetupCompletedMirror()
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(lang))
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
                // User opened BYDMate → widget hides; also clear the
                // "hidden until app launch" long-press flag so it reappears
                // next time the app goes to background.
                WidgetPreferences(app).setHiddenUntilAppLaunch(false)
                WidgetController.setAppForegrounded(true)
            }
        }

        override fun onActivityPaused(activity: Activity) {
            resumedCount--
            if (resumedCount <= 0) {
                resumedCount = 0
                WidgetController.setAppForegrounded(false)
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

    companion object {
        /** 高德地图道路瓦片 — 中国大陆可用，免 API Key。
         *  查询参数风格 (?x=&y=&z=) 需要重写 getTileURLString。 */
        val AmapTileSource = object : OnlineTileSourceBase(
            "Amap", 0, 18, 256, ".png",
            arrayOf(
                "https://webrd01.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=%d&y=%d&z=%d",
                "https://webrd02.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=%d&y=%d&z=%d",
                "https://webrd03.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=%d&y=%d&z=%d",
                "https://webrd04.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=7&x=%d&y=%d&z=%d",
            ),
        ) {
            override fun getTileURLString(pMapTileIndex: Long): String {
                return String.format(
                    baseUrl,
                    MapTileIndex.getX(pMapTileIndex),
                    MapTileIndex.getY(pMapTileIndex),
                    MapTileIndex.getZoom(pMapTileIndex),
                )
            }
        }

        val OsmTileSource = TileSourceFactory.MAPNIK
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
