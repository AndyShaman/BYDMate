package com.bydmate.app.data.push

/** The comparison of the poll against the push channel, printed in the `--- fid push ---` dump. */
internal object FidPushDiagnostics {

    /** One field's push outcome next to the value the poll last decoded for it. */
    data class PollPushRow(
        val field: String,
        val fid: Int,
        /** The daemon accepted the subscription for this fid. */
        val subscribed: Boolean,
        val events: Int,
        /** Last polled value, or null when the poll never produced one (sentinel, unsupported). */
        val pollValue: Any?,
    )

    /** A subscription younger than this has simply not had time to produce an event yet. */
    private const val MIN_AGE_MS = 60_000L

    /** Enough to see the pattern; a full 113-field table would drown the dump. */
    private const val MAX_LINES = 20

    /**
     * The `poll≠push` lines: fields the daemon accepted, that have produced no event at all,
     * while the poll keeps reading a real value for them (maxBatTemp/minBatTemp on the
     * 2026-09-16 drive — subscription ok, events 0, poll fine).
     *
     * Pure: the caller supplies the rows and how long the subscription has been in force.
     */
    fun pollVsPushLines(rows: List<PollPushRow>, sinceSubscribeMs: Long): List<String> {
        if (sinceSubscribeMs < MIN_AGE_MS) return emptyList()
        val since = "${sinceSubscribeMs / 60_000}m"
        return rows.asSequence()
            .filter { it.subscribed && it.events == 0 && it.pollValue != null }
            .take(MAX_LINES)
            .map { "poll≠push: ${it.field}(${it.fid}) poll=${it.pollValue} events=0 since=$since" }
            .toList()
    }
}
