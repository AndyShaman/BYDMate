package com.bydmate.app.agent

import org.json.JSONArray

/** LLM backend for the voice agent. Implementations: OpenRouter (BYOK) now, proxy later. */
interface AgentBackend {
    /** Cheap config check (no network): API key and model are set. */
    suspend fun isConfigured(): Boolean
    suspend fun chat(
        messages: List<AgentMessage>,
        tools: JSONArray?,
        onDelta: ((String) -> Unit)? = null,
    ): Result<AgentReply>
}
