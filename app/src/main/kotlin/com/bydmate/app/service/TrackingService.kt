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
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bydmate.app.MainActivity
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.domain.tracker.ChargeTracker
import com.bydmate.app.domain.tracker.IdleDrainTracker
import com.bydmate.app.domain.tracker.TripState
import com.bydmate.app.domain.tracker.ChargeState
import com.bydmate.app.domain.tracker.TripTracker
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
    @Inject lateinit var idleDrainTracker: IdleDrainTracker
    @Inject lateinit var chargeRepository: ChargeRepository
    @Inject lateinit var tripRepository: com.bydmate.app.data.repository.TripRepository
    @Inject lateinit var historyImporter: com.bydmate.app.data.local.HistoryImporter
    @Inject lateinit var diPlusDbReader: com.bydmate.app.data.remote.DiPlusDbReader
    @Inject lateinit var settingsRepository: com.bydmate.app.data.repository.SettingsRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLocation: Location? = null
    private var locationManager: LocationManager? = null
    private var consecutiveNullCount = 0
    private var firstDataReceived = false

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bydmate_tracking"
        private const val POLL_INTERVAL_MS = 9000L // ~9 seconds
        private const val NULL_WARNING_THRESHOLD = 5

        private val _lastData = MutableStateFlow<DiParsData?>(null)
        val lastData: StateFlow<DiParsData?> = _lastData

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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        acquireWakeLock()
        startLocationUpdates()
        startPolling()
        _isRunning.value = true

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

        // Auto-import on first launch (empty DB)
        serviceScope.launch {
            try {
                val tripCount = tripRepository.getTripCount()
                if (tripCount == 0) {
                    Log.i(TAG, "Empty DB detected, starting auto-import")
                    val bydResult = historyImporter.forceImport()
                    Log.i(TAG, "Auto-import BYD: ${bydResult.count} trips, error=${bydResult.error}")
                    val diPlusResult = diPlusDbReader.importChargingLog()
                    Log.i(TAG, "Auto-import DiPlus: ${diPlusResult.imported} charges, error=${diPlusResult.error}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Auto-import failed: ${e.message}")
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping TrackingService")
        pollingJob?.cancel()

        // Force-end active trip/charge sessions before shutdown
        try {
            runBlocking(Dispatchers.IO) {
                withTimeout(5000L) {
                    val lastData = _lastData.value
                    val lastLoc = lastLocation
                    tripTracker.forceEnd(lastData, lastLoc)
                    chargeTracker.forceEnd(lastData)
                    // Let any in-flight coroutines in serviceScope finish
                    serviceScope.coroutineContext[Job]?.children?.forEach { it.join() }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Timeout during graceful shutdown: ${e.message}")
        }

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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    private fun startPolling() {
        Log.i(TAG, "Starting polling with interval=${POLL_INTERVAL_MS}ms")
        pollingJob = serviceScope.launch {
            while (true) {
                try {
                    val data = diParsClient.fetch()
                    if (data != null) {
                        consecutiveNullCount = 0
                        _diPlusConnected.value = true
                        _lastData.value = data

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
                        val loc = lastLocation
                        tripTracker.onData(data, loc)
                        chargeTracker.onData(data, loc)
                        idleDrainTracker.onData(
                            data,
                            isMoving = tripTracker.state.value == TripState.DRIVING,
                            isCharging = chargeTracker.state.value == ChargeState.CHARGING
                        )
                        updateNotification(data)
                    } else {
                        consecutiveNullCount++
                        if (consecutiveNullCount >= NULL_WARNING_THRESHOLD) {
                            _diPlusConnected.value = false
                            if (consecutiveNullCount == NULL_WARNING_THRESHOLD) {
                                Log.w(TAG, "DiPlus API not responding ($NULL_WARNING_THRESHOLD consecutive nulls)")
                                tryLaunchDiPlus()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Polling error: ${e.message}", e)
                    // Continue polling on error
                }
                delay(POLL_INTERVAL_MS)
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

        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L, // 5 sec
                5f,    // 5 meters
                this
            )
            // Also try network for initial fix
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                10000L,
                50f,
                this
            )
            Log.d(TAG, "Location updates started (GPS + Network)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start location updates: ${e.message}", e)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bydmate:tracking")
        wakeLock?.acquire()
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

    private fun updateNotification(data: DiParsData) {
        val text = buildString {
            append("SOC: ${data.soc ?: "?"}% | ${data.speed ?: 0} km/h")
            data.avgBatTemp?.let { append(" | bat ${it}°C") }
            data.voltage12v?.let { append(" | 12V: ${"%.1f".format(it)}V") }
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
