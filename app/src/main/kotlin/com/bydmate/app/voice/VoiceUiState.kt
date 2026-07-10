package com.bydmate.app.voice

sealed interface VoiceUiState {
    data object Idle : VoiceUiState
    data object Listening : VoiceUiState
    data object Thinking : VoiceUiState
    data class Done(val transcript: String) : VoiceUiState
    data class Blocked(val reason: String) : VoiceUiState
    data class NotUnderstood(val transcript: String) : VoiceUiState

    /** Free-form answer from the LLM agent (fallback when no local command matched). */
    data class AgentAnswer(val text: String) : VoiceUiState
}
