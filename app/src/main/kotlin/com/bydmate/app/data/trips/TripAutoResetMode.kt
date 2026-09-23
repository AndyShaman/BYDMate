package com.bydmate.app.data.trips

import com.bydmate.app.data.local.entity.ChargeEntity

/** Per-counter rule for resetting TRIP 1 / TRIP 2 when a charging session is recorded (#235).
 *  [key] is the persisted settings value. */
enum class TripAutoResetMode(val key: String) {
    OFF("off"),
    ANY("any"),
    AC("ac"),
    DC("dc"),
    FULL("full");

    /** Whether a just-recorded [charge] triggers a reset under this mode. */
    fun shouldReset(charge: ChargeEntity): Boolean = when (this) {
        OFF -> false
        ANY -> true
        AC -> charge.type.equals("AC", ignoreCase = true)
        DC -> charge.type.equals("DC", ignoreCase = true)
        FULL -> (charge.socEnd ?: 0) >= 100
    }

    companion object {
        /** Unknown or absent value maps to OFF. */
        fun fromKey(key: String?): TripAutoResetMode = entries.firstOrNull { it.key == key } ?: OFF
    }
}
