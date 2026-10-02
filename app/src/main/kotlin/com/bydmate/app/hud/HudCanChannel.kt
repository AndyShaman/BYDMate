package com.bydmate.app.hud

import com.bydmate.app.data.vehicle.BatchReadItem
import com.bydmate.app.data.vehicle.HelperClient
import com.bydmate.app.data.vehicle.HudNaviReply
import com.bydmate.app.data.vehicle.HudSdkCall

/**
 * The instrument's own navigation fields on dev 1007, the channel the factory map adapter and
 * OpenBYD's sendSimpleGuidanceInfo / sendNextPathName fill: the maneuver kind into both icon fids
 * (the factory adapter writes every icon into both, and which one a given HUD listens to is not
 * known), the distance in metres, and the road name through setBuffer as UTF-16LE without a BOM.
 * Ported from the archived CAN probe (test/hud-can-probe). The HUD check draws with [show]; ways 2
 * and 3 of the product ([HudWayChannels]) write [guidance] and [road] on change, like OpenBYD; way 3
 * also its rest of route ([rest]) and the SDK call after each ([sdk]).
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

    /** OpenBYD's sendSimpleGuidanceInfo: [turnKind] into both icon fids, then the distance; each
     *  write's raw status, no readback. */
    suspend fun guidance(turnKind: Int, distanceM: Int): List<Int?> = listOf(
        helper.writeStatus(DEV, FID_TURN_KIND, turnKind),
        helper.writeStatus(DEV, FID_GUIDE_INFO_ROAD_AHEAD, turnKind),
        helper.writeStatus(DEV, FID_TURN_DISTANCE_M, distanceM),
    )

    /** OpenBYD's sendNextPathName: the road name as UTF-16LE; the raw status. */
    suspend fun road(name: String): Int? =
        helper.writeBufferStatus(DEV, FID_NEXT_PATHNAME, name.toByteArray(Charsets.UTF_16LE))

    /** Blanks what [show] drew: no icon, the SDK's invalid distance 0, a single space as the road
     *  name (the car rejects an empty buffer, user log 2026-09-29). Distance -1 was accepted by the
     *  car, yet a tester's glass kept the check's «333» whenever the status was raised. */
    suspend fun clear(): Sent = show(TURN_NONE, DISTANCE_NONE, " ")

    /** The rest of route as OpenBYD's sendRestRouteInfo takes it, with the arrival's minute of the hour. */
    data class Rest(val hours: Int, val minutes: Int, val mileageM: Long, val arriveMinute: Int)

    /** Way 3: the SDK call OpenBYD makes after its raw writes of the same fields. */
    suspend fun sdk(call: HudSdkCall): HudNaviReply? = helper.hudSdk(call)

    /** OpenBYD's sendRestRouteInfo raw part, in its order: mileage, hours, minutes, seconds 0, the
     *  arrival minute; each write's raw status. */
    suspend fun rest(r: Rest): List<Int?> =
        listOf(helper.writeStatus(DEV, FID_REST_MILEAGE_M, r.mileageM.toInt())) + writeRestTime(r.hours, r.minutes, r.arriveMinute)

    /** Blanks what [rest] wrote the way OpenBYD's turnOffNavi does: mileage -1, the others 0. A car
     *  that refuses the -1 gets 0 in the same clear, so the clear cannot stay owed for good. */
    suspend fun clearRest(): List<Int?> {
        val mileage = helper.writeStatus(DEV, FID_REST_MILEAGE_M, MILEAGE_NONE)
            .let { rc -> if (rc != null && rc < 0) helper.writeStatus(DEV, FID_REST_MILEAGE_M, 0) else rc }
        return listOf(mileage) + writeRestTime(0, 0, 0)
    }

    private suspend fun writeRestTime(hours: Int, minutes: Int, arriveMinute: Int): List<Int?> = listOf(
        helper.writeStatus(DEV, FID_REST_HOURS, hours),
        helper.writeStatus(DEV, FID_REST_MINUTES, minutes),
        helper.writeStatus(DEV, FID_REST_SECONDS, 0),
        helper.writeStatus(DEV, FID_ARRIVE_MINUTE, arriveMinute),
    )

    companion object {
        const val DEV = 1007
        const val FID_TURN_KIND = 1139806224              // INSTRUMENT_GUIDE_INFO_SIMPLE_SET
        const val FID_GUIDE_INFO_ROAD_AHEAD = 1139806256  // INSTRUMENT_GUIDE_INFO_AND_ROAD_AHEAD_DISTANCE_SET
        const val FID_TURN_DISTANCE_M = 1139806232        // INSTRUMENT_FRONT_CROSSING_DISTANCE_SET
        const val FID_NEXT_PATHNAME = 1140461576          // INSTRUMENT_TARGET_NEXT_PATHNAME_INFO_SET
        const val FID_REST_MILEAGE_M = 1139810344         // INSTRUMENT_NAVI_TRIP_INFO_MILEAGE_SET
        const val FID_REST_HOURS = 1139810320             // INSTRUMENT_NAVI_TRIP_INFO_HOUR_SET
        const val FID_REST_MINUTES = 1139810328           // INSTRUMENT_NAVI_TRIP_INFO_MINUTE_SET
        const val FID_REST_SECONDS = 1139810334           // INSTRUMENT_NAVI_TRIP_REMAINING_SECOND_SET
        const val FID_ARRIVE_MINUTE = 1139839008          // INSTRUMENT_EXPECTED_ARRIVE_MINUTE_SET
        /** OpenBYD's turnOffNavi blanks the rest-of-route mileage with -1. */
        const val MILEAGE_NONE = -1
        // BYDAutoInstrumentDevice (firmware SDK): TURN_KIND_LEFT, TURN_KIND_BLANK, DISTANCE_INVALID.
        const val TURN_LEFT = 7
        const val TURN_NONE = 0
        const val DISTANCE_NONE = 0
    }
}
