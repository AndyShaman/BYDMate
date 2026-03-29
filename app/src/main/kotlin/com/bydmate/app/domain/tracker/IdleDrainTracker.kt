package com.bydmate.app.domain.tracker

import android.util.Log
import com.bydmate.app.data.local.dao.IdleDrainDao
import com.bydmate.app.data.local.entity.IdleDrainEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks battery drain while the vehicle is stationary (parked with
 * ignition on — heating, A/C, infotainment, etc.).
 *
 * Uses Power (kW) integration over time for precise measurement.
 * Fallback: SOC delta method if Power is unavailable.
 */
@Singleton
class IdleDrainTracker @Inject constructor(
    private val idleDrainDao: IdleDrainDao,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "IdleDrainTracker"
        private const val MIN_KWH_TO_RECORD = 0.01
    }

    private var tracking = false
    private var sessionId: Long? = null
    private var sessionStartTs: Long = 0
    private var socAtStart: Int? = null
    private var lastSoc: Int? = null
    private var lastPollTs: Long = 0
    private var accumulatedKwh: Double = 0.0

    /**
     * Called every polling cycle from TrackingService.
     * @param isMoving true if TripTracker is in DRIVING state
     * @param isCharging true if ChargeTracker is in CHARGING state
     */
    suspend fun onData(data: DiParsData, isMoving: Boolean, isCharging: Boolean) {
        val soc = data.soc ?: return
        val speed = data.speed ?: 0
        val now = System.currentTimeMillis()

        // Only track when parked and not charging
        val shouldTrack = speed == 0 && !isMoving && !isCharging

        if (shouldTrack) {
            if (!tracking) {
                tracking = true
                sessionStartTs = now
                socAtStart = soc
                lastSoc = soc
                lastPollTs = now
                accumulatedKwh = 0.0
                Log.d(TAG, "Idle tracking started: SOC=$soc%")
            } else {
                lastSoc = soc

                // Integrate power over time: kWh += kW * hours
                val powerKw = data.power
                if (powerKw != null && powerKw > 0 && lastPollTs > 0) {
                    val intervalHours = (now - lastPollTs) / 3_600_000.0
                    accumulatedKwh += powerKw * intervalHours
                }
                lastPollTs = now

                // Save/update if meaningful drain accumulated
                if (accumulatedKwh >= MIN_KWH_TO_RECORD) {
                    if (sessionId == null) {
                        sessionId = idleDrainDao.insert(
                            IdleDrainEntity(
                                startTs = sessionStartTs,
                                socStart = socAtStart,
                                socEnd = soc,
                                kwhConsumed = accumulatedKwh
                            )
                        )
                        Log.d(TAG, "Idle drain recorded: id=$sessionId, " +
                            "SOC ${socAtStart}→$soc, ${"%.3f".format(accumulatedKwh)} kWh")
                    } else {
                        idleDrainDao.update(
                            IdleDrainEntity(
                                id = sessionId!!,
                                startTs = sessionStartTs,
                                endTs = now,
                                socStart = socAtStart,
                                socEnd = soc,
                                kwhConsumed = accumulatedKwh
                            )
                        )
                    }
                }
            }
        } else if (tracking) {
            finalize(now)
        }
    }

    private suspend fun finalize(now: Long) {
        val id = sessionId

        // If no Power data was available, fall back to SOC delta
        if (accumulatedKwh < MIN_KWH_TO_RECORD) {
            val drop = (socAtStart ?: 0) - (lastSoc ?: 0)
            if (drop >= 1) {
                val batteryKwh = settingsRepository.getBatteryCapacity()
                accumulatedKwh = drop / 100.0 * batteryKwh
            }
        }

        if (id != null && accumulatedKwh >= MIN_KWH_TO_RECORD) {
            idleDrainDao.update(
                IdleDrainEntity(
                    id = id,
                    startTs = sessionStartTs,
                    endTs = now,
                    socStart = socAtStart,
                    socEnd = lastSoc,
                    kwhConsumed = accumulatedKwh
                )
            )
            Log.d(TAG, "Idle session finalized: id=$id, " +
                "SOC ${socAtStart}→$lastSoc, ${"%.3f".format(accumulatedKwh)} kWh")
        }

        // Reset
        tracking = false
        sessionId = null
        socAtStart = null
        lastSoc = null
        lastPollTs = 0
        accumulatedKwh = 0.0
    }
}
