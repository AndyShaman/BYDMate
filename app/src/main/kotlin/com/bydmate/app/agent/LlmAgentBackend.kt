package com.bydmate.app.agent

import com.bydmate.app.data.remote.LlmHttpException
import com.bydmate.app.data.remote.OpenRouterClient
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** Backend failure with a short Russian message ready to be voiced to the driver. */
class LlmError(val userMessage: String, cause: Throwable? = null) : Exception(userMessage, cause)

/**
 * OpenAI-compatible [AgentBackend] over configurable connections (OpenRouter / z.ai /
 * custom). The primary connection is retried once on transient failures (timeout, 429,
 * 5xx); any remaining failure hands over to the fallback connection when one is set.
 */
@Singleton
class LlmAgentBackend @Inject constructor(
    private val client: OpenRouterClient,
    private val connections: LlmConnectionResolver,
) : AgentBackend {

    override suspend fun isConfigured(): Boolean = connections.primary() != null

    override suspend fun chat(
        messages: List<AgentMessage>,
        tools: JSONArray?,
        onDelta: ((String) -> Unit)?,
    ): Result<AgentReply> {
        val primary = connections.primary()
            ?: return Result.failure(LlmError("Агент не настроен: нужен API-ключ и модель"))
        val wire = toWire(messages)
        var forwarded = false
        val guarded: ((String) -> Unit)? = onDelta?.let { cb -> { d -> forwarded = true; cb(d) } }

        var result = call(primary, wire, tools, guarded)
        if (result.isSuccess) return result
        // Once a delta reached the caller the user has heard the beginning: replaying the
        // request (retry or fallback) would speak it twice. Fail fast instead.
        if (forwarded) return interrupted(result)
        if (isTransient(result.exceptionOrNull())) {
            result = call(primary, wire, tools, guarded)
            if (result.isSuccess) return result
            if (forwarded) return interrupted(result)
        }
        val fallback = connections.fallback()
        if (fallback != null) {
            result = call(fallback, wire, tools, guarded)
            if (result.isSuccess) return result
            if (forwarded) return interrupted(result)
        }
        val cause = result.exceptionOrNull()
        return Result.failure(LlmError(userMessage(cause), cause))
    }

    private fun interrupted(result: Result<AgentReply>): Result<AgentReply> =
        Result.failure(LlmError("Ответ оборвался, попробуй ещё раз", result.exceptionOrNull()))

    private suspend fun call(
        conn: LlmConnection,
        wire: JSONArray,
        tools: JSONArray?,
        onDelta: ((String) -> Unit)?,
    ): Result<AgentReply> = (
        if (onDelta != null) client.chatStream(conn.baseUrl, conn.apiKey, conn.model, wire, tools, onDelta)
        else client.chatRaw(conn.baseUrl, conn.apiKey, conn.model, wire, tools)
    ).map { parseReply(it) }

    private fun isTransient(e: Throwable?): Boolean = when {
        e is LlmHttpException -> e.code == 429 || e.code >= 500
        e is java.io.IOException -> true
        else -> false
    }

    private fun userMessage(e: Throwable?): String = when {
        e is LlmHttpException && (e.code == 401 || e.code == 403) ->
            "Ключ подключения не подходит, проверь настройки"
        e is LlmHttpException && e.code == 429 -> "Лимит запросов исчерпан, попробуй позже"
        e is LlmHttpException && e.code >= 500 -> "Сервер модели недоступен, попробуй позже"
        else -> "Нет связи с сервером, скажи простую команду"
    }

    companion object {
        /** OpenRouter wire encoding of the message history. */
        internal fun toWire(messages: List<AgentMessage>): JSONArray = JSONArray().apply {
            messages.forEach { m ->
                put(JSONObject().apply {
                    when (m) {
                        is AgentMessage.System -> { put("role", "system"); put("content", m.content) }
                        is AgentMessage.User -> { put("role", "user"); put("content", m.content) }
                        is AgentMessage.Assistant -> {
                            put("role", "assistant")
                            put("content", m.content ?: JSONObject.NULL)
                            if (m.toolCalls.isNotEmpty()) {
                                put("tool_calls", JSONArray().apply {
                                    m.toolCalls.forEach { tc ->
                                        put(JSONObject().apply {
                                            put("id", tc.id)
                                            put("type", "function")
                                            put("function", JSONObject().apply {
                                                put("name", tc.name)
                                                put("arguments", tc.arguments)
                                            })
                                        })
                                    }
                                })
                            }
                        }
                        is AgentMessage.Tool -> {
                            put("role", "tool")
                            put("tool_call_id", m.toolCallId)
                            put("content", m.content)
                        }
                    }
                })
            }
        }

        /** Parses choices[0].message into [AgentReply]. */
        internal fun parseReply(message: JSONObject): AgentReply {
            val content = if (message.isNull("content")) null
                else message.optString("content").takeIf { it.isNotBlank() }
            val calls = mutableListOf<AgentToolCall>()
            message.optJSONArray("tool_calls")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val tc = arr.getJSONObject(i)
                    val fn = tc.getJSONObject("function")
                    calls += AgentToolCall(
                        id = tc.optString("id", "call_$i"),
                        name = fn.getString("name"),
                        arguments = fn.optString("arguments", "{}"),
                    )
                }
            }
            return AgentReply(content, calls)
        }
    }
}
