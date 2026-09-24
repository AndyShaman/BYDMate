package com.bydmate.app.voice

/** Stable codes for why a voice session did not become a command. They appear verbatim in the
 *  voice journal, the dump and logcat, so issue reports can be grepped by them. */
object VoiceRefusal {
    const val UNRECOGNIZED = "unrecognized"
    const val ASR_EMPTY = "asr_empty"
    const val ASR_FAILED = "asr_failed"
    const val MODEL_MISSING = "asr_model_missing"
    const val ECHO = "echo"
    const val AGENT_FOLLOWUP_WINDOW = "agent_followup_window"
    const val BARGE_IN = "barge_in"
    const val HARD_STOP = "hard_stop"
    const val DISPATCH_FAILED = "dispatch_failed"
    const val RULE_NOT_FOUND = "rule_not_found"
    const val INTERNAL_ERROR = "internal_error"

    /** A safety gate held the command back, e.g. `gate:speed_unknown`. */
    fun gate(name: String): String = "gate:$name"
}

/** What the built-in parser made of a phrase: a command to run, or a refusal with its code. */
sealed interface NluOutcome {
    data class Understood(val result: ParseResult) : NluOutcome
    data class Refused(val reason: String) : NluOutcome

    companion object {
        fun of(result: ParseResult): NluOutcome = when (result) {
            // The parser does not say why it refused yet; when it reports codes such as
            // unknown_measure or multiple_commands, they belong here instead of the generic one.
            ParseResult.Unrecognized -> Refused(VoiceRefusal.UNRECOGNIZED)
            else -> Understood(result)
        }
    }
}
