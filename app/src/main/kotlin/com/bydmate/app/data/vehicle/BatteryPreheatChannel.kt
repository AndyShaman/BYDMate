package com.bydmate.app.data.vehicle

import android.util.Log
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.data.nativestack.FidAddresses
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import com.bydmate.app.split.readSystemProperty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Raw value of a fid, sentinels kept. Null means the read did not complete. */
fun interface PreheatReader {
    suspend fun read(dev: Int, fid: Int): Int?
}

/**
 * The verdict row of a command for the write audit log: [dev] is 0 when nothing was written,
 * [state] the last status read, [verdict] "verdict=<label>" (also on success).
 */
data class PreheatAuditRow(
    val action: String, val dev: Int, val fid: Int, val value: Int, val state: Int?, val ok: Boolean, val verdict: String,
)

fun interface PreheatAudit {
    suspend fun write(row: PreheatAuditRow)
}

/**
 * Battery warm-up on/off (#311), the write of the stock «Battery Warm-up» toggle (CarSetting
 * ChargingPreheat: 1 = on, 2 = off). The channel sends only what that toggle can send, under the
 * same conditions, and nothing is written unless every check before it passes:
 *  - HEV odometer 1048575 (a pure EV)        → the checks below
 *  - HEV odometer a real number, also 0      → NOT_EQUIPPED (a hybrid: the stock toggle is hidden)
 *  - HEV odometer unreadable                 → UNREACHABLE (null, 65535, -10011/-10013)
 *  - config read failed or a read error      → UNREACHABLE (1048575, -10011/-10013)
 *  - config not 0                            → NOT_EQUIPPED (65535 on a firmware without the fid)
 *  - charge gun state unreadable             → UNREACHABLE (a cable may be connected)
 *  - charge gun state 2, 3 or 4              → GUN_CONNECTED (the stock toggle is disabled)
 *  - on, the status already reads heating    → OK
 *  - on, permit unreadable                   → UNREACHABLE
 *  - on, permit not 0                        → NOT_NEEDED (stock: «no preheating is required»)
 * After the write:
 *  - the daemon did not answer               → UNREACHABLE, not read back, counted as written
 *  - the car denied it (-10011)              → DENIED, not read back
 *  - on: status 1 → OK, still 2 (finished) at the last read → NOT_NEEDED
 *  - off: any status but 1                   → OK
 *  - the status never follows                → NO_EFFECT, an unreadable status → UNCONFIRMED
 * Not tried on a car: the permit refuses the warm-up on a warm battery, so every command logs
 * one line with all the reads and the battery temperature next to the verdict.
 * Commands run one at a time under [mutex], so the next one reads the real status.
 */
