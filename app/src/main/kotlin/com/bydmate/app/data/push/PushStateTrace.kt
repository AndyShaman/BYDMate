package com.bydmate.app.data.push

import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/**
 * Trace lines for the car states the push channel carries that nothing else traces: the turn
 * signal, and the ADAS states around the lane change by turn signal — so a drive where the car
 * reported that lane change unavailable can be laid against our own camera use on one time line.
 * Only a change is a line (the first value after start has no `from`); a sentinel reads `none`.
 *
 * Called on the binder thread that delivers a push packet.
 */
internal class PushStateTrace {

    /** Last traced value per fid. */
    private val last = HashMap<Int, String>()

    /** [field] is the FidMap field the fid belongs to, null for the ADAS states. */
    @Synchronized
    fun onEvent(fid: Int, field: String?, raw: Int) {
        val value = when {
            field == TURN_SIGNAL -> turnSide(raw)
            field == null && fid in ADAS_STATES -> SentinelDecoder.decodeInt(raw)?.toString() ?: NONE
            else -> return
        }
        val was = last[fid]
        if (was == value) return
        last[fid] = value
        if (field == TURN_SIGNAL) {
            Trace.event(TraceArea.CAR, "turn", "from" to was, "to" to value)
        } else {
            Trace.event(TraceArea.CAR, "adas", "name" to ADAS_STATES[fid], "from" to was, "to" to value)
        }
    }

    companion object {
        private const val TURN_SIGNAL = "turnSignal"
        private const val NONE = "none"

        /** ADAS device: the one bsdLeft/bsdRight are pushed from. */
        const val ADAS_DEVICE = 1038

        /**
         * ADAS states subscribed for the trace alone, fid → trace name (Leopard 3
         * BYDAutoFeatureIds; all seven answered a plain read there on 2026-09-29). Not FidMap
         * fields: nothing polls them, patches the snapshot with them or resolves them against
         * the catalog, so on a platform that numbers them differently they simply fail to register.
         */
        val ADAS_STATES: Map<Int, String> = linkedMapOf(
            535826452 to "lane-change-gray",    // ADAS_INTERACTIVE_LANE_CHANGE_ASSIST_GRAY
            700448776 to "ilca-switch",         // ADAS_ILCA_SWITCH_STATE
            -1728052359 to "domain-disconnect", // ADAS_DOMAIN_CONTROL_DISCONNECTION_STATUS
            230686760 to "tor-fault",           // ADAS_TOR_FAULT_CODE_ID
            535826458 to "noa-gray",            // ADAS_NOA_GRAY_STATE
            535830556 to "noa-quit",            // ADAS_NOA_QUIT_PROMPT
            828375060 to "lks-fault",           // ADAS_LKS_FAULT
        )

        /** Turn signal mask (Leopard 3): 1=off, 2=left, 4=right, 6=hazard; anything else by number. */
        fun turnSide(raw: Int): String = when (raw) {
            1 -> "off"
            2 -> "left"
            4 -> "right"
            6 -> "hazard"
            else -> SentinelDecoder.decodeInt(raw)?.toString() ?: NONE
        }
    }
}
