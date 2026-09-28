package com.bydmate.app.agent

/** Where a station list came from; [code] is what the model and the log see. */
enum class ChargerSource(val code: String) {
    /** beta.by/map, the BETA association map: connectors, their occupancy, power and price. */
    BETA("beta.by"),

    /** Malanka's public map gateway: stations, a whole-station status for Evika and Battery Fly. */
    GATEWAY("malanka_gateway"),

    /** OpenStreetMap via Overpass: stations only. */
    OSM("openstreetmap"),
}

/** Occupancy of a whole station or of the car's connectors at it. */
enum class StationStatus(val code: String) { FREE("free"), BUSY("busy"), DOWN("unavailable") }

/** One connector as the BETA map lists it. [standard] is the map's code (GBT_DC, CCS2, Type2...),
 *  [status] its raw occupancy (available, charging, occupied, unavailable, error). */
data class StationConnector(
    val standard: String,
    val powerKw: Double?,
    val status: String?,
    val pricePerKwh: Double?,
    val currency: String?,
)

/** A charging station from any source. [connectors] is null when the source knows none (gateway,
 *  OpenStreetMap), [status] is the whole-station occupancy of a source without connectors,
 *  [statusAtMs] when the source last refreshed the occupancy (epoch ms). */
data class ChargerStation(
    val name: String,
    val address: String?,
    val operator: String?,
    val lat: Double,
    val lon: Double,
    val connectors: List<StationConnector>? = null,
    val status: StationStatus? = null,
    val statusAtMs: Long? = null,
)
