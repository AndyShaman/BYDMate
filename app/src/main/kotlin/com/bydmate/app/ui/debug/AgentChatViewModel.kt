package com.bydmate.app.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Debug text chat with the voice agent — same AgentOrchestrator (and session
 *  memory) the PTT path uses, so on-car verification can bypass ASR. */
@HiltViewModel
class AgentChatViewModel @Inject constructor(
    private val orchestrator: AgentOrchestrator,
) : ViewModel() {

    data class ChatLine(val fromUser: Boolean, val text: String)
    data class ChatUiState(
        val lines: List<ChatLine> = emptyList(),
        val busy: Boolean = false,
    )

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || _uiState.value.busy) return
        _uiState.update { it.copy(lines = it.lines + ChatLine(true, trimmed), busy = true) }
        viewModelScope.launch {
            val reply = when (val r = orchestrator.ask(trimmed)) {
                is AgentResult.Answer -> r.text
                AgentResult.Disabled -> "Агент выключен или не настроен (Настройки → Агент)"
                is AgentResult.Error -> "Ошибка: ${r.message}"
            }
            _uiState.update { it.copy(lines = it.lines + ChatLine(false, reply), busy = false) }
        }
    }
}
