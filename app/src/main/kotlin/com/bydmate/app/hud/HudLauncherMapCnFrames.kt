package com.bydmate.app.hud

import java.io.ByteArrayOutputStream
import kotlin.random.Random

/**
 * OpenBYD's default SOME/IP HUD family, LAUNCHER_MAP_CN, ported from OpenBYD 2.5
 * (`.research/decompiled/openbyd-2.5/.../strategies/LauncherMapCnStrategy.java`): the same topics,
 * fields, constants and order. Every event is a hand-rolled protobuf wrapped as field 1
 * (SomeIpHudHelper.wrapInField1), like [HudProtobufBuilder]'s frame, but carries no road name.
 *
 * Not ported: the lane events (topic [TOPIC_LANES]); OpenBYD sends them only when it has lane
 * data, and we have none, so they are left out exactly as OpenBYD leaves them out without it.
 * The topic still counts for the service set. Names of topics the decompile does not name are ours.
 */
object HudLauncherMapCnFrames {

    const val TOPIC_GUIDE_STATE = 0x4000700078001L
    const val TOPIC_GUIDE_REMAIN = 0x4000700078003L
    const val TOPIC_MANEUVER = 0x482028202800bL
    const val TOPIC_LANES = 0x482028202800cL
    const val TOPIC_ROUTING = 0x4000c000c8001L
    const val TOPIC_ROUTING_STATUS = 0x4000c000c8003L       // ROUTING_STATUS_TOPIC
    const val TOPIC_MANEUVER_STATUS_1 = 0x4000d000d8001L    // MANEUVER_STATUS_1_TOPIC
    const val TOPIC_MANEUVER_STATUS_2 = 0x4000d000d8002L    // MANEUVER_STATUS_2_TOPIC
    const val TOPIC_MANEUVER_STATUS_5 = 0x4000d000d8005L
    const val TOPIC_ROUTE_METADATA = 0x4000e000e8001L       // ROUTE_METADATA_TOPIC
    const val TOPIC_ROUTE_POSE = 0x4001700178003L

    /** The constructor's topic list, in its order. */
    val TOPICS = listOf(
        TOPIC_GUIDE_STATE, TOPIC_GUIDE_REMAIN, TOPIC_MANEUVER, TOPIC_LANES, TOPIC_ROUTING, TOPIC_ROUTING_STATUS,
        TOPIC_MANEUVER_STATUS_1, TOPIC_MANEUVER_STATUS_2, TOPIC_MANEUVER_STATUS_5, TOPIC_ROUTE_METADATA, TOPIC_ROUTE_POSE,
    )

    /** getServiceIds(): the gateway service of every topic, duplicates dropped, first seen first. */
    val SERVICE_IDS: List<Long> = TOPICS.map(HudSomeIpBridge::serviceIdFor).distinct()

    /** SomeIpHudHelper's keep-alive: the whole update goes out every 200 ms. */
    const val PERIOD_MS = 200L

    /** One fireEvent: its topic and payload. */
    class Event(val topic: Long, val payload: ByteArray)

    /** LocationHelper's cached position; [DEFAULT] is its value before any location is known. */
    data class Position(val lat: Double, val lon: Double) {
        companion object {
            val DEFAULT = Position(lat = 39.9042, lon = 116.4074)
        }
    }

    /** The per-session route id updateNavigation draws when it has none: 1000000000..9999999999. */
    fun newRouteId(random: Random): Long = random.nextLong(ROUTE_ID_MIN, ROUTE_ID_MAX + 1)

    /** SomeIpHudHelper.mapManeuverToMainAction; [iconId] is OpenBYD's icon id (gaode, 1 = left). */
    fun mainAction(iconId: Int): Int = when (iconId) {
        1 -> 2
        2 -> 3
        3, 4 -> 4
        5, 6 -> 5
        7 -> 6
        8 -> 7
        9 -> 8
        10 -> 9
        11, 12 -> 1
        else -> 0
    }

