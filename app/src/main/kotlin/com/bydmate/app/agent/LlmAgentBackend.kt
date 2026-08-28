package com.bydmate.app.agent

import android.util.Log
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
 * Both extra attempts are budgeted from the start of the turn ([RETRY_BUDGET_MS] /
 * [FALLBACK_BUDGET_MS]): an attempt that already burned its call timeout means a dead
 * network, and the driver must hear an honest failure instead of minutes of silence.
 */
@Singleton
class LlmAgentBackend @Inject constructor(
    private val client: OpenRouterClient,
    private val connections: LlmConnectionResolver,
) : AgentBackend {

    /** Test seam — deterministic clock for the retry/fallback budgets. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    override suspend fun isConfigured(): Boolean = connections.primary() != null

    override suspend fun chat(
        messages: List<AgentMessage>,
        tools: JSONArray?,
        onDelta: ((String) -> Unit)?,
    ): Result<AgentReply> {
        val primary = connections.primary()
            ?: return Result.failure(LlmError("Агент не настроен: заполните адрес, API-ключ и модель в Настройки, Интеграции"))
        val wire = toWire(messages)
        var forwarded = false
        val guarded: ((String) -> Unit)? = onDelta?.let { cb -> { d -> forwarded = true; cb(d) } }

        val startedAt = nowMs()

        // The provider extras (reasoning off etc.) are a latency optimisation, not a
        // requirement: a model or upstream that rejects them with 400 gets the plain request
        // once, so a field the provider does not know can never silence the agent.
        suspend fun attempt(conn: LlmConnection): Result<AgentReply> {
            val first = call(conn, wire, tools, guarded, withExtras = true)
            if (first.isSuccess || forwarded || !rejectedExtras(conn, first, guarded != null)) return first
            Log.w(TAG, "provider ${conn.id} rejected request extras (HTTP 400), retrying plain")
            return call(conn, wire, tools, guarded, withExtras = false)
        }

        var result = attempt(primary)
        if (result.isSuccess) return result
        // Once a delta reached the caller the user has heard the beginning: replaying the
        // request (retry or fallback) would speak it twice. Fail fast instead.
        if (forwarded) return interrupted(result)
        if (isTransient(result.exceptionOrNull()) && nowMs() - startedAt < RETRY_BUDGET_MS) {
            result = attempt(primary)
            if (result.isSuccess) return result
            if (forwarded) return interrupted(result)
        }
        val fallback = connections.fallback()
        if (fallback != null && nowMs() - startedAt < FALLBACK_BUDGET_MS) {
            result = attempt(fallback)
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
        withExtras: Boolean,
    ): Result<AgentReply> = (
        if (onDelta != null) client.chatStream(
            conn.baseUrl, conn.apiKey, conn.model, wire, tools,
            if (withExtras) providerExtras(conn, streaming = true) else null, onDelta,
        )
        else client.chatRaw(
            conn.baseUrl, conn.apiKey, conn.model, wire, tools,
            if (withExtras) providerExtras(conn, streaming = false) else null,
        )
    ).map { parseReply(it) }

    private fun rejectedExtras(conn: LlmConnection, result: Result<AgentReply>, streaming: Boolean): Boolean {
        val e = result.exceptionOrNull()
        return e is LlmHttpException && e.code == 400 && providerExtras(conn, streaming) != null
    }

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
        private const val TAG = "LlmAgentBackend"
        /** A retry only pays off while the turn is still young; past this the network is the
         *  problem, not the request. */
        internal const val RETRY_BUDGET_MS = 10_000L
        /** Hard cap for starting a fallback attempt, counted from the start of the turn. */
        internal const val FALLBACK_BUDGET_MS = 20_000L

        /** Provider-specific payload fields that cut latency: reasoning off where the provider
         *  supports switching it off, usage stats in the stream to check prompt caching in the
         *  field. A custom endpoint speaks an unknown dialect — send it nothing extra. */
        internal fun providerExtras(conn: LlmConnection, streaming: Boolean): JSONObject? = when (conn.id) {
            // "none" is rejected by models with mandatory reasoning (Gemini 3 Flash), "minimal" is not.
            LlmConnectionResolver.ID_OPENROUTER -> JSONObject()
                .put("reasoning", JSONObject().put("effort", "minimal").put("exclude", true))
                // stream_options is only legal alongside stream=true; a non-streaming request
                // carrying it is rejected as an invalid request by OpenAI-compatible endpoints.
                .also { if (streaming) it.put("stream_options", JSONObject().put("include_usage", true)) }
            LlmConnectionResolver.ID_ZAI -> JSONObject()
                .put("thinking", JSONObject().put("type", "disabled"))
            else -> null
        }

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
