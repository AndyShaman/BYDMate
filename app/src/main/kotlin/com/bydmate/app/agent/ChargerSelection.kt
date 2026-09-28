package com.bydmate.app.agent

import com.bydmate.app.data.automation.PlaceGeometry
import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.domain.tracker.TravelCourse
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

internal data class LatLon(val lat: Double, val lon: Double)

/** find_chargers `where`: all around the car, or along its way. */
internal enum class ChargerWhere(val code: String) { AROUND("around"), AHEAD("ahead") }

/** Where a station lies against the direction of travel. */
internal enum class RelPosition(val code: String) { AHEAD("ahead"), BEHIND("behind"), ASIDE("aside") }

/** How an `ahead` search went: [DESTINATION] ranked by detour to the route's end, [CORRIDOR]
 *  along the course, [NOTHING_AHEAD] and [NO_COURSE] fell back to the nearest around. */
internal enum class AheadOutcome { NOT_ASKED, DESTINATION, CORRIDOR, NOTHING_AHEAD, NO_COURSE }

/** The car's connectors at a station: how many, how many free now, the fastest, the price range. */
internal data class ConnectorSummary(
    val total: Int,
    val free: Int,
    /** Connectors with no status or one outside the known set: neither free nor busy. */
    val unknown: Int,
    val maxPowerKw: Double?,
    val minPricePerKwh: Double?,
    val maxPricePerKwh: Double?,
    val currency: String?,
    /** FREE with any free, BUSY with none free, none unknown and any in use, DOWN with all out
     *  of order; null otherwise. */
    val status: StationStatus?,
)

internal data class ChargerPick(
    val station: ChargerStation,
    /** Straight line: the road distance is unknown. */
    val distanceKm: Double,
    val bearingDeg: Double,
    /** Null when the direction of travel is unknown. */
    val position: RelPosition?,
    /** Extra straight-line km to the route's end through this station; destination mode only. */
    val detourKm: Double?,
    /** Null when the source knows no connectors or the station lacks the car's type. */
    val summary: ConnectorSummary?,
)

internal data class ChargerChoice(
    val picks: List<ChargerPick>,
    val ahead: AheadOutcome,
    /** The source knows connectors, but no station in the radius has the car's type: the
     *  picks show the others. */
    val connectorMissing: Boolean,
)

/** Which stations the model hears about and in what order. Pure: no clock, no network. */
internal object ChargerSelection {

    const val MAX_PICKS = 8

    /** The `where` default: along the way when the course is known and the car really drives. */
    const val AHEAD_MIN_SPEED_KMH = 20

    // Corridor along the course: 3 km each side near the car, widening by 15 % of the distance
    // ahead (18 km ahead: 5.7 km, 100 km ahead: 18 km), so a station off a curving road stays in.
    private const val CORRIDOR_BASE_KM = 3.0
    private const val CORRIDOR_GROWTH = 0.15

    // Destination mode: a detour of up to 5 km, or 10 % of the straight distance left when longer.
    private const val DETOUR_MIN_KM = 5.0
    private const val DETOUR_SHARE = 0.10

    // A destination this close counts as reached; the car farther from it than when the route
    // was built (plus GPS slack) or heading more than 90° off it has left the route.
    private const val ARRIVED_KM = 1.0
    private const val APPROACH_SLACK_KM = 1.0
    private const val APPROACH_MAX_ANGLE = 90.0

    // `ahead` radius by speed: town, country road, highway.
    private const val TOWN_SPEED_KMH = 60
    private const val ROAD_SPEED_KMH = 90
    private const val TOWN_RADIUS_KM = 30
    private const val ROAD_RADIUS_KM = 60
    private const val HIGHWAY_RADIUS_KM = 100

    // Connector statuses on the BETA map; null or anything else is unknown.
    private const val FREE = "available"
    private val IN_USE = setOf("charging", "occupied")
    private val OUT_OF_ORDER = setOf("unavailable", "error")
    private val KNOWN_STATUSES = IN_USE + OUT_OF_ORDER + FREE

    fun defaultWhere(course: Double?, speedKmh: Int?): ChargerWhere =
        if (course != null && speedKmh != null && speedKmh > AHEAD_MIN_SPEED_KMH) ChargerWhere.AHEAD else ChargerWhere.AROUND

    fun aheadRadiusKm(speedKmh: Int?): Int = when {
        speedKmh == null || speedKmh <= TOWN_SPEED_KMH -> TOWN_RADIUS_KM
        speedKmh <= ROAD_SPEED_KMH -> ROAD_RADIUS_KM
        else -> HIGHWAY_RADIUS_KM
    }

    fun inCorridor(forwardKm: Double, lateralKm: Double): Boolean =
        forwardKm > 0.0 && lateralKm <= CORRIDOR_BASE_KM + CORRIDOR_GROWTH * forwardKm

    fun maxDetourKm(toDestinationKm: Double): Double = maxOf(DETOUR_MIN_KM, DETOUR_SHARE * toDestinationKm)

    /** Whether the route's end still is where the car is heading: not reached, not farther than
     *  when the route was built ([startKm], null = unknown), not behind the course. */
    fun stillApproaching(car: LatLon, destination: LatLon, startKm: Double?, course: Double?): Boolean {
        val km = km(car, destination)
        if (km < ARRIVED_KM) return false
        if (startKm != null && km > startKm + APPROACH_SLACK_KM) return false
        return course == null || TravelCourse.angleDiffDeg(course, bearing(car, destination)) <= APPROACH_MAX_ANGLE
    }