    /**
     * updateNavigation without lane data: [counter] is SomeIpHudHelper's (+1 per update, 0..255),
     * [nowMs] its System.currentTimeMillis(); both stamps go out as microseconds in a double.
     */
    @Suppress("LongParameterList") // the strategy's inputs, one to one
    fun update(
        iconId: Int,
        distanceM: Int,
        remainDistanceM: Int,
        remainTimeS: Int,
        position: Position,
        routeId: Long,
        counter: Int,
        nowMs: Long,
    ): List<Event> {
        val micros = nowMs * MICROS_PER_MS
        return listOf(
            Event(TOPIC_GUIDE_STATE, message { varint(4, 101L) }),
            Event(TOPIC_MANEUVER, message {
                varint(1, iconId.toLong())
                varint(2, mainAction(iconId).toLong())
                varint(3, 0L)
                varint(4, distanceM.toLong())
            }),
            Event(TOPIC_GUIDE_REMAIN, message {
                varint(17, remainDistanceM.toLong())
                varint(18, remainTimeS.toLong())
                double(11, position.lon)
                double(12, position.lat)
            }),
            Event(TOPIC_ROUTING, message { varint(1, 2641158014L) }),
            Event(TOPIC_ROUTING_STATUS, message { varint(1, 1729875789L) }),
            Event(TOPIC_MANEUVER_STATUS_1, message {
                varint(1, MANEUVER_STATUS_1_ID)
                varint(5, 1L)
                double(12, 5.0)
                double(13, 2.2)
            }),
            Event(TOPIC_MANEUVER_STATUS_2, message { varint(1, 3817498742L) }),
            Event(TOPIC_MANEUVER_STATUS_5, message {
                varint(1, MANEUVER_STATUS_5_ID)
                varint(3, 1L)
            }),
            Event(TOPIC_ROUTE_METADATA, message {
                varint(1, routeId)
                varint(3, 1L)
                double(4, micros)
            }),
            Event(TOPIC_ROUTE_POSE, message {
                bytes(1, fields {
                    varint(1, routeId)
                    varint(2, counter.toLong())
                    double(3, micros)
                })
                bytes(3, fields { POSE.forEachIndexed { i, v -> double(i + 1, v) } })
                varint(7, 7L)
            }),
        )
    }

    /** stopNavigation without lane data: the "off" versions of three update events. */
    fun stop(routeId: Long, nowMs: Long): List<Event> = listOf(
        Event(TOPIC_MANEUVER_STATUS_1, message {
            varint(1, MANEUVER_STATUS_1_ID)
            varint(5, 0L)
            double(12, 0.0)
            double(13, 0.0)
        }),
        Event(TOPIC_MANEUVER_STATUS_5, message {
            varint(1, MANEUVER_STATUS_5_ID)
            varint(3, 0L)
        }),
        Event(TOPIC_ROUTE_METADATA, message {
            varint(1, routeId)
            varint(3, 0L)
            double(4, nowMs * MICROS_PER_MS)
        }),
    )

    private const val ROUTE_ID_MIN = 1_000_000_000L
    private const val ROUTE_ID_MAX = 9_999_999_999L
    private const val MICROS_PER_MS = 1000.0
    private const val MANEUVER_STATUS_1_ID = 3592003832L
    private const val MANEUVER_STATUS_5_ID = 4073768758L

    /** The six constant doubles updateNavigation nests as field 3 of the route pose event. */
    private val POSE = doubleArrayOf(
        1161.2184496889508, 971.1426529964466, -19.15885124372106,
        0.0014609250661213498, -1.5712258405572703, 0.0037437084083233626,
    )

    private fun fields(block: Writer.() -> Unit): ByteArray = Writer().apply(block).toByteArray()

    /** An event payload: the fields [block] writes, wrapped as field 1 (wrapInField1). */
    private fun message(block: Writer.() -> Unit): ByteArray = fields { bytes(1, fields(block)) }

    /** SomeIpHudHelper's field writers. */
    private class Writer {
        private val out = ByteArrayOutputStream()

        fun varint(fieldNo: Int, value: Long) {
            raw((fieldNo.toLong() shl 3) or 0L)
            raw(value)
        }

        fun double(fieldNo: Int, value: Double) {
            raw((fieldNo.toLong() shl 3) or 1L)
            val bits = value.toBits()
            repeat(8) { i -> out.write(((bits ushr (8 * i)) and 0xFF).toInt()) }
        }

        fun bytes(fieldNo: Int, value: ByteArray) {
            raw((fieldNo.toLong() shl 3) or 2L)
            raw(value.size.toLong())
            out.write(value)
        }

        fun toByteArray(): ByteArray = out.toByteArray()

        private fun raw(value: Long) {
            var v = value
            while (true) {
                if (v and 0x7F.inv().toLong() == 0L) {
                    out.write(v.toInt())
                    return
                }
                out.write(((v and 0x7F) or 0x80).toInt())
                v = v ushr 7
            }
        }
    }
}
