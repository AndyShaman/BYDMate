package com.bydmate.app.voice

import com.bydmate.app.agent.AgentTrace
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The «--- voice ---» block of the diagnostic dump: counters over the whole persisted journal,
 *  then one line per session, newest first:
 *  `<time> route=<r> heard="<t>" cmd=<c> reason=<r> result=<r> asr=<ms> dispatch=<ms>`. */
object VoiceJournalDump {
    fun lines(entries: List<VoiceJournalEntry>, limit: Int, answerChars: Int): List<String> {
        if (entries.isEmpty()) return listOf("(no voice sessions)")
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
        val ok = { r: VoiceJournalEntry.Route ->
            entries.count { it.route == r && it.outcome == VoiceJournalEntry.Outcome.OK }
        }
        val out = mutableListOf(
            "sessions=${entries.size} nlu_ok=${ok(VoiceJournalEntry.Route.NLU)}" +
                " automation_ok=${ok(VoiceJournalEntry.Route.AUTOMATION)}" +
                " agent=${entries.count { it.route == VoiceJournalEntry.Route.AGENT }}" +
                " refused=${entries.count { it.route == VoiceJournalEntry.Route.REFUSED }}"
        )
        entries.take(limit).forEach { e ->
            out += "${stamp.format(Date(e.timestampMs))} route=${e.route.code} heard=\"${oneLine(e.transcript)}\"" +
                " cmd=${e.command?.let(::oneLine) ?: "-"} reason=${e.refusal ?: "-"} result=${result(e)}" +
                " asr=${e.asrMs ?: "-"} dispatch=${e.dispatchMs ?: "-"}"
            if (e.tools.isNotEmpty()) {
                out += "  tools: " + e.tools.joinToString(", ") { "${it.name}:${if (it.ok) "ok" else "err"}" }
            }
            e.answer?.let { out += "  answer: " + AgentTrace.clip(oneLine(it), answerChars) }
        }
        return out
    }

    private fun result(e: VoiceJournalEntry): String {
        val text = e.reason?.let { ":\"${oneLine(it)}\"" }.orEmpty()
        return when (e.outcome) {
            VoiceJournalEntry.Outcome.OK -> "ok"
            VoiceJournalEntry.Outcome.BLOCKED -> "blocked$text"
            VoiceJournalEntry.Outcome.ERROR -> "error$text"
            VoiceJournalEntry.Outcome.NOT_UNDERSTOOD -> "not_understood$text"
        }
    }

    /** Keeps one session on one line and its quoted fields unambiguous. */
    private fun oneLine(s: String): String = s.replace('\n', ' ').replace('\r', ' ').replace('"', '\'')
}
