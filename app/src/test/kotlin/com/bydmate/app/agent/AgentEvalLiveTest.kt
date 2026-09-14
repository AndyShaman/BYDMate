package com.bydmate.app.agent

import android.content.Context
import com.bydmate.app.cluster.ClusterVoiceControl
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.local.dao.ChargeDao
import com.bydmate.app.data.local.dao.RuleDao
import com.bydmate.app.data.local.dao.TripDao
import com.bydmate.app.data.remote.InsightsManager
import com.bydmate.app.data.remote.OpenRouterClient
import com.bydmate.app.data.repository.PlaceRepository
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.domain.battery.BatteryStateRepository
import com.bydmate.app.domain.calculator.RangeCalculator
import com.bydmate.app.ui.settings.SettingsViewModel
import com.bydmate.app.voice.VoiceGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Wave 6, live half: runs [AgentEvalCases] against a real model and prints a per-case verdict.
 *
 * Skipped unless OPENROUTER_API_KEY is set, so it never runs in CI or in a normal test run.
 * Use scripts/agent-eval.sh, which documents the environment variables.
 */
class AgentEvalLiveTest {

    private val apiKey: String? = System.getenv("OPENROUTER_API_KEY")?.takeIf { it.isNotBlank() }
    private val model: String = System.getenv("AGENT_EVAL_MODEL")?.takeIf { it.isNotBlank() }
        ?: SettingsViewModel.DEFAULT_OPENROUTER_MODEL

    private val client = OpenRouterClient(
        OkHttpClient.Builder()
            .callTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(CALL_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    )

    private fun tools() = AgentTools(
        mockk<VoiceGate>(relaxed = true), mockk<BatteryStateRepository>(relaxed = true),
        mockk<RangeCalculator>(relaxed = true), mockk<TripDao>(relaxed = true),
        mockk<ChargeDao>(relaxed = true), mockk<ActionDispatcher>(relaxed = true),
        mockk<RuleDao>().also {
            coEvery { it.getEnabled() } returns emptyList()
            every { it.getAll() } returns MutableStateFlow(emptyList())
        },
        mockk<AutomationEngine>(relaxed = true), mockk<PlaceRepository>(relaxed = true),
        mockk<WeatherClient>(relaxed = true), mockk<ExaSearchClient>(relaxed = true),
        mockk<OpenRouterClient>(relaxed = true),
        mockk<SettingsRepository>().also {
            coEvery { it.getString(SettingsRepository.KEY_EXA_API_KEY, "") } returns "exa-key"
        },
        mockk<ContactLookup>(relaxed = true), mockk<Context>(relaxed = true),
        mockk<ClusterVoiceControl>(relaxed = true), mockk<ChargerSearchClient>(relaxed = true),
        mockk<InsightsManager>(relaxed = true), mockk<ZaiSearchClient>(relaxed = true),
        mockk<LlmConnectionResolver>(relaxed = true),
    ).also { it.ruleWatchScope = CoroutineScope(Dispatchers.Unconfined) }

    @Test fun the_model_handles_the_eval_set() = runBlocking {
        assumeFalse("OPENROUTER_API_KEY is not set — live eval skipped", apiKey == null)
        val schemas = tools().schemas()
        val failures = mutableListOf<String>()
        println("agent eval: model=$model cases=${AgentEvalCases.ALL.size}")
        for (case in AgentEvalCases.ALL) {
            val verdict = judge(case, ask(case.utterance, schemas))
            println(if (verdict == null) "  ok   ${case.utterance}" else "  FAIL ${case.utterance}: $verdict")
            if (verdict != null) failures += "${case.utterance} — $verdict"
        }
        val passed = AgentEvalCases.ALL.size - failures.size
        println("agent eval: $passed/${AgentEvalCases.ALL.size} passed on $model")
        assertTrue(
            "below the pass bar:\n" + failures.joinToString("\n"),
            passed >= AgentEvalCases.ALL.size * MIN_PASS_RATE,
        )
    }

    private suspend fun ask(utterance: String, schemas: JSONArray): JSONObject {
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", AgentOrchestrator.SYSTEM_PROMPT))
            .put(JSONObject().put("role", "user").put("content", utterance))
        return client.chatRaw(OPENROUTER_BASE, apiKey!!, model, messages, schemas)
            .getOrElse { return JSONObject().put("content", "ошибка запроса: ${it.message}") }
    }

    /** Null when the reply matches the expectation, else a one-line reason for the printout. */
    private fun judge(case: EvalCase, message: JSONObject): String? {
        val calls = message.optJSONArray("tool_calls") ?: JSONArray()
        val first = calls.optJSONObject(0)?.optJSONObject("function")
        val name = first?.optString("name")
        val arguments = first?.optString("arguments").orEmpty()
        if (case.tool == null) {
            return if (name == null) null else "ждали вопрос словами, получили вызов $name"
        }
        if (name != case.tool) return "ждали ${case.tool}, получили ${name ?: "текст без вызова"}"
        val missing = case.args.filterNot { arguments.contains(it, ignoreCase = true) }
        return if (missing.isEmpty()) null else "в аргументах нет $missing: $arguments"
    }

    private companion object {
        const val OPENROUTER_BASE = "https://openrouter.ai/api/v1"
        const val CALL_TIMEOUT_S = 60L
        /** A weak model is expected to miss a few; a broken prompt misses many. */
        const val MIN_PASS_RATE = 0.8
    }
}
