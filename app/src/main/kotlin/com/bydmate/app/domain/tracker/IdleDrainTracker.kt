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
 * Logic:
 * - Vehicle is IDLE (speed == 0) and NOT charging
 * - SOC is dropping → accumulate drain
 * - When SOC drops by >= 1% since idle started, record a session
 * - When vehicle starts moving or charging, finalize the session
 */
@Singleton
class IdleDrainTracker @Inject constructor(
    private val idleDrainDao: IdleDrainDao,
    private val settingsRepository: SettingsRepository
) {
    companion object {
        private const val TAG = "IdleDrainTracker"
        private const val MIN_SOC_DROP = 1 // minimum 1% drop to record
    }

    private var tracking = false
    private var sessionId: Long? = null
    private var sessionStartTs: Long = 0
    private var socAtStart: Int? = null
    private var lastSoc: Int? = null

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
                // Start idle tracking
                tracking = true
                sessionStartTs = now
                socAtStart = soc
                lastSoc = soc
                Log.d(TAG, "Idle tracking started: SOC=$soc%")
            } else {
                lastSoc = soc
                val drop = (socAtStart ?: soc) - soc
                if (drop >= MIN_SOC_DROP && sessionId == null) {
                    // SOC dropped enough — create DB record
                    val batteryKwh = settingsRepository.getBatteryCapacity()
                    val kwh = drop / 100.0 * batteryKwh
                    sessionId = idleDrainDao.insert(
                        IdleDrainEntity(
                            startTs = sessionStartTs,
                            socStart = socAtStart,
                            socEnd = soc,
                            kwhConsumed = kwh
                        )
                    )
                    Log.d(TAG, "Idle drain recorded: id=$sessionId, " +
                        "SOC ${socAtStart}→$soc (-${drop}%), ${String.format("%.2f", kwh)} kWh")
                } else if (drop >= MIN_SOC_DROP && sessionId != null) {
                    // Update existing session with latest SOC
                    val batteryKwh = settingsRepository.getBatteryCapacity()
                    val kwh = drop / 100.0 * batteryKwh
                    idleDrainDao.update(
                        IdleDrainEntity(
                            id = sessionId!!,
                            startTs = sessionStartTs,
                            endTs = now,
                            socStart = socAtStart,
                            socEnd = soc,
                            kwhConsumed = kwh
                        )
                    )
                }
            }
        } else if (tracking) {
            // Vehicle started moving or charging — finalize session
            finalize(now)
        }
    }

    private suspend fun finalize(now: Long) {
        val id = sessionId
        val drop = (socAtStart ?: 0) - (lastSoc ?: 0)

        if (id != null && drop >= MIN_SOC_DROP) {
            val batteryKwh = settingsRepository.getBatteryCapacity()
            val kwh = drop / 100.0 * batteryKwh
            idleDrainDao.update(
                IdleDrainEntity(
                    id = id,
                    startTs = sessionStartTs,
                    endTs = now,
                    socStart = socAtStart,
                    socEnd = lastSoc,
                    kwhConsumed = kwh
                )
            )
            Log.d(TAG, "Idle session finalized: id=$id, " +
                "SOC ${socAtStart}→$lastSoc, ${String.format("%.2f", kwh)} kWh")
        }

        // Reset
        tracking = false
        sessionId = null
        socAtStart = null
        lastSoc = null
    }
}
