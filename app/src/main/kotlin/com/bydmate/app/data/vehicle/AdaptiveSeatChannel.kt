package com.bydmate.app.data.vehicle

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** A seat comfort target: which seat + heat/vent. */
enum class SeatGroup { DRIVER_HEAT, DRIVER_VENT, PASSENGER_HEAT, PASSENGER_VENT }

/** Status-classified single write, supplied by VehicleApiImpl.doWriteOutcome. */
fun interface SeatWriter {
    suspend fun write(actionName: String, value: Int): WriteOutcome
}

/**
 * Reads the dev=1000 status fid of a seat group (1=on, 2=off), supplied by VehicleApiImpl.
 * Null means inconclusive — the read did not complete (daemon down, transact error), came
 * back as a sentinel, or the group has no validated status fid (passenger). Never a verdict
 * about the write that preceded it.
 */
fun interface SeatReadback {
    suspend fun read(group: SeatGroup): Int?
}

/**
 * Owns the per-device seat write-channel selection. Tries the validated dev=1000
 * primary; on a NOOP/PERMANENT_DENIED result (channel ineffective on this model)
 * falls back to the competitor dev=1001 channel; remembers the winner so later
 * actuations skip the dead channel. A TRANSIENT result (car off / daemon down)
 * never changes the remembered winner. Single consumer of this policy: seats.
 *
 * The autoservice status alone is not enough to tell the channels apart: on Song L and
 * Han EV every dev=1000 seat write answers status=1 while nothing moves (#74/#98/#109),
 * which used to cement PRIMARY as the winner and hide the fallback forever. So a REAL
 * switch write is verified against the group's status fid ([SeatReadback]); a status fid
 * that disagrees twice downgrades the write to NOOP and lets the existing fallback probe
 * run. A failed read leaves REAL alone — we do not punish a write for a read error.
 */
