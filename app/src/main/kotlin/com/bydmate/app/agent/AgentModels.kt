package com.bydmate.app.agent

/** One tool invocation requested by the model. [arguments] is the raw JSON string. */
data class AgentToolCall(val id: String, val name: String, val arguments: String)

/** Chat message in OpenRouter wire vocabulary. */
sealed interface AgentMessage {
    data class System(val content: String) : AgentMessage
    data class User(val content: String) : AgentMessage
    data class Assistant(val content: String?, val toolCalls: List<AgentToolCall> = emptyList()) : AgentMessage
    data class Tool(val toolCallId: String, val content: String) : AgentMessage
}

/** One model turn: final text and/or requested tool calls. */
data class AgentReply(val content: String?, val toolCalls: List<AgentToolCall>)

/** Outcome of one tool call within an agent turn — journal/observability only. */
data class AgentToolOutcome(val name: String, val ok: Boolean)

/** Outcome of one user request to the agent. */
sealed interface AgentResult {
    data class Answer(val text: String, val tools: List<AgentToolOutcome> = emptyList()) : AgentResult
    /** Feature off or not configured — caller degrades to the pre-agent behaviour. */
    data object Disabled : AgentResult
    data class Error(val message: String) : AgentResult
}
