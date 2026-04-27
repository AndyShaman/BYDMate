package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.remote.DiParsClient
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
 * Catch-up charging detection using cascade A/B/C based on CHARGING_CAPACITY
 * (per-session BMS counter) and SOC delta.
 *
 * Gate A (preferred): socDelta > 0 AND capDelta in MIN_DELTA_KWH..200.0
 *   → source = "autoservice_cap_delta"
 * Gate B (counter reset): socDelta > 0 AND currentCap in MIN_DELTA_KWH..200.0
 *   → source = "autoservice_cap_session"
 * Gate C (fallback): socDelta > 0, cap unreliable
 *   → source = "autoservice_soc_estimate"
 *
 * The single required gate is `socDelta > 0` — prevents phantom rows when SOC
 * did not change (the regression that produced phantom autoservice_catchup rows
 * in v2.4.15/v2.4.16 via the lifetime_kwh driving counter).
 */
@Singleton
class AutoserviceChargingDetector @Inject constructor(
    private val client: AutoserviceClient,
    private val chargeRepo: ChargeRepository,
    private val batteryHealthRepo: BatteryHealthRepository,
    private val stateStore: ChargingStateStore,
    private val classifier: ChargingTypeClassifier,
    private val settings: SettingsRepository,
    private val diParsClient: DiParsClient
) {
    companion object {
        const val MIN_DELTA_KWH = 0.5
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

    /**
     * Run the cascade detector.
     *
     * @param now            wall-clock time for the row's start/end timestamps.
     * @param observedKwAbs  optional last-known charging power magnitude in kW
     *                       (e.g. |DiPars Power| during the active session).
     *                       When provided, it overrides the kwh/hours heuristic
     *                       for AC/DC classification — much more accurate for
     *                       short sessions where the heuristic's 1-hour assumption
     *                       under-counts power by an order of magnitude.
     */
    suspend fun runCatchUp(
        now: Long = System.currentTimeMillis(),
        observedKwAbs: Double? = null
    ): CatchUpResult = mutex.withLock {
        _state.value = DetectorState.EVALUATING
        try {
            // Step 1: liveness check
            if (!client.isAvailable()) {
                android.util.Log.i(TAG, "runCatchUp: autoservice client not available")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
            }

            // Step 2: read snapshots
            val battery = client.readBatterySnapshot()
            val charging = client.readChargingSnapshot()

            // Step 3: SOC sentinel check
            val currentSoc = battery?.socPercent?.toInt()
            if (currentSoc == null) {
                android.util.Log.i(TAG, "runCatchUp: socPercent sentinel — BMS not initialized")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.SENTINEL)
            }

            // Step 4: load previous state; seed on cold start
            val prev = stateStore.load()
            if (prev.socPercent == null) {
                stateStore.save(
                    socPercent = currentSoc,
                    mileageKm = battery?.lifetimeMileageKm,
                    capacityKwh = charging?.chargingCapacityKwh,
                    ts = now
                )
                android.util.Log.i(TAG, "runCatchUp: cold start, seeded state soc=$currentSoc")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.BASELINE_INITIALIZED)
            }

            // Step 5: SOC delta gate — the regression-fix gate. NEVER create a row when SOC
            // did not increase. Covers phantom rows from the old lifetime_kwh driving counter.
            val socDelta = currentSoc - prev.socPercent
            if (socDelta <= 0) {
                android.util.Log.i(TAG, "runCatchUp: socDelta=$socDelta <= 0 → NO_DELTA (no charge)")
                stateStore.save(
                    socPercent = currentSoc,
                    mileageKm = battery?.lifetimeMileageKm,
                    capacityKwh = charging?.chargingCapacityKwh,
                    ts = now
                )
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA)
            }

            // Step 6: resolve kWh delta via cascade A → B → C
            val currentCap = charging?.chargingCapacityKwh?.toDouble()
            val prevCap = prev.capacityKwh?.toDouble()

            val delta: Double
            val detectionSource: String

            val capDelta = if (currentCap != null && prevCap != null) currentCap - prevCap else null

            when {
                // Gate A: cap delta is positive and plausible
                capDelta != null && capDelta in MIN_DELTA_KWH..200.0 -> {
                    delta = capDelta
                    detectionSource = "autoservice_cap_delta"
                    android.util.Log.i(TAG, "runCatchUp: Gate A — cap_delta=${"%.3f".format(delta)} kWh")
                }
                // Gate B: cap counter reset (or prev unavailable) — use current value if plausible
                currentCap != null && currentCap in MIN_DELTA_KWH..200.0 -> {
                    delta = currentCap
                    detectionSource = "autoservice_cap_session"
                    android.util.Log.i(TAG, "runCatchUp: Gate B — cap_session=${"%.3f".format(delta)} kWh")
                }
                // Gate C: fallback to SOC estimate
                else -> {
                    val nominalCapacity = settings.getBatteryCapacity()
                    delta = (socDelta / 100.0) * nominalCapacity
                    detectionSource = "autoservice_soc_estimate"
                    android.util.Log.i(TAG, "runCatchUp: Gate C — soc_estimate=${"%.3f".format(delta)} kWh (socDelta=$socDelta)")
                }
            }

            // Step 7 & 8: classify and cost
            val socStart = prev.socPercent
            val socEnd = currentSoc

            val type = classifier.fromGunState(charging?.gunConnectState)
                ?: classifier.fromObservedPowerKw(observedKwAbs)
                ?: classifier.heuristicByPower(delta, HEURISTIC_HOURS)
            val tariff = if (type == "DC") settings.getDcTariff() else settings.getHomeTariff()
            val cost = delta * tariff

            // Step 9: build ChargeEntity (lifetimeKwhAtStart/Finish null — legacy columns only)
            val charge = ChargeEntity(
                startTs = now,
                endTs = now,
                socStart = socStart,
                socEnd = socEnd,
                kwhCharged = delta,
                kwhChargedSoc = run {
                    val cap = settings.getBatteryCapacity()
                    (socEnd - socStart) / 100.0 * cap
                },
                type = type,
                cost = cost,
                status = "COMPLETED",
                lifetimeKwhAtStart = null,
                lifetimeKwhAtFinish = null,
                gunState = charging?.gunConnectState?.takeIf { it != GUN_STATE_NONE },
                detectionSource = detectionSource
            )

            // Step 10: insert charge
            val chargeId = chargeRepo.insertCharge(charge)

            // Step 11: BatterySnapshot when SOC delta is meaningful
            // SoH = BMS-reported (autoservice FID_SOH), NOT our derived value.
            // cellDeltaV/batTempAvg = best-effort from DiPlus; nulls if unavailable.
            if ((socEnd - socStart) >= MIN_SOC_DELTA_FOR_SNAPSHOT) {
                val capacity = batteryHealthRepo.calculateCapacity(delta, socStart, socEnd)
                val bmsSoh = battery?.sohPercent?.toDouble()
                val diPars = runCatching { diParsClient.fetch() }.getOrNull()
                val cellDelta = if (diPars?.maxCellVoltage != null && diPars.minCellVoltage != null)
                    diPars.maxCellVoltage - diPars.minCellVoltage else null
                val batTemp = diPars?.avgBatTemp?.toDouble()
                batteryHealthRepo.insert(
                    BatterySnapshotEntity(
                        timestamp = now,
                        odometerKm = battery?.lifetimeMileageKm?.toDouble(),
                        socStart = socStart,
                        socEnd = socEnd,
                        kwhCharged = delta,
                        calculatedCapacityKwh = capacity,
                        sohPercent = bmsSoh,
                        cellDeltaV = cellDelta,
                        batTempAvg = batTemp,
                        chargeId = chargeId
                    )
                )
            }

            // Step 12: roll state forward
            stateStore.save(
                socPercent = currentSoc,
                mileageKm = battery?.lifetimeMileageKm,
                capacityKwh = charging?.chargingCapacityKwh,
                ts = now
            )

            android.util.Log.i(TAG, "runCatchUp: SESSION_CREATED id=$chargeId, delta=${"%.3f".format(delta)}, source=$detectionSource, type=$type, socStart=$socStart, socEnd=$socEnd")
            _state.value = DetectorState.IDLE
            return CatchUpResult(CatchUpOutcome.SESSION_CREATED, chargeId, delta)
        } catch (e: Exception) {
            _state.value = DetectorState.ERROR
            return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
        }
    }
}