    /** The car's connectors at a station; null when it has none of them. A connector of unknown
     *  status may be free: the station is busy only when every such status is known. */
    fun summarize(connectors: List<StationConnector>, car: ChargeConnector): ConnectorSummary? {
        val own = connectors.filter { car.matches(it.standard) }
        if (own.isEmpty()) return null
        val free = own.count { it.status == FREE }
        val unknown = own.count { it.status !in KNOWN_STATUSES }
        val inUse = own.any { it.status in IN_USE }
        val down = own.all { it.status in OUT_OF_ORDER }
        val prices = own.mapNotNull { it.pricePerKwh }
        return ConnectorSummary(
            total = own.size,
            free = free,
            unknown = unknown,
            maxPowerKw = own.mapNotNull { it.powerKw }.maxOrNull(),
            minPricePerKwh = prices.minOrNull(),
            maxPricePerKwh = prices.maxOrNull(),
            currency = own.firstNotNullOfOrNull { it.currency },
            status = when {
                free > 0 -> StationStatus.FREE
                unknown == 0 && inUse -> StationStatus.BUSY
                down -> StationStatus.DOWN
                else -> null
            },
        )
    }

    /**
     * Up to [MAX_PICKS] stations for the model. Stations without the car's connector are left out
     * while any other has it. `ahead` with a [destination] keeps small detours to it, nearest
     * first; without one, a corridor along the [course], by distance along it; with neither, or
     * nothing found that way, the nearest around.
     */
    @Suppress("LongParameterList") // the car, its stations and the four inputs of the search
    fun choose(
        origin: LatLon,
        stations: List<ChargerStation>,
        connector: ChargeConnector,
        where: ChargerWhere,
        course: Double?,
        destination: LatLon?,
    ): ChargerChoice {
        val known = stations.any { it.connectors != null }
        val typed = stations.filter { s -> s.connectors.orEmpty().any { connector.matches(it.standard) } }
        val pool = if (known && typed.isNotEmpty()) typed else stations
        // Position against the course; toward the destination when the course is not known yet.
        val reference = course ?: destination?.let { bearing(origin, it) }
        val measured = pool.map { measure(origin, it, connector, reference) }
        val (picks, outcome) = order(origin, measured, where, course, destination)
        return ChargerChoice(picks.take(MAX_PICKS), outcome, connectorMissing = known && typed.isEmpty() && stations.isNotEmpty())
    }

    private fun order(
        origin: LatLon,
        measured: List<ChargerPick>,
        where: ChargerWhere,
        course: Double?,
        destination: LatLon?,
    ): Pair<List<ChargerPick>, AheadOutcome> {
        val around = measured.sortedBy { it.distanceKm }
        if (where == ChargerWhere.AROUND) return around to AheadOutcome.NOT_ASKED
        val ahead = when {
            destination != null -> byDetour(origin, measured, destination) to AheadOutcome.DESTINATION
            course != null -> alongCourse(measured, course) to AheadOutcome.CORRIDOR
            else -> return around to AheadOutcome.NO_COURSE
        }
        return if (ahead.first.isEmpty()) around to AheadOutcome.NOTHING_AHEAD else ahead
    }

    /** Small detours on the way to [destination]. A station behind (against the course, or
     *  against the way to the destination when the course is unknown) is no stop on the way,
     *  however small its detour. */
    private fun byDetour(origin: LatLon, measured: List<ChargerPick>, destination: LatLon): List<ChargerPick> {
        val direct = km(origin, destination)
        val limit = maxDetourKm(direct)
        return measured
            .filter { it.position != RelPosition.BEHIND }
            .map { p -> p.copy(detourKm = p.distanceKm + km(LatLon(p.station.lat, p.station.lon), destination) - direct) }
            .filter { it.detourKm!! <= limit }
            .sortedBy { it.distanceKm }
    }

    private fun alongCourse(measured: List<ChargerPick>, course: Double): List<ChargerPick> =
        measured
            .map { p -> p to Math.toRadians(p.bearingDeg - course) }
            .filter { (p, angle) -> inCorridor(p.distanceKm * cos(angle), abs(p.distanceKm * sin(angle))) }
            .sortedBy { (p, angle) -> p.distanceKm * cos(angle) }
            .map { it.first }
}

private const val AHEAD_MAX_ANGLE = 45.0
private const val BEHIND_MIN_ANGLE = 135.0

private fun measure(origin: LatLon, s: ChargerStation, connector: ChargeConnector, reference: Double?): ChargerPick {
    val bearing = bearing(origin, LatLon(s.lat, s.lon))
    return ChargerPick(
        station = s,
        distanceKm = km(origin, LatLon(s.lat, s.lon)),
        bearingDeg = bearing,
        position = reference?.let { position(TravelCourse.angleDiffDeg(it, bearing)) },
        detourKm = null,
        summary = s.connectors?.let { ChargerSelection.summarize(it, connector) },
    )
}

private fun position(offCourseDeg: Double): RelPosition = when {
    offCourseDeg <= AHEAD_MAX_ANGLE -> RelPosition.AHEAD
    offCourseDeg >= BEHIND_MIN_ANGLE -> RelPosition.BEHIND
    else -> RelPosition.ASIDE
}

private fun km(a: LatLon, b: LatLon): Double = PlaceGeometry.distanceMeters(a.lat, a.lon, b.lat, b.lon) / 1000.0

private fun bearing(a: LatLon, b: LatLon): Double = TravelCourse.bearingDeg(a.lat, a.lon, b.lat, b.lon)
