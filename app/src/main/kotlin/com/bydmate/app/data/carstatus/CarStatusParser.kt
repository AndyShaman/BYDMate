package com.bydmate.app.data.carstatus

/** Faults count and service countdown from the stock car status provider; null = not shown. */
data class CarServiceStatus(val faults: Int?, val daysLeft: Int?, val kmLeft: Int?)

/**
 * Pure mapping of the provider's key/value rows to [CarServiceStatus]. The provider only stores
 * what the cluster wrote, so every value is held to the meaning the stock CarSettings gives it:
 * a countdown counts only while its reminder switch is on, and "max + 1" (991 days, 20001 or
 * 34001 km) is the firmware's "unused" marker, not a distance.
 */
object CarStatusParser {

    const val ISSUE = "car_status_issue"
    const val ISSUE_NUM = "car_status_issue_num"
    const val TIME = "car_status_maintenance_time"
    const val SET_TIME = "set_car_status_maintenance_time"
    const val MILE = "car_status_maintenance_mile"
    const val SET_MILE = "set_car_status_maintenance_mile"
    const val HEV_MILE = "car_status_maintenance_hev_mile"
    const val SET_HEV_MILE = "set_car_status_maintenance_hev_mile"
    const val TIME_SWITCH = "maintenance_time_switch_state"
    const val MILE_SWITCH = "maintenance_mile_switch_state"

    /** MaintainTimeModel caps the interval at 990 days; 991 is what the SDK returns when off. */
    private const val MAX_DAYS = 990

    /** Countdown ceiling when the car stores no interval to compare against. */
    private const val MAX_KM_NO_INTERVAL = 99_999

    fun parse(rows: Map<String, String?>): CarServiceStatus =
        CarServiceStatus(faults(rows), daysLeft(rows), kmLeft(rows))

    /** The car's own count; without it the `ID$time&color#...` list, whose IDs are firmware-local. */
    private fun faults(rows: Map<String, String?>): Int? {
        rows.int(ISSUE_NUM)?.takeIf { it >= 0 }?.let { return it }
        val issue = rows[ISSUE] ?: return null
        return issue.split('#').count { it.isNotBlank() }
    }

    private fun daysLeft(rows: Map<String, String?>): Int? {
        if (rows[TIME_SWITCH]?.trim() != "1") return null
        return rows.int(TIME)?.takeIf { it in 0..MAX_DAYS }
    }

    /** Switch 1 reads the plain countdown, 2 the HEV one (autovoice `openHEVMaintenanceMile`). */
    private fun kmLeft(rows: Map<String, String?>): Int? {
        val (valueKey, intervalKey) = when (rows[MILE_SWITCH]?.trim()) {
            "1" -> MILE to SET_MILE
            "2" -> HEV_MILE to SET_HEV_MILE
            else -> return null
        }
        val value = rows.int(valueKey)?.takeIf { it >= 0 } ?: return null
        val ceiling = rows.int(intervalKey)?.takeIf { it > 0 } ?: MAX_KM_NO_INTERVAL
        return value.takeIf { it <= ceiling }
    }

    private fun Map<String, String?>.int(key: String): Int? = this[key]?.trim()?.toIntOrNull()
}
