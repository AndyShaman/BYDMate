package com.bydmate.app.data.push

import android.os.SystemClock
import com.bydmate.app.data.autoservice.SentinelDecoder
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Trace lines for the car states the push channel carries that nothing else traces: the turn
 * signal, and the ADAS states around the lane change by turn signal — so a drive where the car
 * reported that lane change unavailable can be laid against our own camera use on one time line.
 * Only a change is a line (the first value after start has no `from`); a sentinel reads `none`.
 * The turn signal is one start and one end per use ([TurnSeries]), not a line per blink: it
 * showed the real turn direction in #294, on cars with the blind spots off too.
 *
 * Called on the binder thread that delivers a push packet.
 */
internal class PushStateTrace(
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    // Asks for [TurnSeries.onTimer] once the lamp may have stayed dark long enough.
    private val schedule: (delayMs: Long, block: () -> Unit) -> Unit = { delayMs, block ->
        TIMER_SCOPE.launch { delay(delayMs); block() }
    },
) {

    /** fid → trace name of the ADAS states in the subscription in force; nothing else is traced as one. */
    @Volatile var adas: Map<Int, String> = emptyMap()

    /** Last traced value per fid. */
    private val last = HashMap<Int, String>()

    private val turns = TurnSeries()

    /** [field] is the FidMap field the fid belongs to, null for the ADAS states. */
    @Synchronized
    fun onEvent(fid: Int, field: String?, raw: Int) {
        // One read: the confirmed set can be replaced by a resubscribe between two.
        val name = if (field == null) adas[fid] else null
        val value = when {
            field == TURN_SIGNAL -> turnSide(raw)
            name != null -> SentinelDecoder.decodeInt(raw)?.toString() ?: NONE
            else -> return
        }
        val was = last[fid]
        if (was == value) return
        last[fid] = value
        if (field == TURN_SIGNAL) {
            turns.onValue(value, clock()).forEach(::traceTurn)
            if (value == TurnSeries.OFF || value == NONE) schedule(TurnSeries.END_AFTER_MS) { endTurnIfDark() }
        } else {
            Trace.event(TraceArea.CAR, "adas", "name" to name, "from" to was, "to" to value)
        }
    }

    @Synchronized
    private fun endTurnIfDark() {
        turns.onTimer(clock())?.let(::traceTurn)
    }

    private fun traceTurn(event: TurnSeries.Event) {
        when (event) {
            is TurnSeries.Event.Start -> Trace.event(TraceArea.CAR, "turn", "side" to event.side)
            is TurnSeries.Event.End -> Trace.event(TraceArea.CAR, "turn-end", "side" to event.side,
                "blinks" to event.blinks, "ms" to event.durationMs)
        }
    }

    /** One ADAS state: its Leopard 3 fid, its trace name and its catalog symbol. */
    data class AdasState(val fid: Int, val name: String, val symbol: String)

    companion object {
        private const val TURN_SIGNAL = "turnSignal"
        private const val NONE = TurnSeries.NONE

        private val TIMER_SCOPE = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** ADAS device: the one bsdLeft/bsdRight are pushed from. */
        const val ADAS_DEVICE = 1038

        /**
         * ADAS states subscribed for the trace alone (Leopard 3 BYDAutoFeatureIds; all seven
         * answered a plain read there on 2026-09-29). Not FidMap fields: nothing polls them or
         * patches the snapshot with them. The fid is the Leopard 3 number; the push channel
         * subscribes one only where this car's catalog gives its symbol that same number.
         */
        val ADAS_STATES: List<AdasState> = listOf(
            AdasState(535826452, "lane-change-gray", "Adas.ADAS_INTERACTIVE_LANE_CHANGE_ASSIST_GRAY"),
            AdasState(700448776, "ilca-switch", "Adas.ADAS_ILCA_SWITCH_STATE"),
            AdasState(-1728052359, "domain-disconnect", "Adas.ADAS_DOMAIN_CONTROL_DISCONNECTION_STATUS"),
            AdasState(230686760, "tor-fault", "Adas.ADAS_TOR_FAULT_CODE_ID"),
            AdasState(535826458, "noa-gray", "Adas.ADAS_NOA_GRAY_STATE"),
            AdasState(535830556, "noa-quit", "Adas.ADAS_NOA_QUIT_PROMPT"),
            AdasState(828375060, "lks-fault", "Adas.ADAS_LKS_FAULT"),
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
