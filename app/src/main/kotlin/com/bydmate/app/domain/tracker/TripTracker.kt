package com.bydmate.app.domain.tracker

import android.location.Location
import android.util.Log
import com.bydmate.app.data.local.entity.TripEntity
import com.bydmate.app.data.local.entity.TripPointEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.remote.WeatherClient
import com.bydmate.app.data.repository.TripRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.calculator.ConsumptionCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

enum class TripState { IDLE, DRIVING }

@Singleton
class TripTracker @Inject constructor(
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val weatherClient: WeatherClient,
    private val calculator: ConsumptionCalculator
) {
    private val _state = MutableStateFlow(TripState.IDLE)
    val state: StateFlow<TripState> = _state

    private var currentTripId: Long? = null
    private var tripStartTs: Long = 0
    private var socStart: Int? = null
    private var mileageStart: Double? = null
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
                        Log.d(TAG, "Speed above threshold, starting delay timer")
                    } else if (now - speedAboveThresholdSince!! >= START_DELAY_MS) {
                        startTrip(data, location, now)
                    }
                } else {
                    speedAboveThresholdSince = null
                }
            }
            TripState.DRIVING -> {
                // Record point
                val tripId = currentTripId
                if (tripId != null && location != null) {
                    pendingPoints.add(
                        TripPointEntity(
                            tripId = tripId,
                            timestamp = now,
                            lat = location.latitude,
                            lon = location.longitude,
                            speedKmh = speed.toDouble()
                        )
                    )
                    if (pendingPoints.size >= POINT_BATCH_SIZE) {
                        flushPoints()
                    }
                }

                // Check stop condition
                if (speed == 0) {
                    if (speedZeroSince == null) {
                        speedZeroSince = now
                        Log.d(TAG, "Speed dropped to zero, starting stop timer")
                    } else if (now - speedZeroSince!! >= STOP_DELAY_MS) {
                        endTrip(data, location, now)
                    }
                } else {
                    speedZeroSince = null
                }
            }
        }
    }

    private suspend fun startTrip(data: DiParsData, location: Location?, now: Long) {
        socStart = data.soc
        mileageStart = data.mileage
        tripStartTs = now
        synchronized(pendingPoints) {
            pendingPoints.clear()
        }

        val tripId = tripRepository.insertTrip(
            TripEntity(
                startTs = now,
                socStart = data.soc
            )
        )
        currentTripId = tripId
        _state.value = TripState.DRIVING
        speedZeroSince = null
        speedAboveThresholdSince = null

        Log.d(TAG, "Trip started: id=$tripId, soc=${data.soc}, mileage=${data.mileage}")

        // Record first point
        if (location != null) {
            pendingPoints.add(
                TripPointEntity(
                    tripId = tripId,
                    timestamp = now,
                    lat = location.latitude,
                    lon = location.longitude,
                    speedKmh = (data.speed ?: 0).toDouble()
                )
            )
        }
    }

    private suspend fun endTrip(data: DiParsData, location: Location?, now: Long) {
        val tripId = currentTripId
        if (tripId == null) {
            Log.d(TAG, "endTrip called but currentTripId is null, ignoring")
            return
        }

        flushPoints()

        val socEnd = data.soc
        val mileageEnd = data.mileage
        val batteryCapacity = settingsRepository.getBatteryCapacity()

        val distanceKm = if (mileageStart != null && mileageEnd != null) {
            mileageEnd - mileageStart!!
        } else null

        val kwhConsumed = if (socStart != null && socEnd != null) {
            calculator.realKwh(socStart!!, socEnd, batteryCapacity)
        } else null

        val kwhPer100km = if (kwhConsumed != null && distanceKm != null && distanceKm > 0) {
            calculator.kwhPer100km(kwhConsumed, distanceKm)
        } else null

        val durationMs = now - tripStartTs
        val avgSpeed = if (distanceKm != null && durationMs > 0) {
            distanceKm / (durationMs / 3_600_000.0)
        } else null

        // Fetch weather at trip end location
        val temp = if (location != null) {
            weatherClient.getTemperature(location.latitude, location.longitude)?.toDouble()
        } else null

        tripRepository.updateTrip(
            TripEntity(
                id = tripId,
                startTs = tripStartTs,
                endTs = now,
                distanceKm = distanceKm,
                kwhConsumed = kwhConsumed,
                kwhPer100km = kwhPer100km,
                socStart = socStart,
                socEnd = socEnd,
                tempAvgC = temp,
                avgSpeedKmh = avgSpeed
            )
        )

        Log.d(TAG, "Trip ended: id=$tripId, distance=$distanceKm km, kwh=$kwhConsumed")

        // Reset
        _state.value = TripState.IDLE
        currentTripId = null
        socStart = null
        mileageStart = null
        speedZeroSince = null
        speedAboveThresholdSince = null
        synchronized(pendingPoints) {
            pendingPoints.clear()
        }
    }

    /**
     * Force-end the current trip on service shutdown.
     * Uses the last available data to finalize the trip.
     */
    suspend fun forceEnd(lastData: DiParsData?, lastLocation: Location?) {
        if (_state.value != TripState.DRIVING || currentTripId == null) return
        Log.w(TAG, "forceEnd: ending active trip id=$currentTripId on service shutdown")
        val data = lastData ?: DiParsData(
            soc = null, speed = 0, mileage = null, power = null,
            chargeGunState = null, maxBatTemp = null, avgBatTemp = null,
            minBatTemp = null, chargingStatus = null
        )
        endTrip(data, lastLocation, System.currentTimeMillis())
    }

    private suspend fun flushPoints() {
        val snapshot: List<TripPointEntity>
        synchronized(pendingPoints) {
            if (pendingPoints.isEmpty()) return
            snapshot = ArrayList(pendingPoints)
            pendingPoints.clear()
        }
        tripRepository.insertTripPoints(snapshot)
    }
}
