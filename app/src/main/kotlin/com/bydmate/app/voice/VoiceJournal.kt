package com.bydmate.app.voice

import android.content.Context
import android.util.Log
import com.bydmate.app.agent.AgentToolOutcome
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** One completed voice session, recorded for the «Журнал голоса» debug screen and the dump:
 *  what ASR heard, what it became and, if nothing, why. */
data class VoiceJournalEntry(
    val timestampMs: Long = System.currentTimeMillis(),
    val transcript: String,        // what ASR heard ("" if nothing)
    val route: Route,              // NLU / AUTOMATION / AGENT / REFUSED
    val detail: String,            // resolved command id or agent answer summary (NO Chinese: use displayable text)
    val outcome: Outcome,          // OK / BLOCKED / NOT_UNDERSTOOD / ERROR
    val reason: String? = null,    // block/error reason (Russian)
    // Agent turns only: which tools ran (in order, with their ok/error verdict) and the
    // spoken answer. Structured rather than baked into [detail] so the diagnostic dump can
    // print them apart — a "the agent said done but nothing happened" report is judged by
    // exactly this pair.
    val tools: List<AgentToolOutcome> = emptyList(),
    val answer: String? = null,
    val command: String? = null,   // readable command id(s) or the automation's name
    val refusal: String? = null,   // stable code, see VoiceRefusal
    val asrMs: Long? = null,       // decode latency of the utterance
    val dispatchMs: Long? = null,  // from the start of routing to this outcome
) {
    enum class Route {
        NLU, AUTOMATION, AGENT, REFUSED;

        val code: String get() = name.lowercase()
    }
    enum class Outcome { OK, BLOCKED, NOT_UNDERSTOOD, ERROR }
}

/** Ring buffer of the last MAX voice sessions, newest first. With a [file] it survives process
 *  restarts: loaded once on creation, rewritten on every change. Null [file] = memory only. */
@Singleton
class VoiceJournal(private val file: File? = null) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private val lock = Any()
    private val _entries = MutableStateFlow(load())
    val entries: StateFlow<List<VoiceJournalEntry>> = _entries.asStateFlow()

    fun add(e: VoiceJournalEntry) = synchronized(lock) {
        _entries.value = (listOf(e) + _entries.value).take(MAX)
        persist(_entries.value)
    }

    fun clear() = synchronized(lock) {
        _entries.value = emptyList()
        persist(emptyList())
    }

    private fun load(): List<VoiceJournalEntry> {
        val f = file ?: return emptyList()
        if (!f.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it)) }.take(MAX)
        }.onFailure { Log.w(TAG, "voice journal unreadable, starting empty: ${it.message}") }
            .getOrDefault(emptyList())
    }

    private fun persist(list: List<VoiceJournalEntry>) {
        val f = file ?: return
        runCatching {
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(JSONArray(list.map { toJson(it) }).toString())
            if (!tmp.renameTo(f)) {
                f.writeText(tmp.readText())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "voice journal not saved: ${it.message}") }
    }

    companion object {
        const val MAX = 50
        const val FILE_NAME = "voice_journal.json"
        private const val TAG = "VoiceJournal"

        internal fun toJson(e: VoiceJournalEntry): JSONObject = JSONObject().apply {
            put("t", e.timestampMs)
            put("heard", e.transcript)
            put("route", e.route.name)
            put("detail", e.detail)
            put("outcome", e.outcome.name)
            put("reason", e.reason ?: JSONObject.NULL)
            put("tools", JSONArray(e.tools.map { JSONObject().put("name", it.name).put("ok", it.ok) }))
            put("answer", e.answer ?: JSONObject.NULL)
            put("cmd", e.command ?: JSONObject.NULL)
            put("refusal", e.refusal ?: JSONObject.NULL)
            put("asr", e.asrMs ?: JSONObject.NULL)
            put("dispatch", e.dispatchMs ?: JSONObject.NULL)
        }

        /** Null for an entry this build cannot read (e.g. a route a newer build wrote). */
        internal fun fromJson(o: JSONObject): VoiceJournalEntry? {
            val route = VoiceJournalEntry.Route.entries.firstOrNull { it.name == o.optString("route") } ?: return null
            val outcome = VoiceJournalEntry.Outcome.entries.firstOrNull { it.name == o.optString("outcome") } ?: return null
            val tools = o.optJSONArray("tools")
            return VoiceJournalEntry(
                timestampMs = o.optLong("t"),
                transcript = o.optString("heard"),
                route = route,
                detail = o.optString("detail"),
                outcome = outcome,
                reason = o.stringOrNull("reason"),
                tools = if (tools == null) emptyList() else (0 until tools.length()).map {
                    val t = tools.getJSONObject(it)
                    AgentToolOutcome(t.optString("name"), t.optBoolean("ok"))
                },
                answer = o.stringOrNull("answer"),
                command = o.stringOrNull("cmd"),
                refusal = o.stringOrNull("refusal"),
                asrMs = if (o.isNull("asr")) null else o.optLong("asr"),
                dispatchMs = if (o.isNull("dispatch")) null else o.optLong("dispatch"),
            )
        }

        private fun JSONObject.stringOrNull(key: String): String? = if (isNull(key)) null else optString(key)
    }
}
