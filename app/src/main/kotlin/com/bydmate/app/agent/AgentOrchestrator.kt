package com.bydmate.app.agent

import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.voice.AgentIdentity
import com.bydmate.app.voice.AgentPersona
import com.bydmate.app.voice.AgentPersonaPrompt
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Agent loop: user text -> LLM (with tool schemas) -> execute tool calls ->
 * feed results back -> final text answer. Holds a short conversation memory
 * so follow-ups ("а закрой их") keep context: history is cleared [SESSION_TTL_MS]
 * after the last answer and trimmed to [MAX_HISTORY] messages. Single-flight
 * via Mutex — concurrent PTT + chat asks serialize, history stays consistent.
 */
@Singleton
class AgentOrchestrator @Inject constructor(
    private val backend: AgentBackend,
    private val tools: AgentTools,
    private val settingsRepository: SettingsRepository,
    private val isMoving: () -> Boolean = { false },
    private val identity: () -> AgentIdentity = { AgentIdentity("", AgentPersona.NAVIGATOR) },
) {
    /** Test seam — deterministic clock for the session TTL. */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }

    private val mutex = Mutex()
    private val history = mutableListOf<AgentMessage>()
    private var lastAnswerAt = 0L

    suspend fun ask(userText: String, onSentence: ((String) -> Unit)? = null): AgentResult {
        mutex.withLock {
            if (!settingsRepository.isAgentEnabled()) {
                // Close the follow-up window: a disabled/unconfigured agent must not swallow NLU traffic.
                lastAnswerAt = 0L
                return AgentResult.Disabled
            }
            if (!backend.isConfigured()) {
                // Close the follow-up window: a disabled/unconfigured agent must not swallow NLU traffic.
                lastAnswerAt = 0L
                return AgentResult.Error("Агент не настроен: нужен API-ключ и модель")
            }
            val text = userText.trim()
            // Blank transcript: nothing to ask — degrade like a disabled agent.
            if (text.isEmpty()) return AgentResult.Disabled

            clearStaleHistory()
            val snapshot = history.toList()
            history += AgentMessage.User(text)
            try {
                trimHistory()
                val outcomes = mutableListOf<AgentToolOutcome>()
                val callCounts = mutableMapOf<String, Int>()
                var loopStrikes = 0

                val toolSchemas = tools.schemas()
                // Terse mode while driving: same system prompt plus one line asking for a single
                // short confirmation instead of the usual 1-2 sentences.
                val systemPrompt = SYSTEM_PROMPT +
                    "\nСегодня " + SimpleDateFormat("d MMMM yyyy 'года,' EEEE", Locale("ru"))
                        .format(Date(nowMs())) + "." +
                    AgentPersonaPrompt.block(identity()) +
                    if (isMoving()) "\nМашина сейчас движется: отвечай максимально коротко, одним подтверждением." else ""
                repeat(MAX_ITERATIONS) {
                    // Fresh chunker per LLM turn: a tool round's unterminated tail is discarded when
                    // the chunker falls out of scope at the end of this iteration (only completed
                    // sentences were forwarded); the final turn flushes its tail below.
                    val chunker = if (onSentence != null) SentenceChunker() else null
                    val onDelta: ((String) -> Unit)? = if (onSentence != null && chunker != null) {
                        { d -> chunker.feed(d).forEach(onSentence) }
                    } else null
                    val reply = backend
                        .chat(listOf(AgentMessage.System(systemPrompt)) + history, toolSchemas, onDelta)
                        .getOrElse {
                            return AgentResult.Error(
                                (it as? LlmError)?.userMessage ?: "Нет связи с сервером, скажи простую команду"
                            )
                        }

                    if (reply.toolCalls.isEmpty()) {
                        val answer = reply.content?.trim().orEmpty()
                        if (answer.isEmpty()) return AgentResult.Error("Пустой ответ модели")
                        if (onSentence != null) chunker?.flush()?.let(onSentence)
                        history += AgentMessage.Assistant(answer)
                        lastAnswerAt = nowMs()
                        return AgentResult.Answer(answer, outcomes.toList())
                    }

                    history += AgentMessage.Assistant(reply.content, reply.toolCalls)
                    for ((i, call) in reply.toolCalls.withIndex()) {
                        val key = call.name + "|" + call.arguments
                        val seen = callCounts.getOrDefault(key, 0)
                        if (seen >= MAX_IDENTICAL_CALLS) {
                            // Loop guard: the model re-requests an identical call; feed it a synthetic
                            // error instead of executing, and give up after MAX_LOOP_STRIKES rounds.
                            loopStrikes++
                            outcomes += AgentToolOutcome(call.name, false)
                            history += AgentMessage.Tool(call.id, LOOP_ERROR)
                            if (loopStrikes >= MAX_LOOP_STRIKES) {
                                // The Assistant message just appended above carries every tool_call in
                                // this round; close out any calls after this one too, or the next ask()
                                // would replay an Assistant tool_call with no paired Tool message and
                                // the backend would reject it.
                                for (rest in reply.toolCalls.subList(i + 1, reply.toolCalls.size)) {
                                    history += AgentMessage.Tool(rest.id, LOOP_ERROR)
                                }
                                lastAnswerAt = nowMs()
                                return AgentResult.Error("Модель зациклилась, попробуй переформулировать")
                            }
                            continue
                        }
                        callCounts[key] = seen + 1
                        val res = tools.execute(call)
                        // ok = the tool JSON has no "error" key; unparseable output counts as ok
                        // (free-form success payloads like web_search results are not errors).
                        val ok = runCatching { !JSONObject(res).has("error") }.getOrDefault(true)
                        outcomes += AgentToolOutcome(call.name, ok)
                        history += AgentMessage.Tool(call.id, res)
                    }
                }
                lastAnswerAt = nowMs()
                return AgentResult.Error("Слишком длинная цепочка инструментов")
            } catch (ce: kotlin.coroutines.cancellation.CancellationException) {
                // Roll history back to the entry snapshot: a cancelled turn must not leave an
                // unpaired tool_calls Assistant (the next ask would be rejected by the provider).
                history.clear()
                history.addAll(snapshot)
                throw ce
            }
        }
    }

    /** Fast-path (NLU) commands execute without ever going through [ask], so the agent's memory
     *  never learns about them — a follow-up like "а теперь закрой" would then miss the "открой
     *  окно" it refers to. Call this right after a successful fast-path dispatch to backfill
     *  history with a synthetic note and refresh the TTL so the note (and the follow-up) survive. */
    suspend fun noteAction(text: String) {
        mutex.withLock {
            clearStaleHistory()
            history += AgentMessage.Assistant("[выполнено: $text]")
            trimHistory()
            lastAnswerAt = nowMs()
        }
    }

    /** True while the agent's LAST reply was a clarifying question ("Какое окно?")
     *  asked within [FOLLOW_UP_WINDOW_MS]: the driver's next utterance is the ANSWER
     *  and must go straight to [ask], not through NLU (which would turn "водителя"
     *  into a seat command or a NotUnderstood and lose the pending question). */
    suspend fun expectsFollowUp(): Boolean = mutex.withLock {
        if (nowMs() - lastAnswerAt > FOLLOW_UP_WINDOW_MS) return false
        val last = history.lastOrNull() as? AgentMessage.Assistant ?: return false
        last.toolCalls.isEmpty() && last.content?.trimEnd()?.endsWith("?") == true
    }

    /** Shared by [ask] and [noteAction] (both run under [mutex]) so they can't drift: a history
     *  older than [SESSION_TTL_MS] is stale and must be dropped before either appends to it —
     *  otherwise a fast-path note after the TTL expired would both resurrect the old conversation
     *  and refresh [lastAnswerAt], hiding the staleness from the next [ask]. */
    private fun clearStaleHistory() {
        if (nowMs() - lastAnswerAt > SESSION_TTL_MS) history.clear()
    }

    /** Trim from the front to at most [MAX_HISTORY]; when possible, cut exactly at the next User
     *  message so no orphan Assistant/Tool head remains (providers reject a tool message whose
     *  tool_calls assistant was dropped). The very last entry is never itself treated as that
     *  boundary — it was just appended by this very call ([ask]'s fresh User or [noteAction]'s
     *  fresh note) and must always survive the trim. If no User boundary exists in the region
     *  being dropped (e.g. a stretch of synthetic [noteAction] entries with no User message at
     *  all), fall back to a hard cap that just drops the oldest entries — keeping the freshest
     *  notes matters more than the boundary guarantee in that case. */
    private fun trimHistory() {
        val excess = history.size - MAX_HISTORY
        if (excess <= 0) return
        val searchLimit = history.size - 1
        var cut = excess
        while (cut < searchLimit && history[cut] !is AgentMessage.User) cut++
        repeat(if (cut < searchLimit) cut else excess) { history.removeAt(0) }
    }

    companion object {
        private const val SESSION_TTL_MS = 300_000L
        private const val FOLLOW_UP_WINDOW_MS = 60_000L
        private const val MAX_ITERATIONS = 8
        private const val MAX_HISTORY = 20
        private const val MAX_IDENTICAL_CALLS = 2
        private const val MAX_LOOP_STRIKES = 2
        private const val LOOP_ERROR =
            """{"error":"этот вызов уже выполнялся с теми же аргументами, смени подход или ответь пользователю"}"""
        internal val SYSTEM_PROMPT = """
            Ты голосовой ассистент автомобиля BYD в приложении BYDMate. Водитель за рулём,
            ответ читается вслух: отвечай по-русски, максимум 1-2 коротких предложения,
            без списков и markdown.

            ПОВЕДЕНИЕ:
            - Выполнил команду - подтверди коротко: "Готово", "Окна закрыты", "Климат на 22".
            - Не описывай, что собираешься сделать - сделай через инструмент и подтверди результат.
            - Составная просьба ("открой окна и люк") - выполни все действия последовательно
              отдельными вызовами инструментов, в конце подтверди одной фразой.
              Пример: "закрой окна и включи подогрев водителя на 3" -> вызови
              vehicle_control(windows_close_all), затем vehicle_control(seat_heat_driver_3),
              ответ: "Готово".
            - Условная просьба ("если заряд меньше 50, закрой окно") - сначала проверь данные
              инструментом, потом действуй, в ответе назови факт и что сделал.
            - Запрос неоднозначен - задай ОДИН короткий уточняющий вопрос, не гадай
              ("Какое окно - водителя или все?").
            - Факты о машине, поездках и зарядках бери ТОЛЬКО из инструментов, не выдумывай.
            - Если параметра нет в ответе инструмента - он НЕИЗВЕСТЕН: так и скажи; не считай
              его нулём или выключенным.
            - Не выдумывай функции, которых нет среди инструментов: скажи прямо, что не умеешь.
            - Помни контекст: "а теперь закрой" относится к предыдущей команде.
            - Если инструмент вернул error - коротко назови причину; не говори, что выполнил.

            АВТОМАТИЗАЦИИ И МЕСТА: у пользователя есть автоматизации (триггер + действия) и
            Места (гео-точки). Для триггеров place_enter/place_exit сначала проверь имя через
            list_places; если Места нет - предложи создать его через create_place. Триггер
            time_range "HH:MM-HH:MM" с одинаковым началом и концом срабатывает ровно в этот
            момент времени. После create_automation подтверди имя, триггер и действия.

            О ПРИЛОЖЕНИИ: BYDMate ведёт журнал поездок и зарядок (GPS-маршруты, расход,
            стоимость), показывает статистику и AI-инсайты, умеет автоматизации
            (триггер + действие) и управляет устройствами машины.
        """.trimIndent()
    }
}