class AdaptiveSeatChannel(
    private val writer: SeatWriter,
    private val store: SeatChannelStore,
    private val readback: SeatReadback = SeatReadback { null },
    private val journal: SeatCommandJournal? = null,
) {
    private data class Actions(val switch: String, val level: String, val fallback: String)

    /** Outcome of a primary attempt; [contradicted] = write accepted BUT the status fid disagreed twice. */
    private data class PrimaryResult(val outcome: WriteOutcome, val contradicted: Boolean)

    /** Result of the readback probe: [contradicted] plus the rendered reads for the journal. */
    private data class Verdict(val contradicted: Boolean, val detail: String)

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
            SeatChannel.PRIMARY -> cachedPrimary(group, a, level)
            SeatChannel.FALLBACK -> fallbackDirect(group, a, level) == WriteOutcome.REAL
            SeatChannel.UNKNOWN -> probe(group, a, level)
        }
    }

    /**
     * Fresh car, or a car whose winner never settled. The store's re-probe budget gates the
     * fallback here too: with both channels dead the winner stays UNKNOWN, and without the
     * gate every later command would probe the fallback again. The budget is spent only on a
     * definitive fallback verdict (NOOP/PERMANENT_DENIED) — a TRANSIENT probe leaves it intact,
     * so a car probed while switched off does not lose its fallback forever. One probe per
     * process, re-armed by the manual channel reset (setWinner(UNKNOWN) from the settings screen).
     */
    private suspend fun probe(group: SeatGroup, a: Actions, level: Int): Boolean {
        val p = primary(group, a, level)
        if (p.outcome == WriteOutcome.REAL) { store.setWinner(SeatChannel.PRIMARY); return true }
        if (p.outcome == WriteOutcome.TRANSIENT) return false   // car off / daemon down — don't switch
        if (store.reprobeExhausted()) return false              // the single fallback probe is spent
        // NOOP or PERMANENT_DENIED → primary ineffective on this model. Probe fallback with
        // a model-universal value so a high requested stage can't give a false "fallback dead".
        val f = fallbackProbe(group, a, level)
        if (f == WriteOutcome.REAL) { store.setWinner(SeatChannel.FALLBACK); return true }
        if (f != WriteOutcome.TRANSIENT) store.claimReprobe()
        return false   // both dead → stay UNKNOWN, audit log already recorded both attempts
    }

    /**
     * Cached PRIMARY: this car already actuated on the primary channel, so a dead status fid
     * must not un-learn it — several models simply do not wire the status fids, and re-probing
     * the fallback on every command would flicker the seat. Only a CONFIRMED contradiction
     * (write accepted AND the status fid disagreed on both reads) re-opens the question, and
     * only once per process — re-armed by the manual channel reset, which stores UNKNOWN.
     * PRIMARY is kept until the fallback proves itself REAL: an
     * unconfirmed probe must not un-learn the channel this car already actuated on, and
     * leaving the store UNKNOWN would send every later command back through [probe].
     */
    private suspend fun cachedPrimary(group: SeatGroup, a: Actions, level: Int): Boolean {
        val p = primary(group, a, level)
        if (p.outcome == WriteOutcome.REAL) return true
        if (!p.contradicted || !store.claimReprobe()) return false
        val f = fallbackProbe(group, a, level)
        if (f == WriteOutcome.REAL) { store.setWinner(SeatChannel.FALLBACK); return true }
        return false
    }

    /** Primary dev=1000: switch (1=on / 2=off) — level-independent, so it is the detection
     *  write — then, for on, the requested level (best-effort). Outcome = the switch write's
     *  outcome, downgraded to NOOP when the readback contradicts it. NOTE: off is switch=2,
     *  NOT 0. Live-confirmed on Leopard 3 2026-07-01: the autoservice returns status=1 ("ok")
     *  for switch=0 but does NOT actuate — the seat stays on. 2 is the real off code (matches
     *  the read status enum: 1=on, 2=off). */
    private suspend fun primary(group: SeatGroup, a: Actions, level: Int): PrimaryResult {
        val sw = writer.write(a.switch, if (level == 0) 2 else 1)
        if (level > 0 && sw == WriteOutcome.REAL) writer.write(a.level, level)
        if (sw != WriteOutcome.REAL) {
            journal?.appendActuation(group, level, SeatChannel.PRIMARY, sw, "n/a")
            return PrimaryResult(sw, contradicted = false)
        }
        val verdict = verify(group, level)
        val outcome = if (verdict.contradicted) WriteOutcome.NOOP else WriteOutcome.REAL
        journal?.appendActuation(group, level, SeatChannel.PRIMARY, outcome, verdict.detail)
        return PrimaryResult(outcome, verdict.contradicted)
    }

    /**
     * Reads the group's status fid back after an accepted switch write and compares it with
     * the state we commanded (1=on, 2=off). Anything else — 0 on a model that never wired the
     * fid, a sentinel, a stale opposite value — counts as disagreement, but only a second
     * disagreeing read makes it a contradiction, since the climate module publishes the new
     * state with a delay. A read that does not complete (null or throw) is not evidence
     * against the write and ends the probe in favour of the write.
     */
    private suspend fun verify(group: SeatGroup, level: Int): Verdict {
        val expected = if (level == 0) STATUS_OFF else STATUS_ON
        val seen = mutableListOf<String>()
        repeat(READBACK_ATTEMPTS) {
            delay(READBACK_DELAY_MS)
            val value = try {
                readback.read(group)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (value == null) {
                seen += "err"
                return Verdict(contradicted = false, detail = detail(expected, seen))
            }
            seen += value.toString()
            if (value == expected) return Verdict(contradicted = false, detail = detail(expected, seen))
        }
        return Verdict(contradicted = true, detail = detail(expected, seen))
    }

    private fun detail(expected: Int, seen: List<String>) = "expect=$expected got=${seen.joinToString(",")}"

    /** Cached fallback: channel already known, write the requested stage value directly
     *  (no re-probe, no flicker). 1=off, 2=lvl1 … 6=lvl5. */
    private suspend fun fallbackDirect(group: SeatGroup, a: Actions, level: Int): WriteOutcome {
        val outcome = writer.write(a.fallback, if (level == 0) 1 else level + 1)
        journal?.appendActuation(group, level, SeatChannel.FALLBACK, outcome, "n/a")
        return outcome
    }

    /**
     * Probe fallback with a MODEL-UNIVERSAL value (off=1, or stage-1 on=2 — both valid on
     * 3- and 5-stage cars), take the channel verdict from it, then best-effort apply the
     * requested stage. The apply write's outcome does NOT change the verdict: a no-op of a
     * high stage on a 3-stage car means "stage unsupported on this trim", not "channel
     * dead", so it never un-learns the winner (still audit-logged as outcome_NOOP).
     */
    private suspend fun fallbackProbe(group: SeatGroup, a: Actions, level: Int): WriteOutcome {
        val outcome = writer.write(a.fallback, if (level == 0) 1 else 2)
        if (level > 1 && outcome == WriteOutcome.REAL) writer.write(a.fallback, level + 1)
        journal?.appendActuation(group, level, SeatChannel.FALLBACK, outcome, "n/a")
        return outcome
    }

    private companion object {
        const val STATUS_ON = 1
        const val STATUS_OFF = 2
        const val READBACK_ATTEMPTS = 2
        /** Per readback attempt. The whole verify runs inside the NonCancellable seat write, so
         *  this is added latency on every seat command — 2 × 400 ms is the budget that still
         *  leaves the climate module time to publish the new status. */
        const val READBACK_DELAY_MS = 400L
    }
}
