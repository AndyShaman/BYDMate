package com.bydmate.app.data.vehicle

/** A seat comfort target: which seat + heat/vent. */
enum class SeatGroup { DRIVER_HEAT, DRIVER_VENT, PASSENGER_HEAT, PASSENGER_VENT }

/** Status-classified single write, supplied by VehicleApiImpl.doWriteOutcome. */
fun interface SeatWriter {
    suspend fun write(actionName: String, value: Int): WriteOutcome
}

/**
 * Owns the per-device seat write-channel selection. Tries the validated dev=1000
 * primary; on a NOOP/PERMANENT_DENIED result (channel ineffective on this model)
 * falls back to the competitor dev=1001 channel; remembers the winner so later
 * actuations skip the dead channel. A TRANSIENT result (car off / daemon down)
 * never changes the remembered winner. Single consumer of this policy: seats.
 */
class AdaptiveSeatChannel(
    private val writer: SeatWriter,
    private val store: SeatChannelStore,
) {
    private data class Actions(val switch: String, val level: String, val fallback: String)

    private fun actionsFor(g: SeatGroup): Actions = when (g) {
        SeatGroup.DRIVER_HEAT -> Actions("driver_seat_heat_switch", "driver_seat_heat_level", "driver_seat_heat_fallback")
        SeatGroup.DRIVER_VENT -> Actions("driver_seat_vent_switch", "driver_seat_vent_level", "driver_seat_vent_fallback")
        SeatGroup.PASSENGER_HEAT -> Actions("passenger_seat_heat_switch", "passenger_seat_heat_level", "passenger_seat_heat_fallback")
        SeatGroup.PASSENGER_VENT -> Actions("passenger_seat_vent_switch", "passenger_seat_vent_level", "passenger_seat_vent_fallback")
    }

    /** Actuate [group] at [level] (0=off, 1..5). Returns true iff a channel produced REAL. */
    suspend fun actuate(group: SeatGroup, level: Int): Boolean {
        val a = actionsFor(group)
        return when (store.winner()) {
            SeatChannel.PRIMARY -> primary(a, level) == WriteOutcome.REAL
            SeatChannel.FALLBACK -> fallbackDirect(a, level) == WriteOutcome.REAL
            SeatChannel.UNKNOWN -> probe(a, level)
        }
    }

    private suspend fun probe(a: Actions, level: Int): Boolean {
        val p = primary(a, level)
        if (p == WriteOutcome.REAL) { store.setWinner(SeatChannel.PRIMARY); return true }
        if (p == WriteOutcome.TRANSIENT) return false   // car off / daemon down — don't switch
        // NOOP or PERMANENT_DENIED → primary ineffective on this model. Probe fallback with
        // a model-universal value so a high requested stage can't give a false "fallback dead".
        val f = fallbackProbe(a, level)
        if (f == WriteOutcome.REAL) { store.setWinner(SeatChannel.FALLBACK); return true }
        return false   // both dead → stay UNKNOWN, audit log already recorded both attempts
    }

    /** Primary dev=1000: switch (1=on / 2=off) — level-independent, so it is the detection
     *  write — then, for on, the requested level (best-effort). Outcome = the switch write's
     *  outcome. NOTE: off is switch=2, NOT 0. Live-confirmed on Leopard 3 2026-07-01: the
     *  autoservice returns status=1 ("ok") for switch=0 but does NOT actuate — the seat stays
     *  on. 2 is the real off code (matches the read status enum: 1=on, 2=off). */
    private suspend fun primary(a: Actions, level: Int): WriteOutcome {
        val sw = writer.write(a.switch, if (level == 0) 2 else 1)
        if (level > 0 && sw == WriteOutcome.REAL) writer.write(a.level, level)
        return sw
    }

    /** Cached fallback: channel already known, write the requested stage value directly
     *  (no re-probe, no flicker). 1=off, 2=lvl1 … 6=lvl5. */
    private suspend fun fallbackDirect(a: Actions, level: Int): WriteOutcome =
        writer.write(a.fallback, if (level == 0) 1 else level + 1)

    /**
     * Probe fallback with a MODEL-UNIVERSAL value (off=1, or stage-1 on=2 — both valid on
     * 3- and 5-stage cars), take the channel verdict from it, then best-effort apply the
     * requested stage. The apply write's outcome does NOT change the verdict: a no-op of a
     * high stage on a 3-stage car means "stage unsupported on this trim", not "channel
     * dead", so it never un-learns the winner (still audit-logged as outcome_NOOP).
     */
    private suspend fun fallbackProbe(a: Actions, level: Int): WriteOutcome {
        val outcome = writer.write(a.fallback, if (level == 0) 1 else 2)
        if (level > 1 && outcome == WriteOutcome.REAL) writer.write(a.fallback, level + 1)
        return outcome
    }
}
