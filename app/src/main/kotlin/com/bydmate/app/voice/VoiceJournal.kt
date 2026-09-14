package com.bydmate.app.voice

import com.bydmate.app.agent.AgentToolOutcome
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/** One completed voice session, recorded for the «Журнал голоса» debug screen —
 *  the only place the pipeline's outcome is visible beyond the earcon. */
data class VoiceJournalEntry(
    val timestampMs: Long,
    val transcript: String,        // what ASR heard ("" if nothing)
    val route: Route,              // NLU / AGENT / NONE
    val detail: String,            // resolved command id or agent answer summary (NO Chinese: use displayable text)
    val outcome: Outcome,          // OK / BLOCKED / NOT_UNDERSTOOD / ERROR
    val reason: String? = null,    // block/error reason (Russian)
    // Agent turns only: which tools ran (in order, with their ok/error verdict) and the
    // spoken answer. Structured rather than baked into [detail] so the diagnostic dump can
    // print them apart — a "the agent said done but nothing happened" report is judged by
    // exactly this pair.
    val tools: List<AgentToolOutcome> = emptyList(),
    val answer: String? = null,
) {
    enum class Route { NLU, AGENT, NONE }
    enum class Outcome { OK, BLOCKED, NOT_UNDERSTOOD, ERROR }
}

/** In-memory ring buffer of the last MAX voice sessions. Process-lifetime, never persisted. */
@Singleton
class VoiceJournal @Inject constructor() {
    private val _entries = MutableStateFlow<List<VoiceJournalEntry>>(emptyList())
    val entries: StateFlow<List<VoiceJournalEntry>> = _entries.asStateFlow()
    fun add(e: VoiceJournalEntry) { _entries.update { (listOf(e) + it).take(MAX) } }
    fun clear() { _entries.value = emptyList() }
    companion object { const val MAX = 50 }
}
