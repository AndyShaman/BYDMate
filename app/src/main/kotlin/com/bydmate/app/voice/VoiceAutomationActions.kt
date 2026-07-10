package com.bydmate.app.voice

import android.content.Context
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.DispatchResult
import com.bydmate.app.ui.overlay.ListeningOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Voice actions fired by automations ("speak" / "agent_query" kinds). Lives ABOVE both the
 * voice stack and the dispatcher: ActionDispatcher reaches it via dagger.Lazy, because a plain
 * edge would close the DI cycle ActionDispatcher -> here -> AgentOrchestrator -> AgentTools ->
 * ActionDispatcher. Owns what the voice session normally owns: orb lifecycle (announce() alone
 * never shows the orb), music ducking (TtsEngine plays raw, no audio focus), and busy
 * arbitration (a live voice session always wins; automation requests are dropped with a
 * journaled reason, never queued — a stale morning summary must not play after a real dialog).
 */
@Singleton
class VoiceAutomationActions @Inject constructor(
    private val ttsEngine: TtsEngine,
    private val audioCapture: AudioCapture,
    private val gate: VoiceGate,
    private val agentOrchestrator: dagger.Lazy<AgentOrchestrator>,
    private val voiceController: dagger.Lazy<VoiceController>,
    @ApplicationContext private val context: Context,
) {
    private val ownBusy = AtomicBoolean(false)
    @Volatile private var lastAgentQueryAt = 0L

    /** Test seams (JVM tests must not touch the real static overlay / clock). */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var isOrbShowing: () -> Boolean = { ListeningOverlay.isShowing() }
    internal var canShowOrb: () -> Boolean = { ListeningOverlay.canShow(context) }
    internal var showOrb: suspend (String) -> Unit = { ListeningOverlay.show(context, it) }
    internal var showOrbAnswer: (String) -> Unit = { ListeningOverlay.showAnswer(it) }
    internal var hideOrb: () -> Unit = { ListeningOverlay.hide() }

    /** Automation action "speak": say [text] verbatim (no persona wrapper -- the text is the
     *  user's own rule content, not an agent reply). */
    suspend fun speak(text: String): DispatchResult {
        val t = text.trim()
        if (t.isEmpty()) return DispatchResult(false, "не задан текст")
        gateReason()?.let { return DispatchResult(false, it) }
        return speakGuarded(t)
    }

    /** Common gates for both actions. Null = allowed. */
    private fun gateReason(): String? = when {
        !gate.isEnabled() -> "голосовой помощник выключен в настройках"
        !gate.ttsEnabled() -> "озвучка ответов выключена в настройках"
        voiceController.get().sessionActive() -> "идёт голосовая сессия, действие пропущено"
        else -> null
    }

    private suspend fun speakGuarded(text: String): DispatchResult {
        // Re-check before acquiring anything: a live voice session may have started while
        // the agent query was in flight (up to AGENT_QUERY_TIMEOUT_MS). Nothing was acquired
        // yet so no cleanup is needed -- return the same reason the entry gate uses.
        if (voiceController.get().sessionActive()) {
            return DispatchResult(false, "идёт голосовая сессия, действие пропущено")
        }
        if (!ownBusy.compareAndSet(false, true)) {
            return DispatchResult(false, "другое голосовое действие ещё выполняется")
        }
        try {
            // Show the orb only if nobody else owns it; then never hide someone else's orb.
            // Rethrow CancellationException so it is never silently swallowed by runCatching
            // around a suspend call: the caller's scope cancellation must still propagate.
            val showedOrb = if (!isOrbShowing() && canShowOrb()) {
                runCatching { showOrb(text) }
                    .onFailure { if (it is CancellationException) throw it }
                    .isSuccess
            } else false
            if (showedOrb) runCatching { showOrbAnswer(text) }
            // TtsEngine plays raw AudioTrack audio: no audio focus, no ducking -- duck here.
            // restoreMusic and hideOrb are in a finally so cancellation mid-drain never leaks
            // the duck (music stuck silent) or the orb (stranded on screen).
            val saved = audioCapture.duckMusic()
            var ok = false
            var timedOut = false
            try {
                ok = runCatching { ttsEngine.speak(text) }.getOrDefault(false)
                if (ok) {
                    val completed = withTimeoutOrNull(SPEAK_TIMEOUT_MS) {
                        // speak() only enqueues; wait for playback to start, then to drain.
                        withTimeoutOrNull(SPEAK_START_TIMEOUT_MS) { ttsEngine.speaking.first { it } }
                        ttsEngine.speaking.first { !it }
                    }
                    if (completed == null) {
                        // Timeout: stop TTS so it does not keep playing after the action returns.
                        runCatching { ttsEngine.stop() }
                        timedOut = true
                    }
                }
            } finally {
                audioCapture.restoreMusic(saved)
                if (showedOrb) runCatching { hideOrb() }
            }
            return when {
                timedOut -> DispatchResult(false, "озвучка прервана по таймауту")
                ok -> DispatchResult(true)
                else -> DispatchResult(false, "озвучка не запустилась")
            }
        } finally {
            ownBusy.set(false)
        }
    }

    /** Automation action "agent_query": run [prompt] through an isolated agent turn and speak
     *  the answer. Cooldown is a runaway brake on top of the per-rule cooldown: fireVoiceRule
     *  bypasses rule cooldowns, so a misconfigured rule chain must hit a wall here. */
    suspend fun agentQuery(prompt: String): DispatchResult {
        val p = prompt.trim().take(MAX_PROMPT_CHARS)
        if (p.isEmpty()) return DispatchResult(false, "не задан запрос")
        gateReason()?.let { return DispatchResult(false, it) }
        val now = nowMs()
        if (now - lastAgentQueryAt < AGENT_QUERY_COOLDOWN_MS) {
            return DispatchResult(false, "запрос агенту не чаще раза в ${AGENT_QUERY_COOLDOWN_MS / 1000} секунд")
        }
        lastAgentQueryAt = now
        val result = withTimeoutOrNull(AGENT_QUERY_TIMEOUT_MS) { agentOrchestrator.get().askDetached(p) }
            ?: return DispatchResult(false, "агент не ответил вовремя")
        return when (result) {
            is AgentResult.Answer -> speakGuarded(result.text)
            AgentResult.Disabled -> DispatchResult(false, "агент выключен в настройках")
            is AgentResult.Error -> DispatchResult(false, result.message)
        }
    }

    companion object {
        internal const val SPEAK_TIMEOUT_MS = 30_000L
        internal const val SPEAK_START_TIMEOUT_MS = 3_000L
        internal const val AGENT_QUERY_COOLDOWN_MS = 30_000L
        internal const val AGENT_QUERY_TIMEOUT_MS = 60_000L
        internal const val MAX_PROMPT_CHARS = 500
    }
}
