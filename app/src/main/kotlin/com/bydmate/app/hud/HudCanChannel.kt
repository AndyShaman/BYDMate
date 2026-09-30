package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient

/**
 * The instrument's own navigation fields on dev 1007, the channel the factory map adapter and
 * OpenBYD's sendSimpleGuidanceInfo / sendNextPathName fill: the maneuver kind into both icon fids
 * (the factory adapter writes every icon into both, and which one a given HUD listens to is not
 * known), the distance in metres, and the road name through setBuffer as UTF-16LE without a BOM.
 * Ported from the archived CAN probe (test/hud-can-probe). In this wave only the HUD check drives
 * it; the product's guidance path does not.
 *
 * Statuses stay raw: in this channel setInt answers 0 on success (the probe's finding), unlike the
 * 1 = real action of the comfort fids.
 */
class HudCanChannel(private val helper: HelperClient) {

    /** One [show]: each write's raw status and what the icon and distance read back. */
    data class Sent(
        val iconRc: Int?,
        val iconAheadRc: Int?,
        val distRc: Int?,
        val roadRc: Int?,
        val icon: HudArming.FidRead,
        val dist: HudArming.FidRead,
    ) {
        fun describe(): String =
            "icon st=${HudArming.rc(iconRc)}/${HudArming.rc(iconAheadRc)} dist st=${HudArming.rc(distRc)} " +
                "road st=${HudArming.rc(roadRc)} readback icon=$icon dist=$dist"
    }

    /** [turnKind] is the instrument's TURN_KIND (7 = left turn, see [TURN_LEFT]), not a gaode code. */
    suspend fun show(turnKind: Int, distanceM: Int, road: String): Sent {
        val iconRc = helper.writeStatus(DEV, FID_TURN_KIND, turnKind)
        val aheadRc = helper.writeStatus(DEV, FID_GUIDE_INFO_ROAD_AHEAD, turnKind)
        val distRc = helper.writeStatus(DEV, FID_TURN_DISTANCE_M, distanceM)
        val roadRc = helper.writeBufferStatus(DEV, FID_NEXT_PATHNAME, road.toByteArray(Charsets.UTF_16LE))
        val replies = helper.readBatch(listOf(BatchReadItem(5, DEV, FID_TURN_KIND), BatchReadItem(5, DEV, FID_TURN_DISTANCE_M)))
        val rb = List(2) { i -> replies?.getOrNull(i)?.let { (st, v) -> HudArming.FidRead(st, v) } ?: HudArming.FidRead(null) }
        return Sent(iconRc, aheadRc, distRc, roadRc, rb[0], rb[1])
    }

    /** Blanks what [show] drew: no icon, the SDK's invalid distance 0, a single space as the road
     *  name (the car rejects an empty buffer, user log 2026-09-29). Distance -1 was accepted by the
     *  car, yet a tester's glass kept the check's «333» whenever the status was raised. */
    suspend fun clear(): Sent = show(TURN_NONE, DISTANCE_NONE, " ")

    companion object {
        const val DEV = 1007
        const val FID_TURN_KIND = 1139806224              // INSTRUMENT_GUIDE_INFO_SIMPLE_SET
        const val FID_GUIDE_INFO_ROAD_AHEAD = 1139806256  // INSTRUMENT_GUIDE_INFO_AND_ROAD_AHEAD_DISTANCE_SET
        const val FID_TURN_DISTANCE_M = 1139806232        // INSTRUMENT_FRONT_CROSSING_DISTANCE_SET
        const val FID_NEXT_PATHNAME = 1140461576          // INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET
        // BYDAutoInstrumentDevice (firmware SDK): TURN_KIND_LEFT, TURN_KIND_BLANK, DISTANCE_INVALID.
        const val TURN_LEFT = 7
        const val TURN_NONE = 0
        const val DISTANCE_NONE = 0
    }
}
