package com.bydmate.app.data.nativestack

import com.bydmate.app.data.autoservice.SentinelDecoder
import java.time.Instant
import java.time.temporal.ChronoUnit

/** What drives the car. UNKNOWN behaves as EV everywhere. */
enum class VehicleKind { EV, HYBRID, UNKNOWN }

/**
 * One-read rule for the car type, from three raw dev=1014 tx=5 words (null = the read failed):
 * the HEV odometer, the EV odometer and the fuel percent. A pure EV answers the 20-bit sentinel
 * on the odometer AND 255 on the fuel level; one sentinel next to a live second value is not
 * an EV. The EV odometer is evidence only, and engine rpm is not part of the rule (a Leopard 8
 * hybrid read 8191 there).
 */
object VehicleKindDetector {

    const val DEV = 1014
    const val MILEAGE_HEV_FID = 1246773264
    const val MILEAGE_EV_FID = 1246773284
    const val FUEL_PERCENT_FID = 1246785600

    private const val MILEAGE_ABSENT = 1048575
    private const val FUEL_ABSENT = 255
    private val FUEL_RANGE = 0..100

    fun classify(hev: Int?, ev: Int?, fuel: Int?): VehicleKind = when {
        hev == null || ev == null || fuel == null -> VehicleKind.UNKNOWN
        hev == MILEAGE_ABSENT && fuel == FUEL_ABSENT -> VehicleKind.EV
        hev != MILEAGE_ABSENT && fuel in FUEL_RANGE -> VehicleKind.HYBRID
        else -> VehicleKind.UNKNOWN
    }

    /**
     * One batch reply word as [classify] takes it: null when the read failed (status not 0) or
     * answered an error sentinel (65535 link error, -10011, -10013). The 20-bit 1048575 stays,
     * it is the EV marker.
     */
    fun word(status: Int, value: Int): Int? = when {
        status != 0 -> null
        value == MILEAGE_ABSENT -> value
        else -> SentinelDecoder.decodeInt(value)
    }

    /** Cached evidence string: "hev=…;ev=…;fuel=…", a failed read as "?". */
    fun evidence(hev: Int?, ev: Int?, fuel: Int?): String =
        "hev=${hev ?: "?"};ev=${ev ?: "?"};fuel=${fuel ?: "?"}"

    /**
     * The diagnostic dump line. A known [kind] prints its cached evidence and the confirmation
     * time in UTC; UNKNOWN prints the last read of this run, or "(none)" before the first one.
     */
    fun dumpLine(kind: VehicleKind, evidence: String?, confirmedAtMs: Long?, last: String?): String =
        if (kind == VehicleKind.UNKNOWN) {
            "vehicle_kind: UNKNOWN last=${last ?: "(none)"}"
        } else {
            val at = confirmedAtMs?.let { Instant.ofEpochMilli(it).truncatedTo(ChronoUnit.SECONDS).toString() } ?: "?"
            "vehicle_kind: $kind ${evidence?.replace(';', ' ') ?: "?"} confirmed=$at"
        }
}

/**
 * Confirms a kind after two identical EV or HYBRID results at least [minGapMs] apart. A
 * different known result restarts the count from itself; UNKNOWN neither counts nor resets.
 * [offer] returns the kind on the confirming result and null otherwise; the next offer after a
 * confirmation starts a fresh count.
 */
class VehicleKindConfirmation(private val minGapMs: Long = MIN_GAP_MS) {

    private var pending: VehicleKind? = null
    private var pendingSinceMs = 0L

    fun offer(kind: VehicleKind, nowMs: Long): VehicleKind? {
        if (kind == VehicleKind.UNKNOWN) return null
        if (kind != pending) {
            pending = kind
            pendingSinceMs = nowMs
            return null
        }
        if (nowMs - pendingSinceMs < minGapMs) return null
        pending = null
        return kind
    }

    fun reset() {
        pending = null
    }

    companion object {
        const val MIN_GAP_MS = 10_000L
    }
}