class BatteryPreheatChannel(
    private val writer: SeatWriter,
    private val reader: PreheatReader,
    private val audit: PreheatAudit,
    private val systemProperty: (String) -> String? = ::readSystemProperty,
) {
    enum class Result { OK, NOT_EQUIPPED, GUN_CONNECTED, NOT_NEEDED, DENIED, NO_EFFECT, UNREACHABLE, UNCONFIRMED }

    /**
     * [verdict] ends the channel's log line; [written] is false only when it is certain that nothing
     * reached the car (a write with an unknown result counts as written);
     * [state] is the last status read (null when the read failed or never happened).
     */
    data class Outcome(val result: Result, val verdict: String, val written: Boolean, val state: Int?) {
        /** The outcome as the Result dispatch returns for [action]. */
        fun toResult(action: String): kotlin.Result<Unit> = when (result) {
            Result.OK -> kotlin.Result.success(Unit)
            Result.NOT_EQUIPPED -> kotlin.Result.failure(VehicleWriteError.NotEquipped(action))
            Result.GUN_CONNECTED -> kotlin.Result.failure(VehicleWriteError.StateBlocked(action, "charge gun connected"))
            Result.NOT_NEEDED -> kotlin.Result.failure(VehicleWriteError.NotNeeded(action, verdict))
            Result.DENIED -> kotlin.Result.failure(VehicleWriteError.Unsupported(action, verdict))
            Result.NO_EFFECT -> kotlin.Result.failure(VehicleWriteError.ReadbackMismatch(action, verdict))
            Result.UNREACHABLE -> kotlin.Result.failure(VehicleWriteError.HelperUnreachable(action, verdict))
            Result.UNCONFIRMED ->
                kotlin.Result.failure(VehicleWriteError.HelperUnreachable(action, "result not confirmed ($verdict)"))
        }
    }

    private val mutex = Mutex()

    /**
     * One of [ACTIONS] as the Result dispatch returns: runs the command and writes its verdict
     * row. A write that may have reached the car is followed by the late trace reads on [scope].
     */
    suspend fun dispatch(action: String, scope: CoroutineScope): kotlin.Result<Unit> {
        val on = action == ACTION_ON
        val outcome = actuate(on)
        audit.write(
            PreheatAuditRow(
                action, if (outcome.written) WriteAllowlist.BATTERY_PREHEAT_DEV else 0,
                WriteAllowlist.BATTERY_PREHEAT_SET_FID, if (on) SET_ON else SET_OFF, outcome.state,
                outcome.result == Result.OK, "verdict=${outcome.verdict}",
            )
        )
        if (outcome.written) traceLater(scope, on, Trace.cause())
        return outcome.toResult(action)
    }

    private suspend fun actuate(on: Boolean): Outcome = mutex.withLock {
        val line = StringBuilder("Preheat: want=${if (on) "on" else "off"}")
        val outcome = notAnEv(line) ?: refusal(on, line) ?: write(on, line)
        Log.i(TAG, "$line -> ${outcome.verdict}" + if (outcome.written) "" else ", nothing written")
        outcome
    }

    /**
     * What the warm-up does after a write that may have reached the car, as trace lines at [LATE_READS_MS]: status,
     * permit, heating power and the battery temperature, each the value or `err`. The heater's
     * start, power and end are not known from a live car yet. Diagnostics only: fire-and-forget
     * on [scope], no verdict changes.
     */
    private fun traceLater(scope: CoroutineScope, on: Boolean, cause: Long?) {
        scope.launch {
            var waited = 0L
            for (atMs in LATE_READS_MS) {
                delay(atMs - waited)
                waited = atMs
                Trace.event(
                    TraceArea.CAR, "preheat_late", "want" to (if (on) "on" else "off"), "after_s" to (atMs / 1000).toInt(),
                    "status" to render(readCharging(WriteAllowlist.BATTERY_PREHEAT_STATUS_FID)),
                    "permit" to render(readCharging(WriteAllowlist.BATTERY_PREHEAT_PERMIT_FID)),
                    "power" to render(readCharging(WriteAllowlist.BATTERY_HEATING_POWER_FID)),
                    "tmin" to temperature("minBatTemp"), "tmax" to temperature("maxBatTemp"), by = cause,
                )
            }
        }
    }

    /** The checks of the stock toggle, in its order; the refusal, or null when the write may go. */
    private suspend fun refusal(on: Boolean, line: StringBuilder): Outcome? {
        val config = readCharging(WriteAllowlist.BATTERY_PREHEAT_CONFIG_FID)
        line.append(" config=${render(config)}")
        if (config != CONFIG_FITTED) {
            // 65535 (no CAN link) is how a firmware without the fid answers; the other sentinels are read errors.
            val absent = config != null &&
                (config == SentinelDecoder.FEATURE_LINK_ERROR || SentinelDecoder.decodeInt(config) != null)
            return if (absent) Outcome(Result.NOT_EQUIPPED, "no preheat", false, null)
                else Outcome(Result.UNREACHABLE, "config unreadable", false, null)
        }
        val gun = readCharging(WriteAllowlist.CHARGE_GUN_STATE_FID)
        val before = readCharging(WriteAllowlist.BATTERY_PREHEAT_STATUS_FID)
        line.append(" gun=${render(gun)} before=${render(before)}")
        line.append(" tmin=${temperature("minBatTemp")} tmax=${temperature("maxBatTemp")}")
        // A cable may be connected behind a failed read or a sentinel, so only a real value lets the write go.
        if (gun == null || SentinelDecoder.decodeInt(gun) == null) {
            return Outcome(Result.UNREACHABLE, "gun unreadable", false, before)
        }
        if (gun in GUN_CONNECTED) return Outcome(Result.GUN_CONNECTED, "gun connected", false, before)
        if (!on) return null
        if (before == STATUS_HEATING) return Outcome(Result.OK, "already heating", false, before)
        val permit = readCharging(WriteAllowlist.BATTERY_PREHEAT_PERMIT_FID)
        line.append(" permit=${render(permit)}")
        return when {
            permit == null || SentinelDecoder.decodeInt(permit) == null ->
                Outcome(Result.UNREACHABLE, "permit unreadable", false, before)
            permit != PERMIT_GRANTED -> Outcome(Result.NOT_NEEDED, "not needed permit=$permit", false, before)
            else -> null
        }
    }

    /**
     * The check before all of [refusal]: the stock toggle exists on a pure EV only (isEVCarType).
     * A hybrid answers a flat 0 for fids it does not fill, so config and permit cannot refuse it;
     * its HEV odometer can. Fuel percent and the property stock reads ("1" EV, "2" hybrid,
     * "0" fuel) are logged only.
     */
    private suspend fun notAnEv(line: StringBuilder): Outcome? {
        val hev = read(WriteAllowlist.HEV_MILEAGE_DEV, WriteAllowlist.HEV_MILEAGE_FID)
        val fuel = read(WriteAllowlist.HEV_MILEAGE_DEV, WriteAllowlist.FUEL_PERCENT_FID)
        line.append(" hev=${render(hev)} fuel=${render(fuel)} type=${systemProperty(ENERGY_TYPE_PROP) ?: "err"}")
        return when {
            hev == HEV_MILEAGE_ABSENT -> null
            hev != null && SentinelDecoder.decodeInt(hev) != null -> Outcome(Result.NOT_EQUIPPED, "not an EV", false, null)
            else -> Outcome(Result.UNREACHABLE, "car type unreadable", false, null)
        }
    }

    /** The write and the status readback. */
    private suspend fun write(on: Boolean, line: StringBuilder): Outcome {
        val status = writer.write(if (on) ACTION_ON else ACTION_OFF, if (on) SET_ON else SET_OFF)
        line.append(" write=$status")
        // No answer is not a refusal: the car may have taken the write and started heating.
        if (status == WriteOutcome.TRANSIENT) return Outcome(Result.UNREACHABLE, "unreachable", true, null)
        if (status == WriteOutcome.PERMANENT_DENIED) return Outcome(Result.DENIED, "write denied", false, null)

        val after = mutableListOf<Int?>()
        var settled: Outcome? = null
        while (settled == null && after.size < READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val raw = readCharging(WriteAllowlist.BATTERY_PREHEAT_STATUS_FID)
            after += raw
            settled = settledBy(on, raw?.let(SentinelDecoder::decodeInt))
        }
        line.append(" after=[${after.joinToString(",") { render(it) }}]")
        if (settled != null) return settled
        val last = after.last()
        val value = last?.let(SentinelDecoder::decodeInt)
            ?: return Outcome(Result.UNCONFIRMED, "unconfirmed read=${render(last)}", true, last)
        if (on && value == STATUS_FINISHED) return Outcome(Result.NOT_NEEDED, "finished", true, value)
        return Outcome(Result.NO_EFFECT, "no effect read=$value", true, value)
    }

    /**
     * The verdict a status read after the write gives, or null while the status has not followed.
     * Finished does not settle `on`: it can be left over from an earlier warm-up and turn to heating
     * a moment later.
     */
    private fun settledBy(on: Boolean, status: Int?): Outcome? = when {
        on && status == STATUS_HEATING -> Outcome(Result.OK, "OK", true, status)
        !on && status != null && status != STATUS_HEATING -> Outcome(Result.OK, "OK", true, status)
        else -> null
    }

    private suspend fun readCharging(fid: Int): Int? = read(WriteAllowlist.BATTERY_PREHEAT_DEV, fid)

    /** Battery temperature in °C of the FidMap field [name] (raw value minus 40), or `err`. */
    private suspend fun temperature(name: String): String {
        val address = FidAddresses.of(name)
        val raw = read(address.device, address.fid)?.let(SentinelDecoder::decodeInt) ?: return "err"
        return (raw - TEMPERATURE_OFFSET).toString()
    }

    /** A throwing read is a failed read, not a verdict. */
    private suspend fun read(dev: Int, fid: Int): Int? = runCatching { reader.read(dev, fid) }
        .onFailure {
            if (it is CancellationException) throw it
            Log.w(TAG, "Preheat: read dev=$dev fid=$fid failed: ${it.message}")
        }
        .getOrNull()

    companion object {
        private const val ACTION_ON = "battery_preheat_on"
        private const val ACTION_OFF = "battery_preheat_off"
        /** The allowlist actions this channel carries out. */
        val ACTIONS = setOf(ACTION_ON, ACTION_OFF)

        /** The car type as the stock settings app reads it; logged, never a gate. */
        const val ENERGY_TYPE_PROP = "persist.sys.energytype"

        private const val TAG = "BatteryPreheatChannel"
        /** What a pure EV answers for the HEV odometer (20-bit "not initialized"). */
        private const val HEV_MILEAGE_ABSENT = 0x000FFFFF
        private const val SET_ON = 1
        private const val SET_OFF = 2
        private const val CONFIG_FITTED = 0
        private const val PERMIT_GRANTED = 0
        private const val STATUS_HEATING = 1
        private const val STATUS_FINISHED = 2
        /** The states in which the stock toggle is disabled (ChargingPreheat.isEnabled). */
        private val GUN_CONNECTED = setOf(2, 3, 4)
        private const val TEMPERATURE_OFFSET = 40
        /** How fast the car starts the heater is not known yet; the late trace reads show it. */
        private const val READBACK_ATTEMPTS = 4
        private const val READBACK_DELAY_MS = 500L
        /** When a written command is read again for the trace. */
        private val LATE_READS_MS = listOf(5_000L, 30_000L, 120_000L)
    }
}

private fun render(value: Int?) = value?.toString() ?: "err"
