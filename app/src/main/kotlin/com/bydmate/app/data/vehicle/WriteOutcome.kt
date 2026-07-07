package com.bydmate.app.data.vehicle

/**
 * Classification of an autoservice setInt result, derived from the raw status the
 * helper daemon forwards. Drives the seat adaptive-channel fallback policy.
 *  - REAL: status >= 1, the actuator actually moved.
 *  - NOOP: status == 0, accepted but ineffective on this trim (try the other channel).
 *  - PERMANENT_DENIED: status == -10011, rejected by permission/state gate (try the other channel).
 *  - TRANSIENT: any other negative or null (daemon down, car off, no data) — do not switch channel.
 */
enum class WriteOutcome {
    REAL, NOOP, PERMANENT_DENIED, TRANSIENT;

    companion object {
        fun fromStatus(status: Int?): WriteOutcome = when {
            status == null -> TRANSIENT
            status >= 1 -> REAL
            status == 0 -> NOOP
            status == -10011 -> PERMANENT_DENIED
            else -> TRANSIENT
        }
    }
}
