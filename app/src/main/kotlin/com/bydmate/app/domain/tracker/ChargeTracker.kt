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
    private val batTempSamples = mutableListOf<Int>()
    private var totalPowerKw: Double = 0.0
    private var powerSampleCount: Int = 0

    companion object {
        private const val TAG = "ChargeTracker"
        private const val POINT_INTERVAL_MS = 30_000L // 30 seconds
        private const val DC_POWER_THRESHOLD = 50.0   // kW
        private const val MERGE_WINDOW_MS = 30 * 60 * 1000L // 30 min
        private const val SOC_TOLERANCE = 2
        private const val MAX_MERGES = 10
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
                                soc = data.soc,
                                batTemp = data.avgBatTemp
                            )
                        )
                        lastPointTs = now

                        // Accumulate battery temp
                        data.avgBatTemp?.let { batTempSamples.add(it) }

                        val absPower = kotlin.math.abs(power)
                        if (absPower > maxPowerKw) {
                            maxPowerKw = absPower
                        }
                        totalPowerKw += absPower
                        powerSampleCount++

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
        // Try to resume a suspended session
        val suspended = chargeRepository.getLastSuspendedCharge()
        if (suspended != null) {
            val age = now - (suspended.endTs ?: 0)
            val socOk = data.soc != null && suspended.socEnd != null &&
                    data.soc >= suspended.socEnd!! - SOC_TOLERANCE
            val mergeOk = suspended.mergedCount < MAX_MERGES

            if (age < MERGE_WINDOW_MS && socOk && mergeOk) {
                Log.d(TAG, "Resuming suspended charge id=${suspended.id}, age=${age / 1000}s")
                currentChargeId = suspended.id
                chargeStartTs = suspended.startTs
                socStart = suspended.socStart
                maxPowerKw = suspended.maxPowerKw ?: 0.0
                startLocation = location

                chargeRepository.updateCharge(suspended.copy(
                    status = "ACTIVE",
                    endTs = null,
                    mergedCount = suspended.mergedCount + 1
                ))

                _state.value = ChargeState.CHARGING
                lastPointTs = now

                // Restore stats from existing points
                val existingPoints = chargeRepository.getChargePoints(suspended.id)
                batTempSamples.clear()
                existingPoints.mapNotNull { it.batTemp }.forEach { batTempSamples.add(it) }
                totalPowerKw = existingPoints.mapNotNull { it.powerKw }.sumOf { kotlin.math.abs(it) }
                powerSampleCount = existingPoints.count { it.powerKw != null }

                data.avgBatTemp?.let { batTempSamples.add(it) }
                synchronized(pendingPoints) { pendingPoints.clear() }
                return
            } else {
                finalizeSuspended(suspended)
            }
        }

        // Create new session
        socStart = data.soc
        chargeStartTs = now
        maxPowerKw = kotlin.math.abs(data.power ?: 0.0)
        startLocation = location
        synchronized(pendingPoints) {
            pendingPoints.clear()
        }
        batTempSamples.clear()
        totalPowerKw = 0.0
        powerSampleCount = 0
        data.avgBatTemp?.let { batTempSamples.add(it) }
        lastPointTs = now

        val chargeId = chargeRepository.insertCharge(
            ChargeEntity(
                startTs = now,
                socStart = data.soc,
                lat = location?.latitude,
                lon = location?.longitude,
                status = "ACTIVE"
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
                soc = data.soc,
                batTemp = data.avgBatTemp
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
                soc = data.soc,
                batTemp = data.avgBatTemp
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

        // Battery temperature stats
        val batAvg = if (batTempSamples.isNotEmpty()) batTempSamples.average() else null
        val batMax = batTempSamples.maxOrNull()?.toDouble()
        val batMin = batTempSamples.minOrNull()?.toDouble()
        val avgPower = if (powerSampleCount > 0) totalPowerKw / powerSampleCount else null

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
                lon = startLocation?.longitude,
                batTempAvg = batAvg,
                batTempMax = batMax,
                batTempMin = batMin,
                avgPowerKw = avgPower,
                status = "COMPLETED",
                cellVoltageMin = data.minCellVoltage,
                cellVoltageMax = data.maxCellVoltage,
                voltage12v = data.voltage12v,
                exteriorTemp = data.exteriorTemp
            )
        )

        Log.d(TAG, "Charge ended: id=$chargeId, socStart=$socStart, socEnd=$socEnd, kwh=$kwh, maxPower=$maxPowerKw kW, type=$chargeType, batTemp=$batAvg")

        // Reset
        _state.value = ChargeState.IDLE
        currentChargeId = null
        socStart = null
        maxPowerKw = 0.0
        startLocation = null
        batTempSamples.clear()
        totalPowerKw = 0.0
        powerSampleCount = 0
        synchronized(pendingPoints) {
            pendingPoints.clear()
        }
    }

    /**
     * Suspend current charge session on service shutdown (can be resumed later).
     */
    suspend fun forceEnd(lastData: DiParsData?) {
        if (_state.value != ChargeState.CHARGING || currentChargeId == null) return
        val chargeId = currentChargeId!!
        Log.w(TAG, "suspending charge session id=$chargeId")

        // Flush remaining points
        lastData?.let {
            pendingPoints.add(ChargePointEntity(
                chargeId = chargeId, timestamp = System.currentTimeMillis(),
                powerKw = it.power, soc = it.soc, batTemp = it.avgBatTemp
            ))
        }
        flushPoints()

        // Mark as SUSPENDED (not finalized)
        val existing = chargeRepository.getChargeById(chargeId) ?: return
        chargeRepository.updateCharge(existing.copy(
            status = "SUSPENDED",
            endTs = System.currentTimeMillis(),
            socEnd = lastData?.soc
        ))

        // Reset in-memory
        _state.value = ChargeState.IDLE
        currentChargeId = null
        socStart = null
        maxPowerKw = 0.0
        startLocation = null
        batTempSamples.clear()
        totalPowerKw = 0.0
        powerSampleCount = 0
        synchronized(pendingPoints) { pendingPoints.clear() }
    }

    /**
     * Finalize a SUSPENDED session that is too old to resume.
     */
    suspend fun finalizeSuspended(charge: ChargeEntity) {
        val allPoints = chargeRepository.getChargePoints(charge.id)
        val batteryCapacity = settingsRepository.getBatteryCapacity()
        val kwhByPower = calculator.chargePowerIntegration(allPoints)
        val kwhBySoc = if (charge.socStart != null && charge.socEnd != null) {
            calculator.chargeKwhFromSoc(charge.socStart, charge.socEnd, batteryCapacity)
        } else null
        val chargeType = if ((charge.maxPowerKw ?: 0.0) > DC_POWER_THRESHOLD) "DC" else "AC"
        val tariff = if (chargeType == "DC") settingsRepository.getDcTariff() else settingsRepository.getHomeTariff()
        val kwh = if (kwhByPower > 0) kwhByPower else (kwhBySoc ?: 0.0)

        val temps = allPoints.mapNotNull { it.batTemp }
        chargeRepository.updateCharge(charge.copy(
            status = "COMPLETED",
            kwhCharged = kwhByPower,
            kwhChargedSoc = kwhBySoc,
            type = chargeType,
            cost = kwh * tariff,
            batTempAvg = if (temps.isNotEmpty()) temps.average() else null,
            batTempMax = temps.maxOrNull()?.toDouble(),
            batTempMin = temps.minOrNull()?.toDouble(),
            avgPowerKw = if (allPoints.isNotEmpty()) {
                allPoints.mapNotNull { it.powerKw }.map { kotlin.math.abs(it) }.average()
            } else null
        ))
        Log.d(TAG, "Finalized suspended charge id=${charge.id}")
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
