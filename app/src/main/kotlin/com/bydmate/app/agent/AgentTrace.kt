package com.bydmate.app.agent

import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea

/**
 * Renders one agent turn into log lines: a round of the model, every tool call, and the
 * turn's verdict. Kept out of [AgentOrchestrator] so the loop itself stays readable — the
 * sink is a seam (logcat in production, a list in tests), the formatting is not control flow.
 *
 * Timings are taken from the same clock the orchestrator uses, so a test clock produces
 * deterministic lines.
 */
internal class AgentTrace(
    private val clock: () -> Long,
    private val knownTools: Set<String>,
    private val sink: (String) -> Unit,
) {

    private var roundStart = 0L
    private var firstDeltaAt = 0L
    /** Stop reason of the last model round, so the turn line can name it too. */
    private var lastFinish: String? = null
    // Trace events of the turn and of the tool call in flight (0 between calls).
    private var askEvent = 0L
    private var toolEvent = 0L

    /** The turn starts; [cause] is the event it answers (the driver's phrase), null for a turn
     *  started by an automation. */
    fun started(cause: Long?, detached: Boolean) {
        askEvent = Trace.event(TraceArea.AGENT, "ask-start", "detached" to detached.takeIf { it }, by = cause)
    }

    /** A tool call starts; returns its trace event, the cause of the server calls it makes.
     *  [call.name] comes from the model unchecked, so a name it invented must not reach the
     *  trace as-is: only a name from [knownTools] (the schemas offered this turn) is recorded. */
    fun toolStarted(call: AgentToolCall): Long {
        toolEvent = Trace.event(TraceArea.AGENT, "tool-start", "name" to traceName(call), by = askEvent)
        return toolEvent
    }

    /** New model round: resets the round clock and the time-to-first-token mark. */
    fun roundStarted() {
        roundStart = clock()
        firstDeltaAt = 0L
    }

    /** A streamed content delta arrived; only the first one of a round is kept (TTFT). */
    fun delta() {
        if (firstDeltaAt == 0L) firstDeltaAt = clock()
    }

    /** One line per model round: what came back, how many tool calls, time to first token
     *  (-1 when the round was not streamed) and the round's wall time. */
    fun reply(reply: AgentReply) {
        lastFinish = reply.finishReason
        val ttft = if (firstDeltaAt == 0L) -1L else firstDeltaAt - roundStart
        sink(
            "reply text=\"${clip(reply.content)}\" tool_calls=${reply.toolCalls.size} " +
                "finish=${reply.finishReason ?: "-"} ttft=${ttft}ms round=${clock() - roundStart}ms"
        )
    }

    fun replyFailed(message: String) {
        sink("reply failed round=${clock() - roundStart}ms error=${clip(message)}")
    }

    /** The filler spoken while a slow tool call (web search, weather, ...) is in flight. */
    fun filler(phrase: String, toolName: String) {
        sink("filler: \"$phrase\" tool=$toolName")
    }

    /** One line per tool call: name, arguments and verdict, so a "said done, did nothing"
     *  report can be read straight out of the user's log. where_am_i's result carries settlement
     *  names and distances close to the car, so only its length is traced, e.g.
     *  "<123 chars, redacted>" - unless it is an error: those are fixed texts with no place in
     *  them. Every other tool keeps its full result. */
    fun tool(call: AgentToolCall, verdict: String, tookMs: Long, result: String) {
        val redacted = call.name == "where_am_i" && verdict != "error"
        val shownResult = if (redacted) "<${result.length} chars, redacted>" else clip(result)
        sink("tool ${call.name} args=${clip(call.arguments)} -> $verdict ${tookMs}ms result=$shownResult")
        Trace.event(TraceArea.AGENT, "tool", "name" to traceName(call), "verdict" to verdict, "ms" to tookMs,
            "code" to if (verdict == "error") AgentToolErrorCode.of(result) else null,
            by = toolEvent.takeIf { it > 0 } ?: askEvent)
        toolEvent = 0L
    }

    fun turn(totalMs: Long, rounds: Int, outcome: String) {
        sink("turn done total=${totalMs}ms rounds=$rounds finish=${lastFinish ?: "-"} outcome=$outcome")
        Trace.event(TraceArea.AGENT, "ask-end", "outcome" to outcome.substringBefore(':'), "rounds" to rounds,
            "finish" to lastFinish, "ms" to totalMs, by = askEvent)
    }

    /** [call.name] if it is one of the tools offered this turn, else the literal "unknown". */
    private fun traceName(call: AgentToolCall): String = call.name.takeIf { it in knownTools } ?: "unknown"

    companion object {
        /** Trace lines quote model/tool payloads, which can be arbitrarily long. */
        const val TRACE_CHARS = 200

        /** Single-line, length-capped rendering of a payload for a trace/journal line. */
        fun clip(text: String?, max: Int = TRACE_CHARS): String {
            val flat = text.orEmpty().replace('\n', ' ').trim()
            return if (flat.length <= max) flat else flat.take(max) + "…"
        }
    }
}
