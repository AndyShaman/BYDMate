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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bydmate.app.MainActivity
import com.bydmate.app.data.remote.DiParsClient
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.domain.tracker.ChargeTracker
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
import javax.inject.Inject

@AndroidEntryPoint
class TrackingService : Service(), LocationListener {

    @Inject lateinit var diParsClient: DiParsClient
    @Inject lateinit var tripTracker: TripTracker
    @Inject lateinit var chargeTracker: ChargeTracker

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastLocation: Location? = null

    companion object {
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bydmate_tracking"
        private const val POLL_INTERVAL_MS = 9000L // ~9 seconds

        private val _lastData = MutableStateFlow<DiParsData?>(null)
        val lastData: StateFlow<DiParsData?> = _lastData

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning

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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting..."))
        acquireWakeLock()
        startLocationUpdates()
        startPolling()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        pollingJob?.cancel()
        serviceScope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onLocationChanged(location: Location) {
        lastLocation = location
    }

    private fun startPolling() {
        pollingJob = serviceScope.launch {
            while (true) {
                try {
                    val data = diParsClient.fetch()
                    if (data != null) {
                        _lastData.value = data
                        val loc = lastLocation
                        tripTracker.onData(data, loc)
                        chargeTracker.onData(data, loc)
                        updateNotification(data)
                    }
                } catch (e: Exception) {
                    // Continue polling on error
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun startLocationUpdates() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        try {
            locationManager.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                5000L, // 5 sec
                5f,    // 5 meters
                this
            )
            // Also try network for initial fix
            locationManager.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                10000L,
                50f,
                this
            )
        } catch (e: Exception) {
            // GPS provider may not be available
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
        val text = "SOC: ${data.soc ?: "?"}% | ${data.speed ?: 0} km/h"
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }
}
