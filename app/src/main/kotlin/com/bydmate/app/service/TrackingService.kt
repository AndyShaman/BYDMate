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
import com.bydmate.app.domain.calculator.OdometerConsumptionBuffer
import com.bydmate.app.domain.calculator.SocInterpolator
import com.bydmate.app.domain.calculator.RangeCalculator
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
    @Inject lateinit var odometerBuffer: OdometerConsumptionBuffer
    @Inject lateinit var socInterpolator: SocInterpolator
    @Inject lateinit var rangeCalculator: RangeCalculator

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollingJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var locationManager: LocationManager? = null
    private var consecutiveNullCount = 0
    private var firstDataReceived = false
    private var currentPollIntervalMs = POLL_INTERVAL_MS

    // Widget session (ignition-on → ignition-off) — decoupled from TripTracker GPS state.
    // Primary signal: DiPars powerState ≥ 1. Fallback when powerState is unreliable:
    // tripTracker.state == DRIVING. Session closes when both are inactive for 30 sec
    // so a short powerState glitch doesn't split one physical trip into two.
    private lateinit var sessionPersistence: SessionPersistence
    @Volatile private var sessionLastActiveTs: Long = 0L
    // Odometer reading captured at session-start. current mileage - this = trip distance.
    @Volatile private var sessionStartMileageKm: Double? = null
    private var lastSummaryLogTs: Long = 0L

    companion object {
        private const val TAG = "TrackingService"
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "bydmate_tracking"
        private const val POLL_INTERVAL_MS = 3000L // 3 seconds for detailed GPS + charging curve
        private const val NULL_WARNING_THRESHOLD = 5
        private const val MAX_POLL_INTERVAL_MS = 60_000L
        // Tolerance between last "session active" tick and the current tick before we
        // consider the session closed. 30 sec survives brief powerState blips and
        // covers the DiLink wind-down after ignition-off.
        private const val SESSION_IDLE_CLOSE_MS = 30_000L
        // Throttle for the periodic INFO summary so logcat doesn't get flooded.
        private const val SUMMARY_LOG_INTERVAL_MS = 60_000L

        private val _lastData = MutableStateFlow<DiParsData?>(null)
        val lastData: StateFlow<DiParsData?> = _lastData

        private val _lastRangeKm = MutableStateFlow<Double?>(null)
        val lastRangeKm: StateFlow<Double?> = _lastRangeKm

        /** Live trip distance (current odometer - session-start odometer). Null when idle or data unready. */
        private val _tripDistanceKm = MutableStateFlow<Double?>(null)
        val tripDistanceKm: StateFlow<Double?> = _tripDistanceKm

        private val _lastLocation = MutableStateFlow<Location?>(null)
        val lastLocation: StateFlow<Location?> = _lastLocation

        /**
         * Current widget-session anchor (epoch millis of ignition-on), or null when
         * the vehicle is idle. Consumers: widget duration, ConsumptionAggregator,
         * AutomationEngine.fireOncePerTrip.
         */
        private val _sessionStartedAt = MutableStateFlow<Long?>(null)
        val sessionStartedAt: StateFlow<Long?> = _sessionStartedAt

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

        // Reset the live trip-distance companion flow — stale value from a prior
        // service instance in the same process must not leak to the widget before
        // the first polling tick overwrites it.
        _tripDistanceKm.value = null

        // Restore widget session anchor if the process was killed mid-trip.
        // Aggregator will resume cumulative mode on its first post-restart tick
        // as long as the session is still live (powerState ≥ 1 within grace window).
        sessionPersistence = SessionPersistence(this)
        val restored = sessionPersistence.load()
        if (restored != null) {
            val nowMs = System.currentTimeMillis()
            if (restored.isStale(nowMs, SESSION_IDLE_CLOSE_MS)) {
                // Process was killed inside the 30-sec grace window before idle-close
                // could fire. Without this guard the next start would render
                // (now - old sessionStartedAt) as "11 ч 43 мин" trip time.
                val idleFor = (nowMs - restored.lastActiveTs) / 1000
                Log.i(TAG, "Discarded stale session: startedAt=${restored.sessionStartedAt}, " +
                    "idleFor=${idleFor}s (>= ${SESSION_IDLE_CLOSE_MS / 1000}s)")
                sessionPersistence.clear()
            } else {
                _sessionStartedAt.value = restored.sessionStartedAt
                sessionLastActiveTs = restored.lastActiveTs
                Log.i(TAG, "Restored session: startedAt=${restored.sessionStartedAt}, " +
                    "lastActiveTs=${restored.lastActiveTs}")
            }
        }

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

        // v2.0: event-based sync on service start
        serviceScope.launch {
            try {
                val result = historyImporter.runSync()
                Log.i(TAG, "Sync: ${result.details ?: result.error ?: "ok"}")
                // Also import charging sessions
                diPlusDbReader.importChargingLog()
                // AI insights (once per day)
                insightsManager.refreshIfNeeded()
            } catch (e: Exception) {
                Log.w(TAG, "Sync failed: ${e.message}")
            }
        }
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

    /**
     * Widget-session state machine — decoupled from TripTracker's GPS segmentation.
     *
     * Active = powerState ≥ 1 OR tripTracker currently driving. The OR guards against
     * DiPars returning null/0 for powerState on some firmwares — if the fallback
     * detects motion, we still open/keep the session.
     *
     * Session closes when both signals are silent for [SESSION_IDLE_CLOSE_MS],
     * absorbing short powerState blips so one physical trip stays one session.
     */
    private fun updateSessionState(now: Long, data: DiParsData): Long? {
        val powerOn = (data.powerState ?: 0) >= 1
        val driving = tripTracker.state.value == com.bydmate.app.domain.tracker.TripState.DRIVING
        val active = powerOn || driving

        val currentSession = _sessionStartedAt.value

        if (active) {
            sessionLastActiveTs = now
            if (currentSession == null) {
                _sessionStartedAt.value = now
                sessionStartMileageKm = data.mileage
                Log.i(TAG, "Widget session START at $now " +
                    "(powerOn=$powerOn, driving=$driving, mileageStart=${data.mileage})")
            } else if (sessionStartMileageKm == null && data.mileage != null) {
                // DiPars was unready at the exact session-start tick — lazy-initialize now.
                sessionStartMileageKm = data.mileage
            }
        } else if (currentSession != null) {
            val idleFor = now - sessionLastActiveTs
            if (idleFor >= SESSION_IDLE_CLOSE_MS) {
                Log.i(TAG, "Widget session END (idle ${idleFor / 1000}s, powerOn=$powerOn, driving=$driving)")
                _sessionStartedAt.value = null
                sessionStartMileageKm = null
                _tripDistanceKm.value = null
                sessionPersistence.clear()
            }
            // else: grace period — keep session alive through brief blip
        }

        return _sessionStartedAt.value
    }

    /**
     * Once per minute emit a compact INFO line with session summary — helps field
     * diagnosis (logcat) without flooding on every 3-sec tick.
     */
    private suspend fun maybeLogSessionSummary(now: Long, data: DiParsData, sessionId: Long?) {
        if (now - lastSummaryLogTs < SUMMARY_LOG_INTERVAL_MS) return
        lastSummaryLogTs = now
        val status = odometerBuffer.status()
        val state = ConsumptionAggregator.state.value
        val carry = socInterpolator.carryOver(data.totalElecConsumption, data.soc)
        Log.i(TAG, "Widget session: id=$sessionId, " +
            "bufferRows=${status.rowCount}, " +
            "newestKm=${status.newestMileageKm?.let { "%.1f".format(it) } ?: "—"}, " +
            "recentAvg=${"%.2f".format(status.recentAvg)} kWh/100, " +
            "shortAvg=${status.shortAvg?.let { "%.2f".format(it) } ?: "—"}, " +
            "display=${state.displayValue?.let { "%.1f".format(it) } ?: "—"}, " +
            "trend=${state.trend}, " +
            "socCarry=${"%.3f".format(carry)} kWh, " +
            "powerState=${data.powerState}")
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy: stopping TrackingService")
        com.bydmate.app.ui.widget.WidgetController.detach()
        appendChainLog("TrackingService onDestroy")
        pollingJob?.cancel()
        ConsumptionAggregator.reset()
        // NOTE: do NOT null out _sessionStartedAt or clear SessionPersistence here.
        // onDestroy can fire on sys-kill mid-trip; persistence must survive so the
        // next process can resume the session. The ignition-off branch in
        // updateSessionState is the only place that clears prefs.

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

                        val nowMs = System.currentTimeMillis()
                        val sessionId = updateSessionState(nowMs, data)

                        odometerBuffer.onSample(
                            mileage = data.mileage,
                            totalElec = data.totalElecConsumption,
                            socPercent = data.soc,
                            sessionId = sessionId,
                        )
                        socInterpolator.onSample(
                            soc = data.soc,
                            totalElecKwh = data.totalElecConsumption,
                            sessionId = sessionId,
                        )

                        val recentAvg = odometerBuffer.recentAvgConsumption()
                        val shortAvg = odometerBuffer.shortAvgConsumption()
                        ConsumptionAggregator.onSample(now = nowMs, recentAvg = recentAvg, shortAvg = shortAvg)

                        val rangeKm = rangeCalculator.estimate(soc = data.soc, totalElecKwh = data.totalElecConsumption)
                        _lastRangeKm.value = rangeKm

                        // Odometer regression (rare DiPars glitch) leaves delta negative.
                        // Show "—" on the widget instead of silent 0.0 so field diagnosis
                        // still sees the anomaly. OdometerConsumptionBuffer blocks the same
                        // regression at insert, so consumption math is unaffected.
                        val tripDistance = sessionStartMileageKm?.let { start ->
                            data.mileage?.let { cur -> (cur - start).takeIf { it >= 0.0 } }
                        }
                        _tripDistanceKm.value = tripDistance

                        sessionId?.let { sessionPersistence.save(it, sessionLastActiveTs) }

                        chargeTracker.onData(data, loc)
                        // Idle drain tracked via energydata zero-km records only (HistoryImporter).
                        // Live power integration removed — DiPars 发动机功率 ≠ total battery drain.
                        automationEngine.evaluate(data, sessionId)
                        updateNotification(data)
                        maybeLogSessionSummary(nowMs, data, sessionId)
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
        val rangeKm = _lastRangeKm.value
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
