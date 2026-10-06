package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/** What the engine did with a rule whose condition just turned true (or was pressed, or spoken). */
internal enum class EdgeOutcome(val label: String) {
    FIRE("fire"),
    CONFIRM("confirm"),
    SKIP_COOLDOWN("skip:cooldown"),
    SKIP_PARK("skip:park"),
    SKIP_ONCE("skip:once"),

    /** The condition was already true the first time the engine saw the rule: remembered, not
     *  fired (#271, #253 — "the rule did nothing"). */
    SEED_FIRST_CHECK("seed-first-check"),
}

/**
 * The automation lines of the trace: one per condition edge with what the engine did about it,
 * and one per executed step with its verdict. Never per poll. A rule is named by its id only:
 * its name is the user's text.
 */
internal object AutoTrace {

    /** [src] is where the edge came from: `poll`, or the manual trigger kind (key, button, voice). */
    fun edge(ruleId: Long, outcome: EdgeOutcome, src: String, by: Long? = null): Long =
        Trace.event(TraceArea.AUTO, "rule", "id" to ruleId, "edge" to outcome.label, "src" to src, by = by)

    fun step(ruleId: Long, n: Int, action: ActionDef, result: DispatchResult, by: Long?): Long =
        Trace.event(
            TraceArea.AUTO, "step", "rule" to ruleId, "n" to n, "action" to actionLabel(action),
            "st" to if (result.success) "ok" else "failed", "reason" to result.reason?.let(RuleJournal::redactUris), by = by,
        )

    /** A param step's command is a fixed vehicle code; every other kind carries user data in its
     *  payload, so only the kind is named. */
    fun actionLabel(action: ActionDef): String = if (action.kind == "param") action.command else action.kind
}
