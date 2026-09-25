package com.bydmate.app.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.util.AppStrings
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric: the refusal reasons come from the app strings, the Russian texts below are the real ones.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class VoiceAutomationActionsAgentQueryTest {

    private val strings = AppStrings(ApplicationProvider.getApplicationContext())

    private fun lang(tag: String) = LocalePreferences(ApplicationProvider.getApplicationContext()).setLanguage(tag)

    init {
        lang("ru")
    }

    @After fun restoreLanguage() = lang("ru")

    // Factory duplicated from VoiceAutomationActionsSpeakTest — duplication in tests is fine.
    private fun makeActions(
        ttsEnabled: Boolean = true,
        voiceEnabled: Boolean = true,
        listening: Boolean = false,
        speakOk: Boolean = true,
        ttsEngine: TtsEngine = mockk(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { speak(any()) } returns speakOk
        },
        audioCapture: AudioCapture = mockk(relaxed = true),
        orchestrator: AgentOrchestrator = mockk(relaxed = true),
    ): Pair<VoiceAutomationActions, TtsEngine> {
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns voiceEnabled
        every { gate.ttsEnabled() } returns ttsEnabled
        val controller = mockk<VoiceController>()
        every { controller.listening } returns MutableStateFlow(listening)
        every { controller.sessionActive() } returns listening
        val actions = VoiceAutomationActions(
            ttsEngine = ttsEngine,
            audioCapture = audioCapture,
            gate = gate,
            agentOrchestrator = dagger.Lazy { orchestrator },
            voiceController = dagger.Lazy { controller },
            context = mockk<Context>(relaxed = true),
            strings = strings,
        )
        // Never touch the real static overlay in JVM tests.
        actions.isOrbShowing = { false }
        actions.canShowOrb = { false }
        return actions to ttsEngine
    }

    @Test fun `answer is spoken and reported as success`() = runTest {
        val orch = mockk<AgentOrchestrator>()
        coEvery { orch.askDetached("сводка погоды") } returns AgentResult.Answer("Солнечно", emptyList())
        val (actions, tts) = makeActions(orchestrator = orch)
        val r = actions.agentQuery("сводка погоды")
        assertTrue(r.success)
        verify(exactly = 1) { tts.speak("Солнечно") }
    }

    @Test fun `agent disabled maps to failure with reason`() = runTest {
        val orch = mockk<AgentOrchestrator>()
        coEvery { orch.askDetached(any()) } returns AgentResult.Disabled
        val (actions, _) = makeActions(orchestrator = orch)
        val r = actions.agentQuery("тест")
        assertFalse(r.success)
        assertEquals("агент выключен в настройках", r.reason)
    }

    @Test fun `blank prompt - refused without calling the agent`() = runTest {
        val orch = mockk<AgentOrchestrator>(relaxed = true)
        val (actions, _) = makeActions(orchestrator = orch)
        val r = actions.agentQuery("   ")
        assertFalse(r.success)
        assertEquals("не задан запрос", r.reason)
        coVerify(exactly = 0) { orch.askDetached(any()) }
    }

    @Test fun `agent error message is surfaced as the failure reason`() = runTest {
        val orch = mockk<AgentOrchestrator>()
        coEvery { orch.askDetached(any()) } returns AgentResult.Error("Нет связи с сервером, скажи простую команду")
        val (actions, _) = makeActions(orchestrator = orch)
        assertEquals("Нет связи с сервером, скажи простую команду", actions.agentQuery("тест").reason)
    }

    @Test fun `cooldown blocks a second query and lets it pass after the window`() = runTest {
        val orch = mockk<AgentOrchestrator>()
        coEvery { orch.askDetached(any()) } returns AgentResult.Answer("ок", emptyList())
        val (actions, _) = makeActions(orchestrator = orch)
        var clock = 1_000_000L
        actions.nowMs = { clock }
        assertTrue(actions.agentQuery("раз").success)
        assertFalse(actions.agentQuery("два").success)          // inside cooldown
        clock += VoiceAutomationActions.AGENT_QUERY_COOLDOWN_MS + 1
        assertTrue(actions.agentQuery("три").success)           // window passed
        coVerify(exactly = 2) { orch.askDetached(any()) }
    }

    @Test fun `prompt is trimmed to the length cap`() = runTest {
        val orch = mockk<AgentOrchestrator>()
        coEvery { orch.askDetached(any()) } returns AgentResult.Answer("ок", emptyList())
        val (actions, _) = makeActions(orchestrator = orch)
        actions.agentQuery("а".repeat(2_000))
        coVerify { orch.askDetached(match { it.length == VoiceAutomationActions.MAX_PROMPT_CHARS }) }
    }

    @Test fun `live voice session drops the query without calling the agent`() = runTest {
        val orch = mockk<AgentOrchestrator>(relaxed = true)
        val (actions, _) = makeActions(orchestrator = orch, listening = true)
        assertFalse(actions.agentQuery("тест").success)
        coVerify(exactly = 0) { orch.askDetached(any()) }
    }

    // Race condition: session becomes active WHILE the agent query is in flight.
    // The agent returns an answer, but speakGuarded must detect listening=true and drop it
    // WITHOUT calling ttsEngine.speak() or audioCapture.duckMusic().
    @Test fun `answer arrives while live session became active mid-query - speech is dropped`() = runTest {
        val listeningFlow = MutableStateFlow(false)
        val tts = mockk<TtsEngine>(relaxed = true) {
            every { speaking } returns MutableStateFlow(false)
            every { speak(any()) } returns true
        }
        val capture = mockk<AudioCapture>(relaxed = true)
        val orch = mockk<AgentOrchestrator>()
        // Flip listening=true before returning the answer, simulating a session that started
        // while the LLM call was in flight.
        coEvery { orch.askDetached(any()) } coAnswers {
            listeningFlow.value = true
            AgentResult.Answer("Ответ агента", emptyList())
        }
        val gate = mockk<VoiceGate>()
        every { gate.isEnabled() } returns true
        every { gate.ttsEnabled() } returns true
        val controller = mockk<VoiceController>()
        every { controller.listening } returns listeningFlow
        // sessionActive() must mirror the dynamic listeningFlow state for the race-condition check.
        every { controller.sessionActive() } answers { listeningFlow.value }
        val actions = VoiceAutomationActions(
            ttsEngine = tts,
            audioCapture = capture,
            gate = gate,
            agentOrchestrator = dagger.Lazy { orch },
            voiceController = dagger.Lazy { controller },
            context = mockk(relaxed = true),
            strings = strings,
        )
        actions.isOrbShowing = { false }
        actions.canShowOrb = { false }

        val r = actions.agentQuery("тест")
        assertFalse(r.success)
        assertEquals("идёт голосовая сессия, действие пропущено", r.reason)
        verify(exactly = 0) { tts.speak(any()) }
        verify(exactly = 0) { capture.duckMusic() }
    }

    // Timeout: askDetached suspends longer than AGENT_QUERY_TIMEOUT_MS.
    // The returned reason must be the exact timeout string, AND the cooldown must be stamped
    // before the call so a second immediate call is blocked (stamp-before-call invariant).
    @Test fun `timeout returns correct reason and stamps cooldown`() = runTest {
        val orch = mockk<AgentOrchestrator>()
        coEvery { orch.askDetached(any()) } coAnswers {
            delay(120_000L) // exceeds AGENT_QUERY_TIMEOUT_MS (60 s) under virtual time
            AgentResult.Answer("никогда", emptyList())
        }
        val (actions, _) = makeActions(orchestrator = orch)
        var clock = 1_000_000L
        actions.nowMs = { clock }

        val r1 = actions.agentQuery("раз")
        assertFalse(r1.success)
        assertEquals("агент не ответил вовремя", r1.reason)

        // Cooldown must be stamped: second call at the same clock tick must be dropped.
        val r2 = actions.agentQuery("два")
        assertFalse(r2.success)
        assertEquals(
            "запрос агенту не чаще раза в ${VoiceAutomationActions.AGENT_QUERY_COOLDOWN_MS / 1000} секунд",
            r2.reason,
        )
    }

    @Test fun `refusals follow the app language`() = runTest {
        lang("en")
        val disabled = mockk<AgentOrchestrator>()
        coEvery { disabled.askDetached(any()) } returns AgentResult.Disabled
        val slow = mockk<AgentOrchestrator>()
        coEvery { slow.askDetached(any()) } coAnswers {
            delay(120_000L)
            AgentResult.Answer("never", emptyList())
        }
        val (timed, _) = makeActions(orchestrator = slow)
        timed.nowMs = { 1_000_000L }

        val reasons = listOf(
            makeActions().first.agentQuery(" ").reason!!,
            makeActions(orchestrator = disabled).first.agentQuery("x").reason!!,
            timed.agentQuery("x").reason!!,
            timed.agentQuery("x").reason!!,    // inside the cooldown
        )

        assertEquals("the agent did not answer in time", reasons[2])
        assertEquals("the agent can be asked at most once every 30 seconds", reasons[3])
        reasons.forEach { assertFalse(it, it.any { c -> c in 'Ѐ'..'ӿ' }) }
    }
}
