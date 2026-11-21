package com.bydmate.app.helper.dtc

/**
 * The security attempts of the tyre write (owner's rule 2026-11-20; never a head-unit or daemon
 * restart): an attempt counts as its `27 02` goes out and stays a failure until exactly `67 02`
 * clears the count. After a failure no write starts for [PAUSE_MS]; after [MAX_FAILURES] none
 * until [RESET_MS] after the last one, when the count clears by itself. An NRC 36 or 37 to
 * `27 01` / `27 02` holds every write start for [LOCKOUT_MS]. Times are the scanner's clock.
 */
internal class TpmsSecurity {
    private var failures = 0
    private var lastFailureMs = 0L
    private var lockedUntilMs = 0L

    /** A `27 02` reached the device: one failed attempt until its `67 02` says otherwise. */
    @Synchronized fun keySent(nowMs: Long) {
        failures++
        lastFailureMs = nowMs
        log("tpms security attempt counted failures=$failures/$MAX_FAILURES at_ms=$nowMs")
    }

    /** Exactly `67 02`: the count starts again. */
    @Synchronized fun unlocked() {
        failures = 0
        log("tpms security count reset by 67 02")
    }

    /** The block answered NRC 36 (attempts exceeded) or 37 (delay not expired). */
    @Synchronized fun lockout(nowMs: Long, nrc: Int) {
        lockedUntilMs = nowMs + LOCKOUT_MS
        log("tpms security lockout nrc=0x%02X until_ms=$lockedUntilMs".format(nrc))
    }

    /** Milliseconds until a write may start; 0 when it may now. */
    @Synchronized fun waitMs(nowMs: Long): Long {
        if (failures > 0 && nowMs - lastFailureMs >= RESET_MS) {
            log("tpms security count reset after ${RESET_MS / MINUTE_MS} min failures=$failures")
            failures = 0
        }
        val pauseUntil = when {
            failures >= MAX_FAILURES -> lastFailureMs + RESET_MS
            failures > 0 -> lastFailureMs + PAUSE_MS
            else -> 0L
        }
        return (maxOf(pauseUntil, lockedUntilMs) - nowMs).coerceAtLeast(0L)
    }

    companion object {
        const val MAX_FAILURES = 3
        private const val MINUTE_MS = 60_000L
        const val PAUSE_MS = MINUTE_MS
        const val RESET_MS = 30 * MINUTE_MS
        const val LOCKOUT_MS = 10 * MINUTE_MS
        /** exceededNumberOfAttempts, requiredTimeDelayNotExpired. */
        val LOCKOUT_NRCS = setOf(0x36, 0x37)
    }
}
