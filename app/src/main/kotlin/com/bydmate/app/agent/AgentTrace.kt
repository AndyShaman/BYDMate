package com.bydmate.app.agent

/**
 * Renders one agent turn into log lines: a round of the model, every tool call, and the
 * turn's verdict. Kept out of [AgentOrchestrator] so the loop itself stays readable — the
 * sink is a seam (logcat in production, a list in tests), the formatting is not control flow.
 *
 * Timings are taken from the same clock the orchestrator uses, so a test clock produces
 * deterministic lines.
 */
internal class AgentTrace(private val clock: () -> Long, private val sink: (String) -> Unit) {

    private var roundStart = 0L
    private var firstDeltaAt = 0L
    /** Stop reason of the last model round, so the turn line can name it too. */
    private var lastFinish: String? = null

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

    /** One line per tool call: name, arguments and verdict, so a "said done, did nothing"
     *  report can be read straight out of the user's log. */
    fun tool(call: AgentToolCall, verdict: String, tookMs: Long, result: String) {
        sink("tool ${call.name} args=${clip(call.arguments)} -> $verdict ${tookMs}ms result=${clip(result)}")
    }

    fun turn(totalMs: Long, rounds: Int, outcome: String) {
        sink("turn done total=${totalMs}ms rounds=$rounds finish=${lastFinish ?: "-"} outcome=$outcome")
    }

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
