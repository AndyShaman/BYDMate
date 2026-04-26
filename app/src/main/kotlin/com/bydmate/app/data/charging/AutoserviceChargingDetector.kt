package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.repository.BatteryHealthRepository
import com.bydmate.app.data.repository.ChargeRepository
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

enum class DetectorState { IDLE, EVALUATING, ERROR }

enum class CatchUpOutcome {
    AUTOSERVICE_UNAVAILABLE,
    SENTINEL,
    BASELINE_INITIALIZED,
    NO_DELTA,
    SESSION_CREATED
}

data class CatchUpResult(
    val outcome: CatchUpOutcome,
    val chargeId: Long? = null,
    val deltaKwh: Double? = null
)

/**
 * Catch-up charging detection driven by lifetime_kwh delta from the BMS.
 *
 * On every TrackingService start (and later: optional live ticks) we read the
 * current lifetime_kwh, compare to the stored baseline, and synthesize a
 * COMPLETED ChargeEntity when the delta exceeds threshold. This works while
 * the head unit slept through the actual charging session.
 *
 * Phase 1: catch-up only. Live tick (Phase 3) will reuse the same state
 * machine and Mutex.
 */
@Singleton
class AutoserviceChargingDetector @Inject constructor(
    private val client: AutoserviceClient,
    private val chargeRepo: ChargeRepository,
    private val batteryHealthRepo: BatteryHealthRepository,
    private val baselineStore: ChargingBaselineStore,
    private val classifier: ChargingTypeClassifier,
    private val settings: SettingsRepository
) {
    companion object {
        const val MIN_DELTA_KWH = 0.5
        // Safety floor below MIN_DELTA_KWH — protects against BMS calibration drift
        // (sub-100Wh wobble in lifetime_kwh after a full charge → wrong-positive
        // session row with kwhCharged ≈ 0). Empty rows are also DB-cleaned at
        // service start, but the floor avoids the flicker entirely.
        const val SAFETY_FLOOR_KWH = 0.05
        // Heuristic duration when we have no other clue (catch-up after deep sleep).
        // 1 hour is a safe midpoint: under 20 kWh → AC tariff (cheaper, safer for user
        // pocket); above → DC. Phase 3 live tick will replace this with measured ms.
        const val HEURISTIC_HOURS = 1.0
        // Min SOC delta for BatterySnapshot capacity calculation (matches BatteryHealthRepository).
        const val MIN_SOC_DELTA_FOR_SNAPSHOT = 5
        // Gun not connected — autoservice gunConnectState value meaning "no gun".
        private const val GUN_STATE_NONE = 1
        private const val TAG = "AutoserviceDetector"
    }

    private val mutex = Mutex()
    private val _state = MutableStateFlow(DetectorState.IDLE)
    val state: StateFlow<DetectorState> = _state

    /** Public so TrackingService polling loop can update on every DiPars sample. */
    suspend fun recordLastSeenSoc(soc: Int) = settings.setLastSeenSoc(soc)

    suspend fun runCatchUp(now: Long = System.currentTimeMillis()): CatchUpResult = mutex.withLock {
        _state.value = DetectorState.EVALUATING
        try {
            if (!client.isAvailable()) {
                android.util.Log.i(TAG, "runCatchUp: autoservice client not available")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
            }
            val battery = client.readBatterySnapshot()
            val lifetimeKwh = battery?.lifetimeKwh?.toDouble()
            if (lifetimeKwh == null) {
                android.util.Log.i(TAG, "runCatchUp: lifetimeKwh sentinel — BMS not initialized")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.SENTINEL)
            }

            val baseline = baselineStore.getBaseline()
            if (baseline == null) {
                baselineStore.setBaseline(lifetimeKwh, now)
                android.util.Log.i(TAG, "runCatchUp: cold start, baseline=${"%.3f".format(lifetimeKwh)} kWh")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.BASELINE_INITIALIZED)
            }

            val delta = lifetimeKwh - baseline
            if (delta < SAFETY_FLOOR_KWH) {
                android.util.Log.i(TAG, "runCatchUp: delta=${"%.3f".format(delta)} kWh < safety floor $SAFETY_FLOOR_KWH → NO_DELTA")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA, deltaKwh = delta)
            }
            if (delta < MIN_DELTA_KWH) {
                android.util.Log.i(TAG, "runCatchUp: lifetime=${"%.3f".format(lifetimeKwh)}, baseline=${"%.3f".format(baseline)}, delta=${"%.3f".format(delta)} → below MIN_DELTA_KWH=$MIN_DELTA_KWH")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA, deltaKwh = delta)
            }

            val charging = client.readChargingSnapshot()
            val type = classifier.fromGunState(charging?.gunConnectState)
                ?: classifier.heuristicByPower(delta, HEURISTIC_HOURS)
            val tariff = if (type == "DC") settings.getDcTariff() else settings.getHomeTariff()
            val cost = delta * tariff

            val socEnd = battery.socPercent?.toInt()
            val socStart = settings.getLastSeenSoc()

            val charge = ChargeEntity(
                startTs = now,                  // unknown actual start; record as "detected at"
                endTs = now,
                socStart = socStart,
                socEnd = socEnd,
                kwhCharged = delta,
                kwhChargedSoc = if (socStart != null && socEnd != null && socEnd > socStart) {
                    val cap = settings.getBatteryCapacity()
                    (socEnd - socStart) / 100.0 * cap
                } else null,
                type = type,
                cost = cost,
                status = "COMPLETED",
                lifetimeKwhAtStart = baseline,
                lifetimeKwhAtFinish = lifetimeKwh,
                gunState = charging?.gunConnectState?.takeIf { it != GUN_STATE_NONE },
                detectionSource = "autoservice_catchup"
            )
            val chargeId = chargeRepo.insertCharge(charge)
            // Baseline rolls forward via DAO MAX query — no explicit setBaseline needed.

            // BatterySnapshot for capacity / SoH tracking when SOC delta is meaningful.
            if (socStart != null && socEnd != null && (socEnd - socStart) >= MIN_SOC_DELTA_FOR_SNAPSHOT) {
                val capacity = batteryHealthRepo.calculateCapacity(delta, socStart, socEnd)
                val soh = capacity?.let { batteryHealthRepo.calculateSoh(it) }
                batteryHealthRepo.insert(
                    BatterySnapshotEntity(
                        timestamp = now,
                        odometerKm = battery.lifetimeMileageKm?.toDouble(),
                        socStart = socStart,
                        socEnd = socEnd,
                        kwhCharged = delta,
                        calculatedCapacityKwh = capacity,
                        sohPercent = soh,
                        cellDeltaV = null,
                        batTempAvg = null,
                        chargeId = chargeId
                    )
                )
            }

            android.util.Log.i(TAG, "runCatchUp: SESSION_CREATED id=$chargeId, lifetime=${"%.3f".format(lifetimeKwh)}, baseline=${"%.3f".format(baseline)}, delta=${"%.3f".format(delta)}, type=$type, socStart=$socStart, socEnd=$socEnd")
            _state.value = DetectorState.IDLE
            return CatchUpResult(CatchUpOutcome.SESSION_CREATED, chargeId, delta)
        } catch (e: Exception) {
            _state.value = DetectorState.ERROR
            return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
        }
    }
}
