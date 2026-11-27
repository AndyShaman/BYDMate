package com.bydmate.app.data.automation

import com.bydmate.app.data.local.entity.ActionDef

/**
 * #457: pacing between consecutive car commands in one rule so an ECU (for example, climate
 * dev 1000) does not drop writes arriving a few milliseconds apart.
 */
object RuleStepPacing {

    const val PARAM_GAP_MS = 150L

    /** Delay to wait before [next] given [prev]: [PARAM_GAP_MS] only when both are car commands. */
    fun gapBefore(prev: ActionDef?, next: ActionDef): Long =
        if (prev?.kind == "param" && next.kind == "param") PARAM_GAP_MS else 0L
}
