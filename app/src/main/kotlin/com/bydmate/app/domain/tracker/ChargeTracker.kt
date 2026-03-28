package com.bydmate.app.domain.tracker

import android.location.Location
import android.util.Log
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.local.entity.ChargePointEntity
import com.bydmate.app.data.remote.DiParsData
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.calculator.ConsumptionCalculator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

enum class ChargeState { IDLE, CHARGING }

@Singleton
class ChargeTracker @Inject constructor(
    private val chargeRepository: ChargeRepository,
    private val settingsRepository: SettingsRepository,
    private val calculator: ConsumptionCalculator
) {
    private val _state = MutableStateFlow(ChargeState.IDLE)
    val state: StateFlow<ChargeState> = _state

    private var currentChargeId: Long? = null
    private var chargeStartTs: Long = 0
    private var socStart: Int? = null
    private var maxPowerKw: Double = 0.0
    private var startLocation: Location? = null
    private val pendingPoints = Collections.synchronizedList(mutableListOf<ChargePointEntity>())
    private var lastPointTs: Long = 0

    companion object {
        private const val TAG = "ChargeTracker"
        private const val POINT_INTERVAL_MS = 30_000L // 30 seconds
        private const val DC_POWER_THRESHOLD = 50.0   // kW
    }

    suspend fun onData(data: DiParsData, location: Location?) {
        val chargeGun = data.chargeGunState ?: 0
        val power = data.power ?: 0.0
        val now = System.currentTimeMillis()

        val isCharging = chargeGun == 2 && power < -1.0

        when (_state.value) {
            ChargeState.IDLE -> {
                if (isCharging) {
                    startCharging(data, location, now)
                }
            }
            ChargeState.CHARGING -> {
                if (!isCharging) {
                    endCharging(data, now)
                } else {
                    // Record charge point at intervals
                    if (now - lastPointTs >= POINT_INTERVAL_MS) {
                        val chargeId = currentChargeId ?: return
                        pendingPoints.add(
                            ChargePointEntity(
                                chargeId = chargeId,
                                timestamp = now,
                                powerKw = power,
                                soc = data.soc
                            )
                        )
                        lastPointTs = now

                        val absPower = kotlin.math.abs(power)
                        if (absPower > maxPowerKw) {
                            maxPowerKw = absPower
                        }

                        // Flush in batches
                        if (pendingPoints.size >= 20) {
                            flushPoints()
                        }
                    }
                }
            }
        }
    }

    private suspend fun startCharging(data: DiParsData, location: Location?, now: Long) {
        socStart = data.soc
        chargeStartTs = now
        maxPowerKw = kotlin.math.abs(data.power ?: 0.0)
        startLocation = location
        synchronized(pendingPoints) {
            pendingPoints.clear()
        }
        lastPointTs = now

        val chargeId = chargeRepository.insertCharge(
            ChargeEntity(
                startTs = now,
                socStart = data.soc,
                lat = location?.latitude,
                lon = location?.longitude
            )
        )
        currentChargeId = chargeId
        _state.value = ChargeState.CHARGING

        Log.d(TAG, "Charge started: id=$chargeId, soc=${data.soc}, power=${data.power}, type=pending")

        // Record first point
        pendingPoints.add(
            ChargePointEntity(
                chargeId = chargeId,
                timestamp = now,
                powerKw = data.power,
                soc = data.soc
            )
        )
    }

    private suspend fun endCharging(data: DiParsData, now: Long) {
        val chargeId = currentChargeId
        if (chargeId == null) {
            Log.d(TAG, "endCharging called but currentChargeId is null, ignoring")
            return
        }

        // Record final point
        pendingPoints.add(
            ChargePointEntity(
                chargeId = chargeId,
                timestamp = now,
                powerKw = data.power,
                soc = data.soc
            )
        )
        flushPoints()

        val socEnd = data.soc
        val batteryCapacity = settingsRepository.getBatteryCapacity()

        // Get all points for power integration
        val allPoints = chargeRepository.getChargePoints(chargeId)
        val kwhByPower = calculator.chargePowerIntegration(allPoints)
        val kwhBySoc = if (socStart != null && socEnd != null) {
            calculator.chargeKwhFromSoc(socStart!!, socEnd, batteryCapacity)
        } else null

        // Determine type: DC if peak power > 50 kW
        val chargeType = if (maxPowerKw > DC_POWER_THRESHOLD) "DC" else "AC"

        // Calculate cost
        val tariff = if (chargeType == "DC") {
            settingsRepository.getDcTariff()
        } else {
            settingsRepository.getHomeTariff()
        }
        val kwh = if (kwhByPower > 0) kwhByPower else (kwhBySoc ?: 0.0)
        val cost = kwh * tariff

        chargeRepository.updateCharge(
            ChargeEntity(
                id = chargeId,
                startTs = chargeStartTs,
                endTs = now,
                socStart = socStart,
                socEnd = socEnd,
                kwhCharged = kwhByPower,
                kwhChargedSoc = kwhBySoc,
                maxPowerKw = maxPowerKw,
                type = chargeType,
                cost = cost,
                lat = startLocation?.latitude,
                lon = startLocation?.longitude
            )
        )

        Log.d(TAG, "Charge ended: id=$chargeId, socStart=$socStart, socEnd=$socEnd, kwh=$kwh, maxPower=$maxPowerKw kW, type=$chargeType")

        // Reset
        _state.value = ChargeState.IDLE
        currentChargeId = null
        socStart = null
        maxPowerKw = 0.0
        startLocation = null
        synchronized(pendingPoints) {
            pendingPoints.clear()
        }
    }

    /**
     * Force-end the current charge session on service shutdown.
     */
    suspend fun forceEnd(lastData: DiParsData?) {
        if (_state.value != ChargeState.CHARGING || currentChargeId == null) return
        Log.w(TAG, "forceEnd: ending active charge id=$currentChargeId on service shutdown")
        val data = lastData ?: DiParsData(
            soc = null, speed = 0, mileage = null, power = null,
            chargeGunState = null, maxBatTemp = null, avgBatTemp = null,
            minBatTemp = null, chargingStatus = null
        )
        endCharging(data, System.currentTimeMillis())
    }

    private suspend fun flushPoints() {
        val snapshot: List<ChargePointEntity>
        synchronized(pendingPoints) {
            if (pendingPoints.isEmpty()) return
            snapshot = ArrayList(pendingPoints)
            pendingPoints.clear()
        }
        chargeRepository.insertChargePoints(snapshot)
    }
}
