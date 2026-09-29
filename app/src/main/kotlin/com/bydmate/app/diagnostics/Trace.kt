package com.bydmate.app.diagnostics

import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/** Where an event comes from: the first column of a trace line. */
enum class TraceArea {
    USER, VOICE, AGENT, NET, WIDGET, SCREEN, APP, HUD;

    internal val label: String = name.lowercase().padEnd(LABEL_WIDTH)
}

private const val LABEL_WIDTH = 6

/**
 * Process-wide entry point to the [TraceJournal], so an event can be written from anywhere —
 * objects like the widget controller and a11y services included — without threading the
 * journal through every constructor. BYDMateApp installs the journal; until then (and in unit
 * tests that install none, and in the helper daemon's process) every call is a no-op that
 * returns 0, which [event]'s `by` treats as "no cause".
 *
 * Usage: `val id = Trace.event(TraceArea.WIDGET, "hide", "reason" to reason, by = screenId)`.
 * Values go through [TraceSanitizer]; null values are left out.
 */
object Trace {

    @Volatile private var journal: TraceJournal? = null

    fun install(journal: TraceJournal?) {
        this.journal = journal
    }

    /** Records one event; returns its id for a later event's `by`, 0 when nothing recorded it. */
    fun event(area: TraceArea, what: String, vararg fields: Pair<String, Any?>, by: Long? = null): Long =
        journal?.record(area, what, fields, by) ?: 0L

    /** One server call: its host, then `result=ok`, the HTTP `code`, or the `error` ([reason]
     *  when the caller has a fixed one, else the exception class), and its time. The error's
     *  message is read only for an HTTP status. Caused by the event the calling coroutine runs
     *  for ([causedBy]). */
    suspend fun call(what: String, host: String, tookMs: Long, error: Throwable? = null, reason: String? = null): Long {
        val code = error?.message?.let { HTTP_STATUS.find(it) }?.groupValues?.get(1)?.toInt()
        val outcome: Pair<String, Any> = when {
            error == null -> "result" to "ok"
            code != null -> "code" to code
            else -> "error" to (reason ?: error.javaClass.simpleName)
        }
        return event(TraceArea.NET, what, "host" to host, outcome, "ms" to tookMs, by = cause())
    }

    private val HTTP_STATUS = Regex("""\bHTTP (\d{3})\b""")

    /** Model and speech calls are many per turn: only a failed one or one slower than this is
     *  worth a line. */
    const val SLOW_CALL_MS = 5_000L

    /** Writes the pending events to disk now (service stop, app going to the background). */
    fun flush() {
        journal?.flush()
    }

    /** [flush] that waits up to [timeoutMs] for the file write: for a line the process is about to
     *  lose (our own force-stop). Blocks the calling thread; false when nothing confirmed the write. */
    fun flushBlocking(timeoutMs: Long): Boolean = journal?.flushBlocking(timeoutMs) ?: false

    /** The whole journal, oldest first, for the diagnostic dump. */
    suspend fun lines(): List<String> = journal?.lines().orEmpty()

    /** Context element that makes event [id] the cause of what runs inside it: the agent's
     *  tool calls and the HTTP requests below them read it with [cause] instead of taking an
     *  extra parameter through every layer. */
    fun causedBy(id: Long): CoroutineContext = if (id > 0) TraceCause(id) else EmptyCoroutineContext

    suspend fun cause(): Long? = currentCoroutineContext()[TraceCause]?.id
}

/** See [Trace.causedBy]. */
class TraceCause(val id: Long) : AbstractCoroutineContextElement(TraceCause) {
    companion object Key : CoroutineContext.Key<TraceCause>
}
