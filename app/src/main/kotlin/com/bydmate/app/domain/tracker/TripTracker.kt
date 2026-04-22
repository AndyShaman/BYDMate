package com.bydmate.app.domain.tracker

import android.location.Location
import android.util.Log
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.local.dao.TripPointDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

enum class TripState { IDLE, DRIVING }

/**
 * v2.0: GPS collector only. Does NOT create TripEntity records.
 * GPS points are written with tripId=0 and later attached to energydata trips
 * by HistoryImporter.attachGpsPoints().
 */
@Singleton
class TripTracker @Inject constructor(
    private val tripPointDao: TripPointDao
) {
    private val _state = MutableStateFlow(TripState.IDLE)
    val state: StateFlow<TripState> = _state

    private val _tripStartedAt = MutableStateFlow<Long?>(null)
    val tripStartedAt: StateFlow<Long?> = _tripStartedAt

    private var speedAboveThresholdSince: Long? = null
    private var speedZeroSince: Long? = null
    private val pendingPoints = Collections.synchronizedList(mutableListOf<TripPointEntity>())

    companion object {
        private const val TAG = "TripTracker"
        private const val SPEED_THRESHOLD = 3    // km/h
        private const val START_DELAY_MS = 5_000L   // 5 seconds above threshold
        private const val STOP_DELAY_MS = 180_000L  // 3 minutes at zero
        private const val POINT_BATCH_SIZE = 30
    }

    suspend fun onData(data: DiParsData, location: Location?) {
        val speed = data.speed ?: 0
        val now = System.currentTimeMillis()

        when (_state.value) {
            TripState.IDLE -> {
                if (speed > SPEED_THRESHOLD) {
                    if (speedAboveThresholdSince == null) {
                        speedAboveThresholdSince = now
                    } else if (now - speedAboveThresholdSince!! >= START_DELAY_MS) {
                        _state.value = TripState.DRIVING
                        _tripStartedAt.value = now
                        speedZeroSince = null
                        speedAboveThresholdSince = null
                        Log.d(TAG, "Driving started (GPS collection)")
                        // Record first point
                        recordPoint(location, speed, now)
                    }
                } else {
                    speedAboveThresholdSince = null
                }
            }
            TripState.DRIVING -> {
                // Record GPS point
                recordPoint(location, speed, now)

                if (pendingPoints.size >= POINT_BATCH_SIZE) {
                    flushPoints()
                }

                // Check stop condition
                if (speed == 0) {
                    if (speedZeroSince == null) {
                        speedZeroSince = now
                    } else if (now - speedZeroSince!! >= STOP_DELAY_MS) {
                        flushPoints()
                        _state.value = TripState.IDLE
                        _tripStartedAt.value = null
                        speedZeroSince = null
                        speedAboveThresholdSince = null
                        Log.d(TAG, "Driving stopped (GPS collection ended)")
                    }
                } else {
                    speedZeroSince = null
                }
            }
        }
    }

    private fun recordPoint(location: Location?, speed: Int, now: Long) {
        if (location != null) {
            pendingPoints.add(
                TripPointEntity(
                    tripId = 0, // unattached, will be matched later
                    timestamp = now,
                    lat = location.latitude,
                    lon = location.longitude,
                    speedKmh = speed.toDouble()
                )
            )
            if (pendingPoints.size % 10 == 1) {
                Log.d(TAG, "GPS point #${pendingPoints.size}: lat=${"%.5f".format(location.latitude)} " +
                    "lon=${"%.5f".format(location.longitude)} speed=${speed}km/h")
            }
        } else {
            Log.w(TAG, "No GPS fix available (location=null), speed=${speed}km/h")
        }
    }

    /**
     * Force-end on service shutdown. Just flush remaining GPS points.
     */
    suspend fun forceEnd(lastData: DiParsData?, lastLocation: Location?) {
        if (_state.value != TripState.DRIVING) return
        Log.w(TAG, "forceEnd: flushing GPS points on shutdown")
        flushPoints()
        _state.value = TripState.IDLE
        _tripStartedAt.value = null
        speedZeroSince = null
        speedAboveThresholdSince = null
    }

    private suspend fun flushPoints() {
        val snapshot: List<TripPointEntity>
        synchronized(pendingPoints) {
            if (pendingPoints.isEmpty()) return
            snapshot = ArrayList(pendingPoints)
            pendingPoints.clear()
        }
        try {
            tripPointDao.insertAll(snapshot)
            Log.i(TAG, "flushPoints: saved ${snapshot.size} GPS points to DB")
        } catch (e: Exception) {
            Log.e(TAG, "flushPoints FAILED: ${e.message}", e)
        }
    }
}
