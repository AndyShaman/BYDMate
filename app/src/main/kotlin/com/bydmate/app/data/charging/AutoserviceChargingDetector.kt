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
 * The required gate is `socDelta >= MIN_SOC_DELTA_FOR_CHARGE` with the odometer
 * unchanged since the parked anchor — prevents phantom rows when SOC barely
 * moved (the regression that produced phantom autoservice_catchup rows in
 * v2.4.15/v2.4.16 via the lifetime_kwh driving counter) and stops a drive that
 * happened in the sleep gap from smearing into a reconstructed session.
 */
@Singleton
class AutoserviceChargingDetector @Inject constructor(
    private val client: AutoserviceClient,
    private val chargeRepo: ChargeRepository,
    private val batteryHealthRepo: BatteryHealthRepository,
    private val stateStore: ChargingStateStore,
    private val classifier: ChargingTypeClassifier,
    private val settings: SettingsRepository,
    private val parsReader: ParsReader,
    private val journal: CatchUpJournal
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
        // Minimum SOC rise (percentage points) for a wake-up jump to count as a
        // real charging session. Anything below this is noise: a BMS/LFP
        // recalibration (~1%) or regen sub-1% while parked can nudge SOC up
        // without any charge. 2 = a 1% rise is always dropped, never logged.
        const val MIN_SOC_DELTA_FOR_CHARGE = 2
        // SOC rise (percentage points) at or above which a charge is trusted
        // UNCONDITIONALLY — the odometer gate no longer applies. The only
        // non-charge SOC rises (recalibration ~1%, regen sub-1% while parked)
        // stay under 2%, so a >=3% rise is always a real charge regardless of
        // the odometer (the anchor's mileage can be stale — it freezes while
        // SOC is flat; field report 2026-06-11, Song lost an 11% dacha charge
        // to a stale-mileage odometer gate).
        const val SOC_DELTA_TRUSTED_CHARGE = 3
        // Odometer movement (km) above which the gap between the parked anchor and
        // wake-up contained a drive — the stored start-of-charge SOC is then no
        // longer the true charge start, so we skip reconstruction. Applies ONLY
        // to socDelta below SOC_DELTA_TRUSTED_CHARGE (i.e. exactly 2%).
        const val ODOMETER_MOVED_EPSILON_KM = 1.0f
        // Gun not connected — autoservice gunConnectState value meaning "no gun".
        private const val GUN_STATE_NONE = 1
        // A transient gun-fid glitch clears within a read or two; a gun fid
        // that stays silent across this many consecutive runs while sibling
        // fids answer is unsupported firmware — stop deferring so charge
        // logging is not permanently blocked on such cars.
        const val MAX_CONSECUTIVE_GUN_GLITCHES = 3
        // Plausibility floor for BMS-reported SOH in the SOC→kWh conversion
        // (#28). Readings below this are sentinels or garbage, not a real
        // pack — fall back to nominal capacity.
        const val SOH_SANITY_MIN = 50.0
        private const val TAG = "AutoserviceDetector"
    }

    private val mutex = Mutex()
    // Consecutive gun-glitch verdicts (guarded by mutex). Reset on any
    // successful gun read; past MAX_CONSECUTIVE_GUN_GLITCHES the gun fid is
    // treated as unsupported and the cascade proceeds without it.
    private var consecutiveGunGlitches = 0
    @Volatile private var lastSample: DiParsData? = null
    private val _state = MutableStateFlow(DetectorState.IDLE)
    val state: StateFlow<DetectorState> = _state

    /** Feed the most recent shared-loop sample so runCatchUp can avoid a live fetch. */
    fun onSample(data: DiParsData) {
        lastSample = data
    }

    /**
     * Roll the persisted charge-start anchor forward on every live poll while the
     * car is NOT charging. This is the only moment the pre-charge SOC can be
     * captured: the app is dead for the whole of a sleep-charge, so the anchor
     * must already hold the SOC from the last tick before shutdown (≈ the SOC the
     * car arrived at).
     *
     * Roll forward only while SOC is non-increasing (driving / idle drain). A SOC
     * INCREASE means a charge happened while we were away — we deliberately do NOT
     * overwrite the low anchor here, so runCatchUp can reconstruct the session
     * from it. That also makes the wake-up path race-free without a lock-step
     * flag: a post-charge 80% tick can never clobber the 10% anchor before
     * reconstruction reads it.
     *
     * @return true when the live SOC is STRICTLY ABOVE the anchor with the gun
     *         out — a charge happened that catch-up has not reconstructed (e.g.
     *         a stale autoservice read at wake resolved NO_DELTA). The caller
     *         should re-arm catch-up — audit 2026-06-11, lost sleep-charge on
     *         Song.
     */
    suspend fun recordParkedAnchor(data: DiParsData, now: Long = System.currentTimeMillis()): Boolean {
        val gun = data.chargeGunState
        val gunConnected = gun != null && gun != GUN_STATE_NONE && gun != 0
        if (gunConnected) return false                 // live charge — keep the start anchor
        val soc = data.soc?.takeIf { it in 0..100 } ?: return false
        return mutex.withLock {
            val prevSoc = stateStore.load().socPercent
            when {
                // Un-reconstructed charge — re-arm. Negative power means energy
                // is flowing IN right now (live charge still in progress, e.g.
                // Song reports gun=null while charging; also covers regen while
                // driving) — never re-arm mid-charge, wait for power to settle.
                prevSoc != null && soc > prevSoc -> (data.power ?: 0.0) >= 0.0
                else -> {
                    // prevSoc == null → seed; soc < prevSoc → driving roll-forward;
                    // soc == prevSoc → SOC flat but the car may still be moving —
                    // the mileage MUST keep rolling. Freezing it here let the
                    // anchor's odometer go stale (a 7 km drive at constant SOC),
                    // and the Step-5 odometer gate then ate a real dacha charge
                    // (field report 2026-06-11, Song, 10 kWh lost).
                    stateStore.save(
                        socPercent = soc,
                        mileageKm = data.mileage?.toFloat(),
                        capacityKwh = null,
                        ts = now
                    )
                    false
                }
            }
        }
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
                logJournal(now, "UNAVAILABLE")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
            }

            // Step 2: read snapshots
            val battery = client.readBatterySnapshot()
            val charging = client.readChargingSnapshot()

            // Step 3: SOC sentinel check. The autoservice SOC fid can sentinel-out
            // during the cold-start window (TrackingService runs catch-up before
            // autoservice has warmed its fid cache, observed 2026-04-30) or while
            // the BMS balances cells at 100%. There is no independent second SOC
            // source — parsReader reads the same native autoservice — so a
            // sentinel here means "retry later": return SENTINEL without touching
            // state and let TrackingService re-run catch-up once the fid warms up.
            val currentSoc: Int = battery?.socPercent?.toInt() ?: run {
                android.util.Log.i(TAG, "runCatchUp: autoservice SOC sentinel → SENTINEL (retry later)")
                logJournal(now, "SENTINEL")
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
                logJournal(now, "BASELINE_INITIALIZED soc=$currentSoc")
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
            if (gunResolved != null) consecutiveGunGlitches = 0
            val gunIsConnected = gunResolved != null && gunResolved != GUN_STATE_NONE
            // Only DYNAMIC charging fids vouch for the gun fid. batteryType is
            // a constant (LFP) that stays readable on firmwares where the gun
            // fid is simply unsupported — counting it turned every catch-up
            // into STILL_CHARGING and permanently blocked charge logging there.
            val chargingDeviceReadable = charging != null && (
                charging.chargingType != null ||
                    charging.bmsState != null ||
                    charging.chargingCapacityKwh != null ||
                    charging.chargeBatteryVoltV != null
                )
            var gunGlitch = gunResolved == null && chargingDeviceReadable
            if (gunGlitch) {
                consecutiveGunGlitches++
                if (consecutiveGunGlitches > MAX_CONSECUTIVE_GUN_GLITCHES) {
                    android.util.Log.i(TAG, "runCatchUp: gun fid silent $consecutiveGunGlitches runs in a row → treating as unsupported, cascade proceeds")
                    gunGlitch = false
                }
            }
            if (gunIsConnected || gunGlitch) {
                // Persist the evidence that a session is in progress around the
                // stored baseline. The gun physically blocks driving, so if the
                // odometer later moves before a successful catch-up, the charge
                // still happened — the pending flag keeps Step 5 from silently
                // discarding it.
                stateStore.setChargePending(true)
                android.util.Log.i(TAG, "runCatchUp: gun=$gun, deviceReadable=$chargingDeviceReadable → STILL_CHARGING (defer to live edge)")
                logJournal(now, "STILL_CHARGING gun=$gun" + if (gunGlitch) " glitch=$consecutiveGunGlitches" else "")
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.STILL_CHARGING)
            }

            // Step 5: charge-detection gate. Create a row only for a real charge:
            //   - socDelta >= MIN_SOC_DELTA_FOR_CHARGE (a <2% rise is noise:
            //     recalibration or parked regen, never a logged charge), AND
            //   - for the 2% tier ONLY: the odometer did not move since the
            //     anchor (a 2% rise after a drive could still be regen or a BMS
            //     recalculation). A rise >= SOC_DELTA_TRUSTED_CHARGE is beyond
            //     what regen-while-parked or recalibration can produce, so it
            //     is recorded regardless of the odometer — the anchor's mileage
            //     can be stale (it freezes while SOC is flat), and trusting it
            //     over physics lost real charges in the field (2026-06-11).
            // Every non-charge case rolls the baseline forward WITHOUT a row so the
            // anchor can never stick low and mis-attribute the next session. This
            // also subsumes the old socDelta <= 0 regression gate (lifetime_kwh
            // driving-counter phantom rows).
            val socDelta = currentSoc - prev.socPercent
            val odometerMoved = prev.mileageKm != null && battery?.lifetimeMileageKm != null &&
                (battery.lifetimeMileageKm - prev.mileageKm) > ODOMETER_MOVED_EPSILON_KM
            // A pending charge (gun was seen connected) overrides the odometer
            // gate: SOC can only rise via charging, and the gun blocks driving,
            // so socDelta >= threshold with pending evidence means a real session
            // that ended before the drive. The drive's consumption makes the SOC
            // estimate a slight under-count — accepted over losing the row.
            val chargePending = stateStore.loadChargePending()
            if (socDelta < MIN_SOC_DELTA_FOR_CHARGE ||
                (socDelta < SOC_DELTA_TRUSTED_CHARGE && odometerMoved && !chargePending)
            ) {
                android.util.Log.i(TAG, "runCatchUp: socDelta=$socDelta odometerMoved=$odometerMoved pending=$chargePending → NO_DELTA (roll forward, no row)")
                logJournal(
                    now,
                    "NO_DELTA soc=$currentSoc prev=${prev.socPercent} " +
                        "km=${battery?.lifetimeMileageKm} prevKm=${prev.mileageKm} " +
                        "odoMoved=$odometerMoved pending=$chargePending"
                )
                stateStore.save(
                    socPercent = currentSoc,
                    mileageKm = battery?.lifetimeMileageKm,
                    capacityKwh = charging?.chargingCapacityKwh,
                    ts = now
                )
                stateStore.setChargePending(false)
                _state.value = DetectorState.IDLE
                return CatchUpResult(CatchUpOutcome.NO_DELTA)
            }

            // Step 6: resolve kWh delta via cascade A → B → C with SOC sanity check
            val currentCap = charging?.chargingCapacityKwh?.toDouble()
            val prevCap = prev.capacityKwh?.toDouble()
            val nominalCapacity = settings.getBatteryCapacity()
            // #28: 1% of SOC on an aged pack holds nominal × SOH/100 kWh, so the
            // SOC→kWh conversion uses BMS-reported SOH. Sanity-clamped: a sentinel
            // or garbage reading falls back to nominal (factor 1.0).
            val sohFactor = battery?.sohPercent?.toDouble()
                ?.takeIf { it in SOH_SANITY_MIN..100.0 }?.div(100.0) ?: 1.0
            val effectiveCapacity = nominalCapacity * sohFactor
            val socEstimate = (socDelta / 100.0) * effectiveCapacity

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
                kwhChargedSoc = (socEnd - socStart) / 100.0 * effectiveCapacity,
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
            // cellDeltaV/batTempAvg = best-effort from the latest native sample; nulls if unavailable.
            if ((socEnd - socStart) >= MIN_SOC_DELTA_FOR_SNAPSHOT) {
                val capacity = batteryHealthRepo.calculateCapacity(delta, socStart, socEnd)
                val bmsSoh = battery?.sohPercent?.toDouble()
                val sample = lastSample ?: runCatching { parsReader.fetch() }.getOrNull()
                val cellDelta = if (sample?.maxCellVoltage != null && sample.minCellVoltage != null)
                    sample.maxCellVoltage - sample.minCellVoltage else null
                val batTemp = sample?.avgBatTemp?.toDouble()
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
            stateStore.setChargePending(false)

            android.util.Log.i(TAG, "runCatchUp: SESSION_CREATED id=$chargeId, delta=${"%.3f".format(delta)}, source=$detectionSource, type=$type, socStart=$socStart, socEnd=$socEnd")
            logJournal(
                now,
                "SESSION_CREATED kwh=${"%.2f".format(delta)} src=$detectionSource " +
                    "type=$type soc=$socStart->$socEnd"
            )
            _state.value = DetectorState.IDLE
            return CatchUpResult(CatchUpOutcome.SESSION_CREATED, chargeId, delta)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "runCatchUp: failed: ${e.message}", e)
            logJournal(now, "ERROR ${e.message}")
            _state.value = DetectorState.ERROR
            return CatchUpResult(CatchUpOutcome.AUTOSERVICE_UNAVAILABLE)
        }
    }

    /** Best-effort journal write — a prefs failure must never break detection. */
    private suspend fun logJournal(now: Long, payload: String) {
        runCatching { journal.append(payload, now) }
    }
}
