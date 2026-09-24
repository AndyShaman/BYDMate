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
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
 *  restarts. File work never runs on the caller's thread: the file is read once (queued on
 *  creation) and rewritten after changes, both on one background thread ([executor], a
 *  dedicated single thread by default) where queued writes coalesce into one write of the
 *  latest list. Null [file] = memory only. */
@Singleton
class VoiceJournal(private val file: File? = null, executor: Executor? = null) {
    @Inject constructor(@ApplicationContext context: Context) : this(File(context.filesDir, FILE_NAME))

    private val io: Executor by lazy {
        executor ?: Executors.newSingleThreadExecutor { r -> Thread(r, "voice-journal").apply { isDaemon = true } }
    }
    private val lock = Any()
    private val loadRequested = AtomicBoolean(false)
    private val writeQueued = AtomicBoolean(false)
    // Set by clear() before the file was read: the old sessions must not come back.
    private var discardFile = false
    private val _entries = MutableStateFlow<List<VoiceJournalEntry>>(emptyList())
    val entries: StateFlow<List<VoiceJournalEntry>> = _entries.asStateFlow()

    // First use is creation (the singleton is built when first injected): the read is only
    // queued here, the caller never waits for the disk.
    init { ensureLoaded() }

    fun add(e: VoiceJournalEntry) {
        synchronized(lock) { _entries.value = (listOf(bounded(e)) + _entries.value).take(MAX) }
        scheduleWrite()
    }

    fun clear() {
        synchronized(lock) {
            discardFile = true
            _entries.value = emptyList()
        }
        scheduleWrite()
    }

    /** Queues the one read of the file ahead of every write, so a write never replaces
     *  sessions it has not seen. Sessions added meanwhile are newer and stay first. */
    private fun ensureLoaded() {
        val f = file ?: return
        if (!loadRequested.compareAndSet(false, true)) return
        runCatching {
            io.execute {
                val stored = load(f)
                synchronized(lock) {
                    if (!discardFile) _entries.value = (_entries.value + stored).take(MAX)
                }
            }
        }.onFailure {
            loadRequested.set(false)
            Log.w(TAG, "voice journal read not scheduled: ${it.message}")
        }
    }

    /** One pending write at a time; it saves whatever the list is when it runs. */
    private fun scheduleWrite() {
        val f = file ?: return
        if (!writeQueued.compareAndSet(false, true)) return
        runCatching {
            io.execute {
                writeQueued.set(false)
                persist(f, _entries.value)
            }
        }.onFailure {
            writeQueued.set(false)
            Log.w(TAG, "voice journal write not scheduled: ${it.message}")
        }
    }

    private fun load(f: File): List<VoiceJournalEntry> =
        runCatching {
            if (!f.isFile) return@runCatching emptyList()
            val size = f.length()
            if (size > MAX_FILE_BYTES) {
                Log.w(TAG, "voice journal is $size bytes, over $MAX_FILE_BYTES: starting empty")
                return@runCatching emptyList()
            }
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { fromJson(arr.getJSONObject(it))?.let(::bounded) }.take(MAX)
        }.onFailure { Log.w(TAG, "voice journal unreadable, starting empty: ${it.message}") }
            .getOrDefault(emptyList())

    /** Per-field character bounds keep the serialised size close to but not under [MAX_FILE_BYTES]
     *  (JSON escaping, e.g. control characters as \\uXXXX, can inflate a bounded field well past
     *  its character count). Bound by the actual serialised size instead: drop the oldest entry
     *  and re-serialise until it fits, so a file this journal wrote is never one the loader rejects. */
    private fun persist(f: File, list: List<VoiceJournalEntry>) {
        runCatching {
            var kept = list
            var json = JSONArray(kept.map { toJson(it) }).toString()
            while (json.toByteArray(Charsets.UTF_8).size.toLong() > MAX_FILE_BYTES && kept.size > 1) {
                kept = kept.dropLast(1)
                json = JSONArray(kept.map { toJson(it) }).toString()
            }
            val tmp = File(f.parentFile, "${f.name}.tmp")
            tmp.writeText(json)
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

        /** A larger file is not this journal's (50 bounded sessions, at most ~5.5K chars of text
         *  each, up to 3 UTF-8 bytes a char): skipped, not parsed. */
        const val MAX_FILE_BYTES = 1024L * 1024
        /** Longest transcript, command or reason kept per session. */
        const val MAX_FIELD_CHARS = 500
        /** Longest detail or answer kept per session (an agent answer lands in both). */
        const val MAX_TEXT_CHARS = 2000
        /** Longest tool list kept per session, and longest name per tool. */
        const val MAX_TOOLS = 20
        const val MAX_TOOL_NAME_CHARS = 100

        internal fun bounded(e: VoiceJournalEntry): VoiceJournalEntry = e.copy(
            transcript = e.transcript.take(MAX_FIELD_CHARS),
            command = e.command?.take(MAX_FIELD_CHARS),
            reason = e.reason?.take(MAX_FIELD_CHARS),
            detail = e.detail.take(MAX_TEXT_CHARS),
            answer = e.answer?.take(MAX_TEXT_CHARS),
            tools = e.tools.take(MAX_TOOLS).map { it.copy(name = it.name.take(MAX_TOOL_NAME_CHARS)) },
            refusal = e.refusal?.take(MAX_FIELD_CHARS),
        )

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
