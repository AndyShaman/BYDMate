package com.bydmate.app.data.charging

import com.bydmate.app.data.autoservice.AutoserviceClient
import com.bydmate.app.data.local.entity.BatterySnapshotEntity
import com.bydmate.app.data.local.entity.ChargeEntity
import com.bydmate.app.data.nativestack.ParsReader
import com.bydmate.app.data.remote.DiParsData
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
    STILL_CHARGING,
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
    private val parsReader: ParsReader
) {
    companion object {
        const val MIN_DELTA_KWH = 0.5
        // Last-resort heuristic duration when prev.ts is unset (cold start).
        // Real elapsed time (now - prev.ts) is preferred and used whenever
        // available — see runCatchUp Step 7. Was the only path before v2.5.15
        // and produced false-DC rows for overnight AC sessions (30 kWh / 1h
        // = 30 kW → DC).
        const val HEURISTIC_HOURS_FALLBACK = 1.0
        // Floor on elapsed-time heuristic to avoid div-by-near-zero blowing
        // a short live-edge fire into a false DC classification.
        const val MIN_ELAPSED_HOURS = 0.05
        // Ceiling on elapsed-time heuristic. Caps the rare case where the
        // car sat idle for days with no state save in between, then a DC
        // fast charge made the per-hour rate look tiny (30 kWh / 48h →
        // 0.625 kW → false AC). 16h covers any realistic single-night AC
        // session; anything longer is multi-day idle and we'd rather bias
        // toward AC tariff anyway (cheaper for the user when uncertain).
        const val MAX_ELAPSED_HOURS = 16.0
        // BMS chargingCapacityKwh sanity gate. The per-session counter has been
        // observed to under-report on overnight AC (counter resets across BMS
        // pause/resume sub-phases — caught a 51% SOC charge as 21.5 kWh in
        // v2.5.15). Compare any cap-derived candidate (Gate A delta or Gate B
        // session) to socDelta × nominalCapacity. When the ratio falls outside
        // [LOW..HIGH] the BMS counter is treated as broken and we fall back to
        // the SOC estimate. LOW=0.70 tolerates a fresh-battery efficiency tax
        // and SoH down to ~70% before false-positives. HIGH=1.30 catches stale
        // baselines and counter overruns.
        const val SOC_SANITY_RATIO_LOW = 0.70
        const val SOC_SANITY_RATIO_HIGH = 1.30
        // Min SOC delta for BatterySnapshot capacity calculation (matches BatteryHealthRepository).
        const val MIN_SOC_DELTA_FOR_SNAPSHOT = 5
        // Gun not connected — autoservice gunConnectState value meaning "no gun".
        private const val GUN_STATE_NONE = 1
        private const val TAG = "AutoserviceDetector"
    }

    private val mutex = Mutex()
    @Volatile private var lastSample: DiParsData? = null
    private val _state = MutableStateFlow(DetectorState.IDLE)
    val state: StateFlow<DetectorState> = _state

    /** Feed the most recent shared-loop sample so runCatchUp can avoid a live fetch. */
    fun onSample(data: DiParsData) {
        lastSample = data
    }

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

            // Step 3: SOC sentinel check with DiPars fallback.
            // The autoservice SOC fid can sentinel-out for several reasons:
            //   - cold-start window: TrackingService runs catch-up before
            //     autoservice has warmed its fid cache (observed 2026-04-30).
            //   - 100% balancing: BMS may temporarily detach some telemetry
            //     fids while equalizing cells (per car manual).
            // DiPars lives in a separate process with its own cache and
            // typically holds the last-known SOC across both windows. When
            // it has a valid 0..100 value, we use it as the SOC source so
            // catch-up does not lose a real charging session.
            val autoSoc = battery?.socPercent?.toInt()
            val currentSoc: Int = if (autoSoc != null) {
                autoSoc
            } else {
                val diParsSoc = (lastSample ?: runCatching { parsReader.fetch() }.getOrNull())
                    ?.soc?.takeIf { it in 0..100 }
                if (diParsSoc != null) {
                    android.util.Log.i(TAG, "runCatchUp: autoservice SOC sentinel → DiPars fallback soc=$diParsSoc")
                    diParsSoc
                } else {
                    android.util.Log.i(TAG, "runCatchUp: socPercent sentinel from BOTH autoservice AND DiPars")
                    _state.value = DetectorState.IDLE
                    return CatchUpResult(CatchUpOutcome.SENTINEL)
                }
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

            // Step 4.5: gun-connected gate — physical charging is still in progress.
            // Reproduced by user 2026-04-30: car woken up while still on the charger.
            // SOC had grown across DiLink sleep, runCatchUp would otherwise create a
            // COMPLETED row and advance the baseline, splitting one real session into
            // N phantom rows on each ignition cycle. While the gun is inserted, defer
            // to the live gun-disconnect edge in TrackingService to finalize.
            //
            // gun=null is treated as "still charging" only when the autoservice
            // charging device is otherwise responsive (any sibling fid readable) —
            // that combination means the gun fid alone glitched, and unblocking
            // would re-open the same phantom-row regression in a different shape.
            // When ALL charging fids are silent, the device is unsupported on this
            // firmware; we fall back to legacy behavior so charge logging is not
            // permanently blocked on other BYD models.
            val gun = charging?.gunConnectState
            // gun=0 is the autoservice "unknown" sentinel — ChargingTypeClassifier
            // already treats it as null. We mirror that here so a firmware where 0
            // is the steady-state value doesn't permanently block charge logging.
            val gunResolved = gun?.takeIf { it != 0 }
            val gunIsConnected = gunResolved != null && gunResolved != GUN_STATE_NONE
            val chargingDeviceReadable = charging != null && (
                charging.chargingType != null ||
                    charging.batteryType != null ||
                    charging.bmsState != null ||
                    charging.chargingCapacityKwh != null ||
                    charging.chargeBatteryVoltV != null
                )
            val gunGlitch = gunResolved == null && chargingDeviceReadable
            if (gunIsConnected || gunGlitch) {
                android.util.Log.i(TAG, "runCatchUp: gun=$gun, deviceReadable=$chargingDeviceReadable → STILL_CHARGING (defer to live edge)")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.STILL_CHARGING)
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

            // Step 6: resolve kWh delta via cascade A → B → C with SOC sanity check
            val currentCap = charging?.chargingCapacityKwh?.toDouble()
            val prevCap = prev.capacityKwh?.toDouble()
            val nominalCapacity = settings.getBatteryCapacity()
            val socEstimate = (socDelta / 100.0) * nominalCapacity

            val delta: Double
            val detectionSource: String

            val capDelta = if (currentCap != null && prevCap != null) currentCap - prevCap else null

            // Pick the BMS-derived candidate (Gate A preferred, Gate B fallback).
            val capCandidate: Pair<Double, String>? = when {
                capDelta != null && capDelta in MIN_DELTA_KWH..200.0 ->
                    capDelta to "autoservice_cap_delta"
                currentCap != null && currentCap in MIN_DELTA_KWH..200.0 ->
                    currentCap to "autoservice_cap_session"
                else -> null
            }

            // Sanity-gate the BMS counter against the SOC-derived estimate.
            // Diverging values mean the per-session counter is broken (sub-phase
            // reset, stale baseline, residue from a previous session). Trust SOC
            // in that case — coarser but always-monotonic.
            if (capCandidate == null) {
                delta = socEstimate
                detectionSource = "autoservice_soc_estimate"
                android.util.Log.i(TAG, "runCatchUp: Gate C — soc_estimate=${"%.3f".format(delta)} kWh (socDelta=$socDelta)")
            } else {
                val (capValue, capSource) = capCandidate
                val ratio = if (socEstimate > 0.0) capValue / socEstimate else Double.NaN
                if (ratio.isFinite() && ratio in SOC_SANITY_RATIO_LOW..SOC_SANITY_RATIO_HIGH) {
                    delta = capValue
                    detectionSource = capSource
                    android.util.Log.i(
                        TAG,
                        "runCatchUp: $capSource — kwh=${"%.3f".format(delta)} ratio=${"%.2f".format(ratio)} (socEstimate=${"%.3f".format(socEstimate)})"
                    )
                } else {
                    delta = socEstimate
                    detectionSource = "autoservice_soc_fallback"
                    android.util.Log.i(
                        TAG,
                        "runCatchUp: SOC fallback — bms=${"%.3f".format(capValue)} socEstimate=${"%.3f".format(socEstimate)} ratio=${"%.2f".format(ratio)} ($capSource diverged)"
                    )
                }
            }

            // Step 7 & 8: classify and cost
            val socStart = prev.socPercent
            val socEnd = currentSoc

            // Real elapsed time since the previous state save — far more
            // accurate for AC/DC heuristic than a fixed 1-hour assumption.
            // Overnight AC of 30 kWh divided by the old 1h constant looked
            // like 30 kW → DC; divided by the actual ~8h elapsed it lands at
            // ~4 kW → AC, matching reality. The floor blocks div-by-tiny on
            // back-to-back live-edge fires; the ceiling caps multi-day idle.
            // A backward clock jump (now < prev.ts, e.g. user-set time
            // correction) makes raw elapsed negative; we treat that as
            // "no reliable elapsed signal" and fall back to the 1h baseline.
            val rawElapsedHours = (now - prev.ts) / 3_600_000.0
            val elapsedHours = when {
                prev.ts <= 0L || rawElapsedHours <= 0.0 -> HEURISTIC_HOURS_FALLBACK
                else -> rawElapsedHours.coerceIn(MIN_ELAPSED_HOURS, MAX_ELAPSED_HOURS)
            }
            val type = classifier.fromGunState(charging?.gunConnectState)
                ?: classifier.fromObservedPowerKw(observedKwAbs)
                ?: classifier.heuristicByPower(delta, elapsedHours)
            val tariff = if (type == "DC") settings.getDcTariff() else settings.getHomeTariff()
            val cost = delta * tariff

            // Step 9: build ChargeEntity (lifetimeKwhAtStart/Finish null — legacy columns only)
            val charge = ChargeEntity(
                startTs = now,
                endTs = now,
                socStart = socStart,
                socEnd = socEnd,
                kwhCharged = delta,
                kwhChargedSoc = (socEnd - socStart) / 100.0 * nominalCapacity,
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
                val diPars = lastSample ?: runCatching { parsReader.fetch() }.getOrNull()
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
