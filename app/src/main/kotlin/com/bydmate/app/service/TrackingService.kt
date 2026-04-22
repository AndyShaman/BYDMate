package com.bydmate.app.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bydmate.app.MainActivity
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.remote.AlicePollingManager
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.domain.tracker.ChargeTracker
import com.bydmate.app.domain.tracker.TripState
import com.bydmate.app.domain.tracker.ChargeState
import com.bydmate.app.domain.tracker.TripTracker
import com.bydmate.app.domain.calculator.ConsumptionAggregator
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : Service(), LocationListener {

    @Inject lateinit var diParsClient: DiParsClient
    @Inject lateinit var tripTracker: TripTracker
    @Inject lateinit var chargeTracker: ChargeTracker
    @Inject lateinit var chargeRepository: ChargeRepository
    @Inject lateinit var tripRepository: com.bydmate.app.data.repository.TripRepository
    @Inject lateinit var historyImporter: com.bydmate.app.data.local.HistoryImporter
    @Inject lateinit var diPlusDbReader: com.bydmate.app.data.remote.DiPlusDbReader
    @Inject lateinit var settingsRepository: com.bydmate.app.data.repository.SettingsRepository
    @Inject lateinit var insightsManager: com.bydmate.app.data.remote.InsightsManager
    @Inject lateinit var automationEngine: AutomationEngine
    @Inject lateinit var alicePollingManager: AlicePollingManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var consecutiveNullCount = 0
    private var firstDataReceived = false
    private var currentPollIntervalMs = POLL_INTERVAL_MS

    // Cached values for range estimation in notification.
    // Updated at service start + after history sync (new trips → TripRepository invalidates its own EMA cache).
    @Volatile private var cachedEmaConsumption: Double = 0.0
    @Volatile private var cachedWeeklyEmaConsumption: Double = 0.0
    @Volatile private var cachedBatteryCapacity: Double = 72.9

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bydmate_tracking"
        private const val POLL_INTERVAL_MS = 3000L // 3 seconds for detailed GPS + charging curve
        private const val NULL_WARNING_THRESHOLD = 5
        private const val MAX_POLL_INTERVAL_MS = 60_000L

        private val _lastData = MutableStateFlow<DiParsData?>(null)
        val lastData: StateFlow<DiParsData?> = _lastData

        private val _lastRangeKm = MutableStateFlow<Double?>(null)
        val lastRangeKm: StateFlow<Double?> = _lastRangeKm

        private val _lastLocation = MutableStateFlow<Location?>(null)
        val lastLocation: StateFlow<Location?> = _lastLocation

        private val _tripStartedAt = MutableStateFlow<Long?>(null)
        val tripStartedAt: StateFlow<Long?> = _tripStartedAt

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

        private val _diPlusConnected = MutableStateFlow(true)
        val diPlusConnected: StateFlow<Boolean> = _diPlusConnected

        fun start(context: Context) {
            val intent = Intent(context, TrackingService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TrackingService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: starting TrackingService")
        appendChainLog("TrackingService onCreate")
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Запуск…"))
        appendChainLog("startForeground OK")
        acquireWakeLock()
        startLocationUpdates()
        startPolling()
        _isRunning.value = true
        appendChainLog("TrackingService fully started")

        // Start Smart Home polling if configured
        serviceScope.launch {
            val enabled = settingsRepository.getString(
                com.bydmate.app.data.repository.SettingsRepository.KEY_ALICE_ENABLED, "false"
            ) == "true"
            if (enabled) alicePollingManager.start()
        }

        // Finalize stale SUSPENDED charge sessions from previous runs
        serviceScope.launch {
            try {
                val cutoff = System.currentTimeMillis() - 30 * 60 * 1000L
                val stale = chargeRepository.getStaleSessions(cutoff)
                stale.forEach { chargeTracker.finalizeSuspended(it) }
                if (stale.isNotEmpty()) {
                    Log.d(TAG, "Finalized ${stale.size} stale suspended charge sessions")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to finalize stale sessions: ${e.message}")
            }
        }

        // Initial load of consumption cache for notification range estimate.
        // Will also be refreshed after history sync below (if new trips appeared).
        refreshConsumptionCache()

        // Mirror TripTracker's tripStartedAt into companion StateFlow for UI consumers
        serviceScope.launch {
            tripTracker.tripStartedAt.collect { _tripStartedAt.value = it }
        }

        // v2.0: event-based sync on service start
        serviceScope.launch {
            try {
                // One-time: remove inflated idle drain from live power integration
                historyImporter.cleanupIdleDrainV2()
                val result = historyImporter.syncFromEnergyData()
                Log.i(TAG, "Sync: ${result.details ?: result.error ?: "ok"}")
                historyImporter.enrichWithDiPlus()
                // One-time fix: recalculate consumption from BMS (energydata)
                historyImporter.recalculateConsumptionFromEnergyData()
                historyImporter.calculateMissingCosts(settingsRepository.getTripCostTariff())
                historyImporter.attachGpsPoints()
                // Also import charging sessions
                diPlusDbReader.importChargingLog()
                // AI insights (once per day)
                insightsManager.refreshIfNeeded()
                // After sync (possibly new trips) — refresh EMA for notification
                refreshConsumptionCache()
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed: ${e.message}")
            }
        }
    }

    private fun refreshConsumptionCache() {
        serviceScope.launch {
            try {
                cachedEmaConsumption = tripRepository.getEmaConsumption()
                cachedWeeklyEmaConsumption = tripRepository.getWeeklyEmaConsumption()
                cachedBatteryCapacity = settingsRepository.getBatteryCapacity()
                Log.d(TAG, "Consumption cache: ema=${"%.2f".format(cachedEmaConsumption)} kWh/100km, " +
                    "weekly=${"%.2f".format(cachedWeeklyEmaConsumption)} kWh/100km, cap=$cachedBatteryCapacity kWh")
            } catch (e: Exception) {
                Log.w(TAG, "refreshConsumptionCache failed: ${e.message}")
            }
        }
    }

    private fun estimateRangeKm(soc: Int?): Double? {
        val socVal = soc ?: return null
        if (socVal <= 0) return null
        val ema = cachedEmaConsumption
        val cap = cachedBatteryCapacity
        if (ema <= 0.0 || cap <= 0.0) return null
        return socVal / 100.0 * cap / ema * 100.0
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        maybeAttachWidget()
        return START_STICKY
    }

    private fun maybeAttachWidget() {
        val prefs = com.bydmate.app.ui.widget.WidgetPreferences(this)
        if (prefs.isEnabled() && android.provider.Settings.canDrawOverlays(this)) {
            // ActivityLifecycleCallbacks detaches the widget when an Activity is resumed,
            // so calling attach unconditionally here is safe.
            com.bydmate.app.ui.widget.WidgetController.attach(this)
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping TrackingService")
        com.bydmate.app.ui.widget.WidgetController.detach()
        appendChainLog("TrackingService onDestroy")
        pollingJob?.cancel()
        ConsumptionAggregator.reset()
        _tripStartedAt.value = null

        // Force-end active trip/charge sessions asynchronously
        // Android gives ~5 seconds after onDestroy before killing process
        val shutdownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        shutdownScope.launch {
            try {
                withTimeout(4000L) {
                    val lastData = _lastData.value
                    val lastLoc = _lastLocation.value
                    tripTracker.forceEnd(lastData, lastLoc)
                    chargeTracker.forceEnd(lastData)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Graceful shutdown: ${e.message}")
            }
        }

        alicePollingManager.stop()
        automationEngine.shutdown()
        serviceScope.cancel()

        // Remove GPS listener to prevent leak
        try {
            locationManager?.removeUpdates(this)
            Log.d(TAG, "Location updates removed")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove location updates: ${e.message}")
        }

        wakeLock?.let { if (it.isHeld) it.release() }
        _isRunning.value = false

        // Auto-restart via WorkManager (like BydConnect AutoRestartReceiver)
        try {
            val request = OneTimeWorkRequestBuilder<ServiceStartWorker>().build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                ServiceStartWorker.WORK_NAME,
                ExistingWorkPolicy.KEEP,
                request
            )
            Log.i(TAG, "Restart scheduled via WorkManager")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule restart: ${e.message}")
        }

        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: scheduling restart via WorkManager")
        appendChainLog("onTaskRemoved → restart")
        try {
            val request = OneTimeWorkRequestBuilder<ServiceStartWorker>().build()
            WorkManager.getInstance(this).enqueueUniqueWork(
                ServiceStartWorker.WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule restart on task removed: ${e.message}")
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        _lastLocation.value = location
        Log.d(TAG, "GPS fix: lat=${location.latitude} lon=${location.longitude} " +
            "acc=${"%.1f".format(location.accuracy)}m speed=${"%.1f".format(location.speed * 3.6f)}km/h " +
            "provider=${location.provider}")
    }

    private fun startPolling() {
        Log.i(TAG, "Starting polling with interval=${POLL_INTERVAL_MS}ms")
        pollingJob = serviceScope.launch {
            while (true) {
                try {
                    val data = diParsClient.fetch()
                    if (data != null) {
                        consecutiveNullCount = 0
                        currentPollIntervalMs = POLL_INTERVAL_MS
                        _diPlusConnected.value = true
                        _lastData.value = data

                        // Feed DiPlus data to Alice for real device states
                        alicePollingManager.latestData = data

                        // Save SOC for retrospective charge detection
                        data.soc?.let { soc ->
                            settingsRepository.saveLastKnownSoc(soc)
                        }

                        // On first data after startup: detect offline charging
                        if (!firstDataReceived) {
                            firstDataReceived = true
                            data.soc?.let { currentSoc ->
                                detectOfflineCharge(currentSoc)
                            }
                        }
                        val loc = _lastLocation.value
                        tripTracker.onData(data, loc)
                        ConsumptionAggregator.onSample(
                            now = System.currentTimeMillis(),
                            tripStartedAt = tripTracker.tripStartedAt.value,
                            mileageKm = data.mileage,
                            totalElecKwh = data.totalElecConsumption,
                            weeklyEma = cachedWeeklyEmaConsumption,
                        )
                        chargeTracker.onData(data, loc)
                        // Idle drain tracked via energydata zero-km records only (HistoryImporter).
                        // Live power integration removed — DiPars 发动机功率 ≠ total battery drain.
                        automationEngine.evaluate(data, tripTracker.tripStartedAt.value)
                        updateNotification(data)
                    } else {
                        consecutiveNullCount++
                        if (consecutiveNullCount >= NULL_WARNING_THRESHOLD) {
                            _diPlusConnected.value = false
                            currentPollIntervalMs = (currentPollIntervalMs * 1.5).toLong()
                                .coerceAtMost(MAX_POLL_INTERVAL_MS)
                            if (consecutiveNullCount == NULL_WARNING_THRESHOLD) {
                                Log.w(TAG, "DiPlus API not responding ($NULL_WARNING_THRESHOLD consecutive nulls), backoff to ${currentPollIntervalMs}ms")
                                tryLaunchDiPlus()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}", e)
                }
                delay(currentPollIntervalMs)
            }
        }
    }

    private fun detectOfflineCharge(currentSoc: Int) {
        serviceScope.launch {
            try {
                val lastSoc = settingsRepository.getLastKnownSoc() ?: return@launch
                val lastTs = settingsRepository.getLastSocTimestamp()
                val now = System.currentTimeMillis()

                // SOC increased since last shutdown → charging happened offline
                if (currentSoc > lastSoc) {
                    val socDelta = currentSoc - lastSoc
                    val batteryCapacity = settingsRepository.getBatteryCapacity()
                    val kwhCharged = socDelta / 100.0 * batteryCapacity

                    // Determine tariff (assume AC for home charging)
                    val tariff = settingsRepository.getHomeTariff()
                    val cost = kwhCharged * tariff

                    val chargeId = chargeRepository.insertCharge(
                        com.bydmate.app.data.local.entity.ChargeEntity(
                            startTs = lastTs,
                            endTs = now,
                            socStart = lastSoc,
                            socEnd = currentSoc,
                            kwhCharged = kwhCharged,
                            kwhChargedSoc = kwhCharged,
                            type = "AC",
                            cost = cost,
                            status = "COMPLETED"
                        )
                    )

                    Log.i(TAG, "Offline charge detected: SOC $lastSoc→$currentSoc (+$socDelta%), " +
                        "${"%.1f".format(kwhCharged)} kWh, id=$chargeId")
                } else {
                    Log.d(TAG, "No offline charge: lastSoc=$lastSoc, currentSoc=$currentSoc")
                }
            } catch (e: Exception) {
                Log.w(TAG, "detectOfflineCharge failed: ${e.message}")
            }
        }
    }

    private fun tryLaunchDiPlus() {
        try {
            val intent = Intent().apply {
                setClassName("com.van.diplus", "com.van.diplus.activity.StartMainServiceActivity")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
            Log.i(TAG, "Launched DiPlus StartMainServiceActivity")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to launch DiPlus: ${e.message}")
            // Try alternative: just launch the main app
            try {
                val launchIntent = packageManager.getLaunchIntentForPackage("com.van.diplus")
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    startActivity(launchIntent)
                    Log.i(TAG, "Launched DiPlus via package manager")
                }
            } catch (e2: Exception) {
                Log.w(TAG, "Failed to launch DiPlus fallback: ${e2.message}")
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted, skipping location updates")
            return
        }

        val lm = getSystemService(LOCATION_SERVICE) as LocationManager
        locationManager = lm

        val gpsEnabled = try { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } catch (_: Exception) { false }
        val netEnabled = try { lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } catch (_: Exception) { false }
        Log.i(TAG, "Location providers: gps=$gpsEnabled network=$netEnabled")

        // GPS provider — same params as TripInfo (2000ms, 8m, explicit MainLooper)
        if (gpsEnabled) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2000L, 8.0f,
                    this, Looper.getMainLooper()
                )
                Log.i(TAG, "requestLocationUpdates(GPS_PROVIDER) registered")
            } catch (e: Exception) {
                Log.e(TAG, "GPS provider registration failed: ${e.message}", e)
            }
        }

        // Network provider for initial fix
        if (netEnabled) {
            try {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000L, 8.0f,
                    this, Looper.getMainLooper()
                )
                Log.i(TAG, "requestLocationUpdates(NETWORK_PROVIDER) registered")
            } catch (e: Exception) {
                Log.w(TAG, "Network provider registration failed: ${e.message}")
            }
        }

        // Get last known location for immediate fix (like TripInfo)
        try {
            val lastKnown = if (gpsEnabled) lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                else if (netEnabled) lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                else null
            if (lastKnown != null) {
                _lastLocation.value = lastKnown
                Log.i(TAG, "lastKnownLocation: lat=${lastKnown.latitude} lon=${lastKnown.longitude} " +
                    "provider=${lastKnown.provider} age=${(System.currentTimeMillis() - lastKnown.time) / 1000}s")
            } else {
                Log.w(TAG, "lastKnownLocation is null")
            }
        } catch (e: Exception) {
            Log.w(TAG, "getLastKnownLocation failed: ${e.message}")
        }

        if (!gpsEnabled && !netEnabled) {
            Log.e(TAG, "No location provider enabled! GPS will not work.")
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydmate:tracking")
        wakeLock?.acquire(30 * 60 * 1000L) // 30 min max, auto-released
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "BYDMate Tracking",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Trip and charge tracking"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("BYDMate")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun appendChainLog(entry: String) {
        try {
            val sdf = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
            val ts = sdf.format(java.util.Date())
            val prefs = getSharedPreferences(BootReceiver.PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(BootReceiver.KEY_CHAIN_LOG, "") ?: ""
            val lines = existing.lines().takeLast(19)
            val updated = (lines + "$ts $entry").joinToString("\n")
            prefs.edit().putString(BootReceiver.KEY_CHAIN_LOG, updated).apply()
        } catch (_: Exception) {}
    }

    private fun updateNotification(data: DiParsData) {
        val parts = mutableListOf<String>()

        // Block 1: запас (SOC + оценка km) + t°бат
        val socStr = data.soc?.let { "$it%" } ?: "—"
        val rangeKm = estimateRangeKm(data.soc)
        _lastRangeKm.value = rangeKm
        val rangeStr = rangeKm?.let { " ~${"%.0f".format(it)} км" } ?: ""
        val tempStr = data.avgBatTemp?.let { " | t°бат: ${it}°C" } ?: ""
        parts += "запас: $socStr$rangeStr$tempStr"

        // Block 2: 12V
        data.voltage12v?.let {
            parts += "борт.сеть: ${"%.1f".format(it)} В"
        }

        val text = parts.joinToString(" | ")
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
