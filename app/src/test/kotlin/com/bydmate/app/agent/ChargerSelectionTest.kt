package com.bydmate.app.agent

import com.bydmate.app.data.charging.ChargeConnector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class ChargerSelectionTest {

    // A car near Minsk; stations placed by kilometres north/east of it on a local plane.
    private val car = LatLon(53.9, 27.56)
    private val kmPerDegLat = 111.195
    private val kmPerDegLon = kmPerDegLat * cos(Math.toRadians(car.lat))

    private fun point(northKm: Double, eastKm: Double) =
        LatLon(car.lat + northKm / kmPerDegLat, car.lon + eastKm / kmPerDegLon)

    private fun station(
        name: String,
        northKm: Double = 1.0,
        eastKm: Double = 0.0,
        connectors: List<StationConnector>? = listOf(gbt("available")),
    ): ChargerStation {
        val p = point(northKm, eastKm)
        return ChargerStation(name, address = null, operator = null, lat = p.lat, lon = p.lon, connectors = connectors)
    }

    private fun gbt(status: String?, powerKw: Double? = 60.0, price: Double? = 0.73, standard: String = "GBT_DC") =
        StationConnector(standard, powerKw, status, price, "BYN")

    private fun other(standard: String, status: String = "available") =
        StationConnector(standard, 50.0, status, 0.73, "BYN")

    private fun choose(
        stations: List<ChargerStation>,
        where: ChargerWhere = ChargerWhere.AROUND,
        course: Double? = null,
        destination: LatLon? = null,
        connector: ChargeConnector = ChargeConnector.GBT,
    ) = ChargerSelection.choose(car, stations, connector, where, course, destination)

    // --- connector filter ---

    @Test fun gbt_counts_both_gbt_dc_and_gbt_ac() {
        val s = station("Две розетки", connectors = listOf(
            gbt("available", standard = "GBT_DC"), gbt("charging", powerKw = 7.0, standard = "GBT_AC"), other("CCS2")))
        val summary = ChargerSelection.summarize(s.connectors!!, ChargeConnector.GBT)!!
        assertEquals(2, summary.total)
        assertEquals(1, summary.free)
        assertEquals(StationStatus.FREE, summary.status)
        assertEquals(60.0, summary.maxPowerKw!!, 0.0)
    }

    @Test fun free_ccs2_with_every_gbt_busy_is_busy_for_a_gbt_car() {
        val s = station("Занято", connectors = listOf(gbt("charging"), gbt("occupied"), other("CCS2")))
        val summary = ChargerSelection.summarize(s.connectors!!, ChargeConnector.GBT)!!
        assertEquals(2, summary.total)
        assertEquals(0, summary.free)
        assertEquals(StationStatus.BUSY, summary.status)
        // The driver may still want it: it stays in the list.
        assertEquals(listOf("Занято"), choose(listOf(s)).picks.map { it.station.name })
    }

    @Test fun every_gbt_out_of_order_is_unavailable() {
        val summary = ChargerSelection.summarize(listOf(gbt("unavailable"), gbt("error")), ChargeConnector.GBT)!!
        assertEquals(StationStatus.DOWN, summary.status)
        assertEquals(0, summary.free)
    }

    // BETA sends some connectors without a status: unknown is not busy.
    @Test fun unknown_status_is_not_counted_as_busy() {
        val one = ChargerSelection.summarize(listOf(gbt(null), gbt("charging")), ChargeConnector.GBT)!!
        assertNull(one.status)
        assertEquals(0, one.free)
        assertEquals(1, one.unknown)
        val both = ChargerSelection.summarize(listOf(gbt(null), gbt(null)), ChargeConnector.GBT)!!
        assertNull(both.status)
        assertEquals(0, both.free)
        assertEquals(2, both.unknown)
    }

    @Test fun a_status_outside_the_known_set_is_unknown() {
        val s = ChargerSelection.summarize(listOf(gbt("reserved"), gbt("unavailable")), ChargeConnector.GBT)!!
        assertEquals(1, s.unknown)
        assertNull(s.status)
    }

    @Test fun one_free_connector_makes_the_station_free_despite_unknown_ones() {
        val s = ChargerSelection.summarize(listOf(gbt(null), gbt("available")), ChargeConnector.GBT)!!
        assertEquals(StationStatus.FREE, s.status)
        assertEquals(1, s.unknown)
    }

    @Test fun price_range_power_and_currency_of_the_cars_connectors() {
        val summary = ChargerSelection.summarize(
            listOf(gbt("available", 22.0, 0.54), gbt("available", 120.0, 0.72), other("CCS2")), ChargeConnector.GBT)!!
        assertEquals(120.0, summary.maxPowerKw!!, 0.0)
        assertEquals(0.54, summary.minPricePerKwh!!, 1e-9)
        assertEquals(0.72, summary.maxPricePerKwh!!, 1e-9)
        assertEquals("BYN", summary.currency)
    }

    @Test fun station_without_the_cars_connector_is_left_out() {
        val choice = choose(listOf(
            station("Только Type2", northKm = 0.5, connectors = listOf(other("Type2"))),
            station("GB/T", northKm = 2.0),
        ))
        assertEquals(listOf("GB/T"), choice.picks.map { it.station.name })
        assertFalse(choice.connectorMissing)
    }

    @Test fun no_station_with_the_cars_connector_lists_the_others_and_says_so() {
        val choice = choose(listOf(station("Только Type2", connectors = listOf(other("Type2")))))
        assertEquals(listOf("Только Type2"), choice.picks.map { it.station.name })
        assertTrue(choice.connectorMissing)
        assertNull(choice.picks.single().summary)
    }

    @Test fun another_connector_asked_by_voice_filters_by_it() {
        val choice = choose(listOf(
            station("GB/T", northKm = 0.5),
            station("CCS2", northKm = 2.0, connectors = listOf(other("CCS2"))),
        ), connector = ChargeConnector.CCS2)
        assertEquals(listOf("CCS2"), choice.picks.map { it.station.name })
    }

    // Gateway and OpenStreetMap know no connectors: nothing to filter by.
    @Test fun sources_without_connectors_are_not_filtered() {
        val choice = choose(listOf(station("A", connectors = null), station("B", northKm = 3.0, connectors = null)))
        assertEquals(2, choice.picks.size)
        assertFalse(choice.connectorMissing)
    }

    @Test fun around_is_nearest_first_and_capped() {
        val stations = (12 downTo 1).map { station("S$it", northKm = it.toDouble()) }
        val picks = choose(stations).picks
        assertEquals(ChargerSelection.MAX_PICKS, picks.size)
        assertEquals((1..8).map { "S$it" }, picks.map { it.station.name })
    }

    // --- direction of travel ---

    @Test fun corridor_keeps_a_station_20_km_ahead_3_km_aside_and_drops_one_5_km_behind() {
        val choice = choose(listOf(
            station("Впереди", northKm = 20.0, eastKm = 3.0),
            station("Позади", northKm = -5.0),
            station("Далеко в стороне", northKm = 3.0, eastKm = 10.0),
        ), where = ChargerWhere.AHEAD, course = 0.0)
        assertEquals(AheadOutcome.CORRIDOR, choice.ahead)
        val pick = choice.picks.single()
        assertEquals("Впереди", pick.station.name)
        assertEquals(RelPosition.AHEAD, pick.position)
    }

    @Test fun corridor_orders_by_distance_along_the_course() {
        val picks = choose(listOf(
            station("30 км", northKm = 30.0, eastKm = 1.0),
            station("10 км", northKm = 10.0, eastKm = -2.0),
        ), where = ChargerWhere.AHEAD, course = 0.0).picks
        assertEquals(listOf("10 км", "30 км"), picks.map { it.station.name })
    }

    @Test fun corridor_widens_with_distance() {
        assertTrue(ChargerSelection.inCorridor(forwardKm = 20.0, lateralKm = 5.9))
        assertFalse(ChargerSelection.inCorridor(forwardKm = 20.0, lateralKm = 6.1))
        assertTrue(ChargerSelection.inCorridor(forwardKm = 1.0, lateralKm = 3.0))
        assertFalse(ChargerSelection.inCorridor(forwardKm = -1.0, lateralKm = 0.0))
    }

    @Test fun known_destination_ranks_by_detour_and_orders_by_distance() {
        val choice = choose(listOf(
            station("60 км по пути", northKm = 60.0, eastKm = -1.0),
            station("40 км по пути", northKm = 40.0, eastKm = 2.0),
            station("Крюк в сторону", northKm = 20.0, eastKm = 30.0),
            station("8 км назад", northKm = -8.0),
        ), where = ChargerWhere.AHEAD, course = 0.0, destination = point(100.0, 0.0))
        assertEquals(AheadOutcome.DESTINATION, choice.ahead)
        assertEquals(listOf("40 км по пути", "60 км по пути"), choice.picks.map { it.station.name })
        val detour = choice.picks.first().detourKm!!
        assertTrue("detour $detour", detour in 0.0..1.0)
    }

    // A station behind the car is no stop on the way, however small the detour: 4 km back with
    // the route's end 100 km ahead costs 8 km, inside the 10 km limit.
    @Test fun destination_mode_drops_stations_behind() {
        val dest = point(100.0, 0.0)
        val back = station("4 км назад", northKm = -4.0)
        val forward = station("20 км вперёд", northKm = 20.0)
        for (course in listOf(0.0, null)) {
            val choice = choose(listOf(back, forward), where = ChargerWhere.AHEAD, course = course, destination = dest)
            assertEquals(AheadOutcome.DESTINATION, choice.ahead)
            assertEquals(listOf("20 км вперёд"), choice.picks.map { it.station.name })
        }
        val only = choose(listOf(back), where = ChargerWhere.AHEAD, course = 0.0, destination = dest)
        assertEquals(AheadOutcome.NOTHING_AHEAD, only.ahead)
        assertEquals(listOf("4 км назад"), only.picks.map { it.station.name })
        assertEquals(RelPosition.BEHIND, only.picks.single().position)
    }

    // Detour threshold: 5 km, or 10 % of the straight distance to the destination when longer.
    @Test fun detour_threshold_grows_with_the_distance_to_the_destination() {
        assertEquals(5.0, ChargerSelection.maxDetourKm(30.0), 1e-9)
        assertEquals(30.0, ChargerSelection.maxDetourKm(300.0), 1e-9)
    }

    @Test fun nothing_ahead_falls_back_to_the_nearest_around_marked_behind() {
        val choice = choose(listOf(
            station("7 км позади", northKm = -7.0),
            station("5 км позади", northKm = -5.0),
        ), where = ChargerWhere.AHEAD, course = 0.0)
        assertEquals(AheadOutcome.NOTHING_AHEAD, choice.ahead)
        assertEquals(listOf("5 км позади", "7 км позади"), choice.picks.map { it.station.name })
        assertTrue(choice.picks.all { it.position == RelPosition.BEHIND })
    }

    @Test fun ahead_without_course_or_destination_is_around() {
        val choice = choose(listOf(station("A")), where = ChargerWhere.AHEAD, course = null)
        assertEquals(AheadOutcome.NO_COURSE, choice.ahead)
        assertNull(choice.picks.single().position)
    }

    @Test fun around_marks_position_when_the_course_is_known() {
        val picks = choose(listOf(
            station("Сбоку", northKm = 0.0, eastKm = 2.0),
            station("Позади", northKm = -3.0),
            station("Впереди", northKm = 4.0),
        ), course = 0.0).picks
        assertEquals(
            listOf(RelPosition.ASIDE, RelPosition.BEHIND, RelPosition.AHEAD),
            picks.map { it.position })
        assertEquals(AheadOutcome.NOT_ASKED, choose(listOf(station("A")), course = 0.0).ahead)
    }

    @Test fun default_is_ahead_only_when_moving_with_a_known_course() {
        assertEquals(ChargerWhere.AHEAD, ChargerSelection.defaultWhere(course = 0.0, speedKmh = 50))
        assertEquals(ChargerWhere.AROUND, ChargerSelection.defaultWhere(course = 0.0, speedKmh = 20))
        assertEquals(ChargerWhere.AROUND, ChargerSelection.defaultWhere(course = null, speedKmh = 90))
        assertEquals(ChargerWhere.AROUND, ChargerSelection.defaultWhere(course = 0.0, speedKmh = null))
    }

    @Test fun ahead_radius_grows_with_speed() {
        assertEquals(30, ChargerSelection.aheadRadiusKm(null))
        assertEquals(30, ChargerSelection.aheadRadiusKm(45))
        assertEquals(60, ChargerSelection.aheadRadiusKm(75))
        assertEquals(100, ChargerSelection.aheadRadiusKm(110))
    }

    // A destination counts while the car is still getting closer to it in this trip.
    @Test fun destination_is_used_only_while_the_car_approaches_it() {
        val dest = point(100.0, 0.0)
        assertTrue(ChargerSelection.stillApproaching(car, dest, startKm = 120.0, course = 0.0))
        assertTrue(ChargerSelection.stillApproaching(car, dest, startKm = null, course = null))
        // Turned back: pointing away from it.
        assertFalse(ChargerSelection.stillApproaching(car, dest, startKm = 120.0, course = 180.0))
        // Farther than when the route was built.
        assertFalse(ChargerSelection.stillApproaching(car, dest, startKm = 90.0, course = 0.0))
        // Arrived.
        assertFalse(ChargerSelection.stillApproaching(car, point(0.5, 0.0), startKm = 10.0, course = 0.0))
    }

    @Test fun picks_carry_the_straight_distance() {
        val pick = choose(listOf(station("10 км", northKm = 10.0))).picks.single()
        assertNotNull(pick.distanceKm)
        assertEquals(10.0, pick.distanceKm, 0.05)
    }
}
