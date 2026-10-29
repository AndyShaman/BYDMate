package com.bydmate.app.data.carstatus

import org.junit.Assert.assertEquals
import org.junit.Test

class CarStatusParserTest {

    private fun parse(vararg rows: Pair<String, String?>) = CarStatusParser.parse(mapOf(*rows))

    @Test fun leopard3_switches_off_hides_service() {
        val s = parse(
            "car_status_issue" to "", "car_status_issue_num" to "0",
            "car_status_maintenance_time" to "0", "car_status_maintenance_mile" to "0",
            "maintenance_time_switch_state" to "0", "maintenance_mile_switch_state" to "0",
            "set_car_status_maintenance_time" to "991", "set_car_status_maintenance_mile" to "3500",
        )
        assertEquals(CarServiceStatus(0, null, null), s)
    }

    @Test fun dolphin_dilink3_values_pass() {
        val s = parse(
            "car_status_issue" to "#", "car_status_issue_num" to "0",
            "car_status_maintenance_time" to "278", "set_car_status_maintenance_time" to "360",
            "car_status_maintenance_mile" to "27609", "set_car_status_maintenance_mile" to "34000",
            "maintenance_time_switch_state" to "1", "maintenance_mile_switch_state" to "1",
            "car_status_maintenance_hev_mile" to "34001",
        )
        assertEquals(CarServiceStatus(0, 278, 27609), s)
    }

    @Test fun time_sentinel_991_is_null() {
        val s = parse("maintenance_time_switch_state" to "1", "car_status_maintenance_time" to "991")
        assertEquals(null, s.daysLeft)
    }

    @Test fun mile_above_interval_is_null() {
        val s = parse(
            "maintenance_mile_switch_state" to "1", "car_status_maintenance_mile" to "20001",
            "set_car_status_maintenance_mile" to "3500",
        )
        assertEquals(null, s.kmLeft)
    }

    @Test fun hev_switch_2_reads_hev_mile() {
        val s = parse(
            "maintenance_mile_switch_state" to "2", "car_status_maintenance_hev_mile" to "1200",
            "set_car_status_maintenance_hev_mile" to "5000",
        )
        assertEquals(1200, s.kmLeft)
    }

    @Test fun issue_string_counted_when_num_missing() {
        val s = parse("car_status_issue" to "3\$1700000000&1#12\$1700000001&2")
        assertEquals(2, s.faults)
    }

    @Test fun garbage_numbers_are_null() {
        val s = parse("car_status_issue_num" to "abc", "car_status_maintenance_time" to "")
        assertEquals(null, s.faults)
        assertEquals(null, s.daysLeft)
    }

    @Test fun empty_map_is_all_null() {
        assertEquals(CarServiceStatus(null, null, null), CarStatusParser.parse(emptyMap()))
    }
}
