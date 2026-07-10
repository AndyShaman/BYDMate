package com.bydmate.app.voice

import android.content.Context
import android.util.Log
import com.bydmate.app.agent.AgentOrchestrator
import com.bydmate.app.agent.AgentResult
import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.automation.AutomationEngine
import com.bydmate.app.data.automation.VoiceFireResult
import com.bydmate.app.R
import com.bydmate.app.data.local.LocalePreferences
import com.bydmate.app.data.local.entity.ActionDef
import com.bydmate.app.ui.overlay.ListeningOverlay
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceController @Inject constructor(
    private val audioCapture: AudioCapture,
    private val asrEngine: AsrEngine,
    private val modelManager: VoiceModelManager,
    private val actionDispatcher: ActionDispatcher,
    private val localePreferences: LocalePreferences,
    private val earcon: VoiceEarcon,
    private val gate: VoiceGate,
    private val automationEngine: AutomationEngine,
    private val automationResolver: VoiceAutomationResolver,
    private val agentOrchestrator: AgentOrchestrator,
    @ApplicationContext private val context: Context,
    private val ttsEngine: TtsEngine,
    private val journal: VoiceJournal,
    private val continuousAsr: ContinuousAsr,
    private val agentIdentity: () -> AgentIdentity,
    private val ttsModelManager: TtsModelManager,
    private val ruStressMarker: RuStressMarker,
    private val selectedTtsVoice: () -> TtsVoice,
    private val echoFilter: SelfEchoFilter = SelfEchoFilter(),
) {
    // Process-lifetime scope (@Singleton): intentionally never cancelled.
    private val scope = CoroutineScope(SupervisorJob())
    private val _state = MutableStateFlow<VoiceUiState>(VoiceUiState.Idle)
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    // Continuous session indicator (Wave B), consumed by the listening-widget UI. Distinct from
    // [state], which flashes each utterance's terminal outcome and resets independently.
    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val busy = AtomicBoolean(false)
    @Volatile private var sessionJob: Job? = null
    @Volatile private var routingJob: Job? = null
    @Volatile private var cancellableAskJob: Job? = null
    // Busy drops are Log.i-only by contract (no journal/earcon/state change), so tests have no
    // observable signal that the sequential collect consumed a dropped utterance; this counter
    // is that signal. @Volatile: written by the collect coroutine, read from the test thread.
    @Volatile private var droppedWhileBusy = 0

    /** Test seams, same rationale as [lastSpeakingSeenMs]: deterministic await conditions
     *  instead of fixed sleeps, no public API surface added. */
    internal fun routingJobForTest(): Job? = routingJob
    internal fun droppedWhileBusyForTest(): Int = droppedWhileBusy
    // Hard-stop contract: orb press (stopContinuousSession) cancels routingJob immediately.
    // A vehicle-write unit that has already started runs to completion regardless — VehicleApiImpl
    // wraps composite (window fan-out, fridge preset) and seat (switch+level) write sequences in
    // withContext(NonCancellable), so a cancellation mid-sequence can never leave the car
    // half-commanded. processingUtterance is retained as a flag for the barge-in / busy-drop path.
    @Volatile private var processingUtterance = false
    private val stopRequested = AtomicBoolean(false)
    // Anti-self-trigger mute window for the continuous session's mic capture. Stamped two ways:
    // (1) at call time, right after ttsEngine.speak() returns true (announce()/agent answer) --
    // this is the floor, since we always know when we start our own TTS, and it also covers a
    // short utterance that starts AND ends entirely between two mic frames, which would otherwise
    // never be observed. speak() returning false (blank text, or engine not ready -- e.g. voice
    // not downloaded) means no audio was actually enqueued, so no stamp: muting the mic for a
    // no-op would swallow the start of the user's next phrase for nothing.
    // (2) inline in the mic filter for every frame where ttsEngine.speaking reads
    // true, which extends the stamp across longer utterances (no collect()-based watcher --
    // collecting a StateFlow can conflate a fast true->false->true transition away, silently
    // missing a mute window; reading .value per frame always reflects that exact instant).
    // shouldMute() stays muted for TTS_MUTE_GRACE_MS after this timestamp.
    // 0 outside a continuous session -- shouldMute() then always reports false, so the legacy
    // one-shot path (whose mic already closed before it speaks) is never affected.
    // internal (not private): lets tests await the exact moment either stamp fires as a
    // deterministic condition, instead of a fixed sleep -- no public API surface added.
    @Volatile internal var lastSpeakingSeenMs: Long = 0L

    // After a terminal state (Done/NotUnderstood/Blocked) the voice UI state auto-returns to Idle
    // so it never sticks (the "не распознал" red used to stay forever). Kept short so it
    // feels instant. resetGen invalidates a pending reset once a newer session owns the state
    // machine; the busy check stops a stale reset from firing mid-session.
    @Volatile private var resetJob: Job? = null
    private val resetGen = AtomicInteger(0)
    // Test seam — overridable so unit tests need not wait the full delay.
    internal var idleResetDelayMs: Long = IDLE_RESET_DELAY_MS

    // Wave D2: the orb dialog block ("Ты: …" / "Агент: …") is cleared this long after the spoken
    // answer finishes (or after it is shown, if TTS is off). Overridable so unit tests need not wait
    // the full 6s -- mirrors idleResetDelayMs above.
    internal var dialogClearDelayMs: Long = DIALOG_CLEAR_MS

    // Pending orb-dialog clear scheduled after a terminal answer; cancelled by a new utterance so the
    // block never collapses mid-conversation. Only ever touched from scheduleClear/cancelScheduledClear.
    @Volatile private var clearJob: Job? = null

    // Test seams — real impl shows/updates/hides the persistent "Слушаю"/"Думаю" pill for the
    // whole continuous-session lifetime (Wave B indication).
    internal var showListeningOverlay: suspend (String) -> Unit = { text -> ListeningOverlay.show(context, text) }
    internal var updateListeningOverlay: (String) -> Unit = { text -> ListeningOverlay.update(text) }
    internal var hideListeningOverlay: () -> Unit = { ListeningOverlay.hide() }

    // Test seams — the running orb dialog block (Wave D2): the recognized user phrase ("Ты: …"),
    // the agent/command answer ("Агент: …"), and clearing both. All three are no-op-safe when the
    // orb is not shown (they only mutate state the dialog window observes). Mirror the overlay seams.
    internal var showHeardHook: (String) -> Unit = { text -> ListeningOverlay.showHeard(text) }
    internal var showAnswerHook: (String) -> Unit = { text -> ListeningOverlay.showAnswer(text) }
    internal var clearDialogHook: () -> Unit = { ListeningOverlay.clearDialog() }

    /** Speak a short phrase for every session outcome (not only agent answers).
     *  Overlay text stays detailed; the spoken phrase is terse for the road. */
    private suspend fun announce(title: String, overlay: String, spoken: String) {
        // Hard stop gate: never start a new announcement after the orb went off — the callers'
        // runCatching wrappers swallow the cancellation that would otherwise stop this path.
        // coroutineContext.isActive is the per-turn mark: a cancelled routing turn stays
        // cancelled even after a following session has reset the global stopRequested flag.
        if (stopRequested.get() || !currentCoroutineContext().isActive) return
        if (gate.ttsEnabled()) {
            // Stamp at the moment we ourselves start TTS, not only from the mic filter's
            // per-frame read of ttsEngine.speaking: a short utterance that starts AND ends
            // entirely between two mic frames (see lastSpeakingSeenMs above) would otherwise
            // never get stamped at all, since speaking never reads true on any frame. Only
            // stamp when speak() actually enqueued playback -- see lastSpeakingSeenMs above.
            val phrase = agentIdentity().persona.spokenPhrase(spoken)
            if (runCatching { ttsEngine.speak(phrase) }.getOrDefault(false)) {
                echoFilter.noteSpoken(phrase)
                lastSpeakingSeenMs = System.currentTimeMillis()
            }
        }
        // Orb dialog: mirror the detailed overlay text into the "Агент: …" row, then arm the clear.
        // Placed here so every announce() terminal (command outcomes + agent Disabled/Error) feeds the
        // orb; the agent Answer branch, which does not call announce(), does the same two calls itself.
        showAnswerHook(overlay)
        scheduleClear()
    }

    /** Records one journal entry + a matching logcat line for a terminal voice-session outcome.
     *  The Chinese command string (if any) belongs only in [logMsg], never in [detail] or
     *  [VoiceJournalEntry.transcript] — those stay user-displayable for the debug journal screen. */
    private fun record(
        route: VoiceJournalEntry.Route,
        transcript: String,
        detail: String,
        outcome: VoiceJournalEntry.Outcome,
        reason: String? = null,
        logMsg: String,
    ) {
        journal.add(
            VoiceJournalEntry(
                timestampMs = System.currentTimeMillis(),
                transcript = transcript,
                route = route,
                detail = detail,
                outcome = outcome,
                reason = reason,
            )
        )
        Log.i(TAG, logMsg)
    }

    // Fix D: prefer the Settings language override (via gate); fall back to app locale.
    fun currentLang(): VoiceLang =
        gate.preferredLang()
            ?: if ((localePreferences.getLanguage() ?: "ru") == "en") VoiceLang.EN else VoiceLang.RU

    fun startSession() {
        if (!gate.isEnabled()) return
        if (!busy.compareAndSet(false, true)) return
        ensureSupertonicStressDict()
        // A previous continuous-session hard stop leaves stopRequested set; a fresh legacy
        // one-shot session must not inherit it, or every announce() would be suppressed.
        stopRequested.set(false)
        // Barge-in: kill any ongoing TTS so it neither talks over the user
        // nor bleeds into the mic capture.
        runCatching { ttsEngine.stop() }
        val lang = currentLang()
        // Return early if the model for this language is not yet downloaded/ready.
        if (!asrEngine.isModelReady(lang)) {
            // Set the terminal state BEFORE releasing busy — otherwise a concurrent startSession()
            // could win the busy CAS and set Listening, which this stale write would then clobber.
            _state.value = VoiceUiState.NotUnderstood("")
            // Outcome.ERROR (not NOT_UNDERSTOOD): a missing model is a system fault, not the driver
            // going unrecognized — the UI still degrades to NotUnderstood (no dedicated crash state).
            record(VoiceJournalEntry.Route.NONE, "", "", VoiceJournalEntry.Outcome.ERROR,
                "Модель распознавания не готова", "Voice model not ready for lang=$lang")
            busy.set(false)
            scheduleIdleReset()
            // No suspend context here (this is the synchronous early-return path) — hop onto the
            // controller's own scope so the driver still gets an overlay, not just silence.
            scope.launch { announce("Голос", "Голосовая модель не загружена", "Голосовая модель не загружена") }
            return
        }
        scope.launch {
            try {
                _state.value = VoiceUiState.Listening
                // Vocabulary source seam: built-in lexicon + user automation phrases.
                val vocab = VoiceLexicon.vocabulary(lang) + automationResolver.phrases()
                val vocabJson = JSONArray(vocab).toString()
                val pcm = audioCapture.captureSession()

                // Stream recognition. Early-fire the instant a confident command stabilises across a
                // few partials; otherwise route the Vosk VAD final (end of phrase). lastPartial/stable
                // count IDENTICAL hypotheses, so a phrase that is a prefix of a longer one
                // ("открой окно" before "...водителя") does NOT fire while the user is still talking —
                // the partial keeps changing and resets the counter, and the correct longer command
                // resolves at the final instead.
                // See routeUtterance: an unanswered agent question routes this whole
                // utterance to the agent — skip NLU resolution AND early-fire.
                val followUp = runCatching { agentOrchestrator.expectsFollowUp() }.getOrDefault(false)
                var lastPartial = ""
                var stable = 0
                var chosen: Resolution? = null
                var transcript = ""
                try {
                    asrEngine.recognize(pcm, lang, vocabJson).collect { ev ->
                        when (ev) {
                            is AsrEvent.Partial -> {
                                if (ev.text == lastPartial) stable++ else { lastPartial = ev.text; stable = 1 }
                                // Resolve once per stable plateau (== guard) to avoid re-querying the DB.
                                // Aperture OPENs are excluded from early-fire (see isEarlyFireable):
                                // they wait for the final so a mid-phrase pause can't open the wrong one.
                                if (!followUp && stable == EARLY_FIRE_STABLE_READS) {
                                    resolve(ev.text, lang)?.takeIf { isEarlyFireable(it) }
                                        ?.let { chosen = it; transcript = ev.text; throw StopCollect }
                                }
                            }
                            is AsrEvent.Final -> {
                                chosen = if (followUp) null else resolve(ev.text, lang)
                                transcript = ev.text
                                throw StopCollect
                            }
                        }
                    }
                } catch (e: StopCollect) {
                    // Expected: stop capture on the first actionable event (early-fire or final).
                }

                _state.value = VoiceUiState.Thinking
                val res = chosen
                if (res != null) apply(res, transcript)
                else agentFallback(transcript)
            } catch (t: Throwable) {
                // A real coroutine cancellation must propagate; only genuine failures fall through.
                if (t is CancellationException) throw t
                earcon.fail()
                _state.value = VoiceUiState.NotUnderstood("")
                // Outcome.ERROR (not NOT_UNDERSTOOD): a genuine pipeline crash, not the driver going
                // unrecognized — the UI still degrades to NotUnderstood (no dedicated crash state).
                record(VoiceJournalEntry.Route.NONE, "", "", VoiceJournalEntry.Outcome.ERROR, null,
                    "Voice session failed: ${t.message}")
                announce("Голос", "Отказ: ${t.message ?: "внутренняя ошибка"}", "Ошибка")
            } finally {
                busy.set(false)
                scheduleIdleReset()
            }
        }
    }

    /** Supertonic voices need a side-loaded dictionary for uppercase stress marking. This runs
     *  out-of-band so a missing network/dict never blocks recognition; [TtsModelManager] itself
     *  no-ops when the file is already present and fails soft when it cannot be fetched. */
    private fun ensureSupertonicStressDict() {
        val voice = runCatching { selectedTtsVoice() }.getOrDefault(TtsVoiceCatalog.byId(TtsModelManager.DEFAULT_VOICE_ID))
        if (voice.engine != TtsVoiceEngine.SUPERTONIC) return
        scope.launch {
            if (ttsModelManager.ensureStressDict(voice)) ruStressMarker.preload()
        }
    }

    /** PTT toggle (Wave B): no session running -> start one (continuous GigaAM session when the
     *  model is ready and the language is RU, else the legacy one-shot path, unchanged); a
     *  continuous session already listening -> stop it immediately (barge-in stops TTS too). */
    fun onPttPressed() {
        if (!gate.isEnabled()) return
        if (_listening.value) {
            stopContinuousSession()
            return
        }
        if (continuousAsr.isReady() && currentLang() == VoiceLang.RU) {
            startContinuousSession()
        } else {
            startSession()
        }
    }

    /** Continuous PTT-toggled session (Wave B): one long-lived mic capture feeds VAD-segmented
     *  utterances into the same NLU/agent router the legacy one-shot path uses (routeUtterance),
     *  so a follow-up question from the agent keeps listening for free — the loop just collects
     *  again. Auto-stops after SILENCE_AUTOSTOP_MS of continuous silence (Wave P: no session cap). */
    private fun startContinuousSession() {
        if (!busy.compareAndSet(false, true)) return
        // Barge-in: kill any ongoing TTS so it neither talks over the user nor bleeds into capture.
        runCatching { ttsEngine.stop() }
        lastSpeakingSeenMs = 0L
        processingUtterance = false
        routingJob = null
        cancellableAskJob = null
        stopRequested.set(false)
        _state.value = VoiceUiState.Listening
        _listening.value = true
        earcon.ok()
        // Duck the music the instant the orb appears -- captureSession's own duck fires only after
        // the GigaAM recognizer is constructed (~1.3 s, field defect APK 337). duckMusic() is
        // idempotent (volume already at the duck target returns null), so the inner call becomes
        // a no-op and this early saved volume is the one restored at session teardown.
        val earlyDuck = runCatching { audioCapture.duckMusic() }.getOrNull()
        sessionJob = scope.launch {
            val session = coroutineContext[Job]
            runCatching { showListeningOverlay(context.getString(R.string.voice_listening)) }
            var lastEventMs = System.currentTimeMillis()
            var wasAudible = false
            try {
                val pcm = audioCapture.captureSession(maxMs = Long.MAX_VALUE) // Wave P: no session cap; silence auto-stop below is the only auto-exit
                    .filter {
                        // Once a stop has been requested (see stopContinuousSession()) no further
                        // frames are forwarded to the recognizer, even while an in-flight utterance
                        // started before the stop is still being routed.
                        if (stopRequested.get()) return@filter false
                        // Read the real, physical playback signal each frame (see
                        // lastSpeakingSeenMs above for why not a collect()-based watcher):
                        // audible(), not the logical speaking flag, which is deliberately held
                        // true across a whole streamed reply (including silent tool rounds) so
                        // this filter would otherwise deafen the mic while the agent "thinks".
                        val speaking = ttsEngine.audible()
                        val now = System.currentTimeMillis()
                        if (speaking) lastSpeakingSeenMs = now
                        else if (wasAudible) {
                            // Transition from audible to silent: notify echo filter
                            echoFilter.onPlaybackEnd()
                        }
                        wasAudible = speaking
                        !shouldMute(now, lastSpeakingSeenMs + TTS_MUTE_GRACE_MS, speaking)
                    }
                continuousAsr.transcribe(pcm).collect { ev ->
                    when (ev) {
                        is ContinuousAsrEvent.SpeechStart -> {
                            lastEventMs = System.currentTimeMillis()
                            // The live VAD now detects speech while a routing child is in
                            // flight; clobbering Thinking here would violate the busy-drop
                            // contract (no state change while an utterance is being routed).
                            if (!processingUtterance) _state.value = VoiceUiState.Listening
                        }
                        is ContinuousAsrEvent.SilenceTick -> {
                            lastEventMs = System.currentTimeMillis()
                            if (ev.silentMs >= SILENCE_AUTOSTOP_MS && !processingUtterance) throw StopSession
                        }
                        is ContinuousAsrEvent.Utterance -> {
                            val decodeMs = System.currentTimeMillis() - lastEventMs
                            if (processingUtterance) {
                                if (AgentNameMatcher.matches(ev.text, agentIdentity().name)) {
                                    cancellableAskJob?.takeIf { it.isActive }?.let { ask ->
                                        Log.i(TAG, "Barge-in by name")
                                        ask.cancel()
                                        runCatching { ttsEngine.stop() }
                                        earcon.ok()
                                        _state.value = VoiceUiState.Listening
                                        runCatching { updateListeningOverlay(context.getString(R.string.voice_listening)) }
                                        record(VoiceJournalEntry.Route.NONE, ev.text, "Прерван по имени",
                                            VoiceJournalEntry.Outcome.OK, null, "Barge-in by name")
                                    } ?: Log.i(TAG, "Barge-in by name ignored: no cancellable ask")
                                    return@collect
                                }
                                Log.i(TAG, "Utterance dropped while busy: ${ev.text}")
                                droppedWhileBusy++
                                return@collect
                            }
                            processingUtterance = true
                            val job = launch(start = CoroutineStart.LAZY) {
                                runCatching { updateListeningOverlay(context.getString(R.string.voice_thinking)) }
                                try {
                                    routeUtterance(ev.text, decodeMs)
                                } catch (t: Throwable) {
                                    // A single utterance's routing crashed. Only capture/ASR failures
                                    // (caught below) tear the session down -- this one just gets
                                    // journaled as an error, and the session keeps listening.
                                    if (t is CancellationException) throw t
                                    earcon.fail()
                                    _state.value = VoiceUiState.NotUnderstood(ev.text)
                                    record(VoiceJournalEntry.Route.NONE, ev.text, withDecodeMs(ev.text, decodeMs), VoiceJournalEntry.Outcome.ERROR, null,
                                        "Continuous session utterance failed: decodeMs=$decodeMs ${t.message}")
                                    announce("Голос", "Отказ: ${t.message ?: "внутренняя ошибка"}", "Ошибка")
                                } finally {
                                    routingJob = null
                                    processingUtterance = false
                                    runCatching { updateListeningOverlay(context.getString(R.string.voice_listening)) }
                                    if (stopRequested.get()) session?.cancel()
                                }
                            }
                            routingJob = job
                            job.start()
                            lastEventMs = System.currentTimeMillis()
                        }
                    }
                }
            } catch (e: StopSession) {
                // Expected silence auto-stop. Deferred PTT-stop after an in-flight utterance uses
                // session cancellation from the routing child so the session finally still runs.
            } catch (t: Throwable) {
                // A real coroutine cancellation (e.g. stopContinuousSession() cancelling sessionJob
                // while idle) must propagate. Anything else is a genuine capture/ASR failure (e.g.
                // GigaAM's transcribe() throwing) -- unlike a single utterance's routing crash
                // (caught above, session stays open), this tears the whole session down.
                if (t is CancellationException) throw t
                Log.w(TAG, "Continuous session failed: ${t.message}")
                record(VoiceJournalEntry.Route.NONE, "", "", VoiceJournalEntry.Outcome.ERROR, null,
                    "Continuous session failed: ${t.message}")
                announce("Голос", "Отказ: ${t.message ?: "внутренняя ошибка"}", "Ошибка")
            } finally {
                routingJob?.cancel()
                routingJob = null
                cancellableAskJob = null
                processingUtterance = false
                runCatching { audioCapture.restoreMusic(earlyDuck) }
                _listening.value = false
                _state.value = VoiceUiState.Idle
                // Distinct off-cue so the driver can tell session start (ok) and stop apart by ear.
                earcon.off()
                runCatching { hideListeningOverlay() }
                sessionJob = null
                busy.set(false)
            }
        }
    }

    /** User contract: orb button is a hard OFF switch. Cancels everything immediately, regardless
     *  of whether an utterance is mid-routing. Order: (1) set stop flag FIRST so every
     *  continuation path (pcm filter, routing finally, ask callback) sees it before any job is
     *  cancelled; (2) stop TTS; (3) cancel ask job; (4) cancel routing job; (5) cancel session.
     *
     *  Vehicle-write safety: a single binder.transact() is a blocking native call that completes
     *  even when the coroutine is cancelled mid-call, but compound commands (seat switch→level,
     *  fridge mode→temp, composite sequences) issue several writes. VehicleApiImpl wraps one
     *  command's write unit in withContext(NonCancellable), so a started sequence always runs to
     *  completion and cancellation lands between commands; dispatch wrappers rethrow
     *  CancellationException so a cancelled turn is never announced as a failure. */
    private fun stopContinuousSession() {
        stopRequested.set(true)   // (1) gate every continuation before cancelling anything
        runCatching { ttsEngine.stop() }
        Log.i(TAG, "stopContinuousSession: hard stop; askActive=${cancellableAskJob?.isActive} routingActive=${routingJob?.isActive}")
        cancellableAskJob?.cancel()  // (2) cancel pending agent ask / follow-up
        routingJob?.cancel()         // (3) cancel in-flight routing (NLU dispatch or agent path)
        sessionJob?.cancel()         // (4) full teardown; routing finally also cancels session
                                     //     via stopRequested, but we do it here too so idle-
                                     //     session stops are instant and not deferred to finally
    }

    /** Routes one continuous-session utterance through the exact same NLU-resolve -> apply /
     *  agentFallback path the legacy final-transcript routing uses below (VoiceJournal writes,
     *  noteAction, announce — all unchanged). decodeMs is also logged for RTF measurement on
     *  the car, and threaded into the VoiceJournal detail for each utterance. */
    private suspend fun routeUtterance(transcript: String, decodeMs: Long) {
        // Orb dialog: show "Ты: <phrase>" (clearing any prior answer) and cancel a pending clear so a
        // fresh turn keeps the block visible. The pill has already flipped to "Думаю" at the call site.
        showHeardHook(transcript)
        val command = AgentNameMatcher.stripLeadingName(transcript, agentIdentity().name)
        cancelScheduledClear()
        Log.i(TAG, "Continuous session utterance: decodeMs=$decodeMs")

        // Echo filter: only check if the transcript had no agent name prefix (if it did have the name,
        // stripLeadingName would have returned something different). Name-gated barge-in always passes.
        // A silent drop: no earcon, no state change, no spoken "Не понял" -- speaking that phrase
        // would itself be noteSpoken'd and risk being echo-caught again, looping the agent's own voice.
        if (transcript == command && echoFilter.isEcho(transcript)) {
            record(VoiceJournalEntry.Route.NONE, transcript, withDecodeMs(transcript, decodeMs),
                VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, "Эхо своей речи",
                "Echo filtered: transcript=\"$transcript\"")
            return
        }

        // An unanswered clarifying question from the agent outranks NLU: the phrase
        // is the ANSWER ("водителя", "назови её Дом") and must reach ask() verbatim.
        val followUp = runCatching { agentOrchestrator.expectsFollowUp() }.getOrDefault(false)
        val res = if (followUp) null else resolve(command, currentLang())
        _state.value = VoiceUiState.Thinking
        if (res != null) apply(res, command, decodeMs) else agentFallback(command, decodeMs)
    }

    /** Appends the continuous-session decode latency to a journal detail string; a no-op
     *  (returns [text] unchanged) for the legacy one-shot path, which has no per-utterance
     *  decode timing. */
    private fun withDecodeMs(text: String, decodeMs: Long?): String =
        if (decodeMs != null) "$text decodeMs=$decodeMs" else text

    /** Side-effect-free resolution of a transcript to an actionable command (built-in catalog first,
     *  then user automations). Returns null when nothing matches — used both for early-fire probing
     *  of partials and for routing the final. */
    private suspend fun resolve(text: String, lang: VoiceLang): Resolution? =
        when (val r = NluParser.parse(text, lang)) {
            is ParseResult.Command -> Resolution.Cmd(r.command)
            is ParseResult.RelativeTemp -> Resolution.RelTemp(r.sign)
            is ParseResult.Volume -> Resolution.Vol(r.payload)
            ParseResult.Unrecognized -> automationResolver.match(text)?.let { Resolution.Auto(it) }
        }

    /** Whether a resolved command may fire from a stable PARTIAL (early-fire). Aperture OPEN
     *  commands (window/sunroof/sunshade open / vent / half) are NOT: a bare noun like "открой окно"
     *  resolves to the all-windows command, yet the user may still add a qualifier ("...водителя")
     *  that yields a DIFFERENT command. For those we wait for the Vosk final so a mid-phrase pause
     *  can't open the wrong aperture. Everything else (close, climate, volume, lights, locks, seats,
     *  automations) early-fires as before. The final path is never gated — by then the phrase is
     *  complete. */
    private fun isEarlyFireable(res: Resolution): Boolean =
        !(res is Resolution.Cmd && (
            ActionDispatcher.isWindowOpenCommand(res.command) ||
                ActionDispatcher.isSunroofOpenCommand(res.command) ||
                ActionDispatcher.isSunshadeOpenCommand(res.command)))

    /** Execute a resolved command and set the terminal voice UI state. decodeMs is null for the
     *  legacy one-shot path (no per-utterance decode timing there). */
    private suspend fun apply(res: Resolution, transcript: String, decodeMs: Long? = null) {
        when (res) {
            is Resolution.Cmd -> execute(res.command, transcript, decodeMs)
            is Resolution.RelTemp -> dispatchRelativeTemp(res.sign, transcript, decodeMs)
            is Resolution.Vol -> dispatchVolume(res.payload, transcript, decodeMs)
            is Resolution.Auto -> fireAutomation(res.ruleId, transcript, decodeMs)
        }
    }

    /** Schedule the voice UI state's fall-back to Idle after a terminal state. Cancels any prior pending
     *  reset; the generation token + busy check guarantee a newer session's state is never
     *  clobbered (a reset only fires if its generation is still current and no session is active). */
    private fun scheduleIdleReset() {
        val gen = resetGen.incrementAndGet()
        resetJob?.cancel()
        resetJob = scope.launch {
            delay(idleResetDelayMs)
            if (resetGen.get() == gen && !busy.get()) {
                _state.value = VoiceUiState.Idle
            }
        }
    }

    /** Arms the orb-dialog clear after a terminal answer: waits out the spoken answer (immediate if
     *  TTS is off/idle, since speaking is already false), then hides the dialog block after a dwell.
     *  Cancels any previously armed clear so only the latest answer's timer runs. */
    private fun scheduleClear() {
        clearJob?.cancel()
        clearJob = scope.launch {
            ttsEngine.speaking.first { !it }   // wait out the spoken answer (immediate if TTS off)
            delay(dialogClearDelayMs)
            clearDialogHook()
        }
    }

    /** Cancels a pending orb-dialog clear so a new utterance keeps the block on screen. */
    private fun cancelScheduledClear() {
        clearJob?.cancel()
    }

    private suspend fun execute(command: String, transcript: String, decodeMs: Long? = null) {
        val snapshot = gate.vehicleSnapshot()
        // Fail CLOSED on unknown speed for window- and sunroof-open commands (both speed-gated
        // predicates after the T12 split): ActionDispatcher.getBlockReason() returns null
        // (no block) when data == null, so without this guard a voice "открой окна"/"открой люк"
        // at unknown speed would dispatch unchecked. Sunshade needs no speed and is not held.
        // Automations keep the fail-soft data==null semantics untouched.
        if (snapshot == null && (ActionDispatcher.isWindowOpenCommand(command) ||
                ActionDispatcher.isSunroofOpenCommand(command))) {
            earcon.fail()
            val reason = "Скорость неизвестна"
            _state.value = VoiceUiState.Blocked(reason)
            record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, reason,
                "NLU blocked (speed unknown): cmd=$command transcript=\"$transcript\"")
            announce("Голос", "Услышал: «$transcript». Отказ: $reason", "Не получилось")
            return
        }
        // The live snapshot is passed through so ActionDispatcher's safety gates
        // (>80 km/h window-open block, frunk standstill block) apply to voice
        // commands exactly as they do to automations (voice-agent spec invariant).
        // DispatchResult.reason holds the error string (not .message — real field name is `reason`).
        val result = actionDispatcher.dispatch(
            ActionDef(command = command, displayName = command, kind = "param"),
            data = snapshot
        )
        if (result.success) {
            earcon.ok()
            _state.value = VoiceUiState.Done(transcript)
            record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.OK, null,
                "NLU dispatched: cmd=$command transcript=\"$transcript\"")
            announce("Голос", "Услышал: «$transcript». Выполнено", "Готово")
            // Fire-and-forget: the note must never make the voice announce path wait on the
            // agent's mutex, which may be held by a concurrent ask().
            scope.launch { runCatching { agentOrchestrator.noteAction(transcript) } }
        } else {
            val reason = result.reason ?: transcript
            earcon.fail()
            _state.value = VoiceUiState.Blocked(reason)
            record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, reason,
                "NLU blocked: cmd=$command transcript=\"$transcript\" reason=$reason")
            announce("Голос", "Услышал: «$transcript». Отказ: $reason", "Не получилось")
        }
    }

    /** Relative temperature: read the live AC setpoint, step +-1, clamp 16..30,
     *  then dispatch as an absolute set. Fail-soft if the setpoint is unknown. */
    private suspend fun dispatchRelativeTemp(sign: Int, transcript: String, decodeMs: Long? = null) {
        val acTemp = gate.vehicleSnapshot()?.acTemp
        if (acTemp == null) {
            earcon.fail()
            val reason = "Не знаю текущую температуру"
            _state.value = VoiceUiState.Blocked(reason)
            record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, reason,
                "NLU blocked (acTemp unknown): transcript=\"$transcript\"")
            announce("Голос", "Услышал: «$transcript». Отказ: $reason", "Не получилось")
            return
        }
        val target = (acTemp + sign).coerceIn(16, 30)
        execute("设置温度$target", transcript, decodeMs)
    }

    /** Media volume is not speed-gated (not a window op). Dispatch as a
     *  media_volume action; the dispatcher's AudioManager applies it. */
    private suspend fun dispatchVolume(payload: String, transcript: String, decodeMs: Long? = null) {
        val result = actionDispatcher.dispatch(
            ActionDef(command = "media_volume", displayName = transcript, kind = "media_volume", payload = payload),
            data = gate.vehicleSnapshot()
        )
        if (result.success) {
            earcon.ok()
            _state.value = VoiceUiState.Done(transcript)
            record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.OK, null,
                "NLU dispatched: cmd=media_volume payload=$payload transcript=\"$transcript\"")
            announce("Голос", "Услышал: «$transcript». Выполнено", "Готово")
            scope.launch { runCatching { agentOrchestrator.noteAction(transcript) } }
        } else {
            val reason = result.reason ?: transcript
            earcon.fail()
            _state.value = VoiceUiState.Blocked(reason)
            record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, reason,
                "NLU blocked: cmd=media_volume payload=$payload transcript=\"$transcript\" reason=$reason")
            announce("Голос", "Услышал: «$transcript». Отказ: $reason", "Не получилось")
        }
    }

    private suspend fun fireAutomation(ruleId: Long, transcript: String, decodeMs: Long? = null) {
        when (val r = automationEngine.fireVoiceRule(ruleId, gate.vehicleSnapshot())) {
            is VoiceFireResult.Fired ->
                if (r.success) {
                    earcon.ok(); _state.value = VoiceUiState.Done(transcript)
                    record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.OK, null,
                        "NLU automation fired: ruleId=$ruleId transcript=\"$transcript\"")
                    announce("Голос", "Услышал: «$transcript». Выполнено", "Готово")
                    scope.launch { runCatching { agentOrchestrator.noteAction(transcript) } }
                } else {
                    earcon.fail(); _state.value = VoiceUiState.Blocked(transcript)
                    record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, transcript,
                        "NLU automation fire failed: ruleId=$ruleId transcript=\"$transcript\"")
                    announce("Голос", "Услышал: «$transcript». Отказ: $transcript", "Не получилось")
                }
            VoiceFireResult.Confirming -> {
                earcon.ok(); _state.value = VoiceUiState.Done(transcript)
                record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.OK, null,
                    "NLU automation confirming: ruleId=$ruleId transcript=\"$transcript\"")
                announce("Голос", "Услышал: «$transcript». Выполнено", "Готово")
                scope.launch { runCatching { agentOrchestrator.noteAction(transcript) } }
            }
            VoiceFireResult.ParkRequired -> {
                val reason = "Требуется паркинг"
                earcon.fail(); _state.value = VoiceUiState.Blocked(reason)
                record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, reason,
                    "NLU automation blocked (park required): ruleId=$ruleId transcript=\"$transcript\"")
                announce("Голос", "Услышал: «$transcript». Отказ: $reason", "Не получилось")
            }
            VoiceFireResult.SpeedUnknown -> {
                val reason = "Скорость неизвестна"
                earcon.fail(); _state.value = VoiceUiState.Blocked(reason)
                record(VoiceJournalEntry.Route.NLU, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.BLOCKED, reason,
                    "NLU automation blocked (speed unknown): ruleId=$ruleId transcript=\"$transcript\"")
                announce("Голос", "Услышал: «$transcript». Отказ: $reason", "Не получилось")
            }
            VoiceFireResult.NotFound -> {
                earcon.fail(); _state.value = VoiceUiState.NotUnderstood(transcript)
                record(VoiceJournalEntry.Route.NONE, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, null,
                    "NLU automation rule not found: ruleId=$ruleId transcript=\"$transcript\"")
                if (transcript.isNotBlank()) {
                    announce("Голос", "Не понял: «$transcript»", "Не понял")
                }
            }
        }
    }

    /** LLM-agent fallback for FINAL transcripts no local resolver matched. A disabled or
     *  unconfigured-off agent degrades to the pre-agent NotUnderstood behaviour. The agent's
     *  own tools re-check every dispatcher safety gate — nothing here bypasses them. */
    private suspend fun agentFallback(transcript: String, decodeMs: Long? = null) {
        if (transcript.isBlank()) {
            earcon.fail()
            _state.value = VoiceUiState.NotUnderstood(transcript)
            record(VoiceJournalEntry.Route.NONE, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, null,
                "Agent skipped: blank transcript")
            announce("Голос", "Не понял", "Не понял")
            return
        }
        val queue = if (gate.ttsEnabled()) runCatching { ttsEngine.startQueue() }.getOrNull() else null
        val streamed = StringBuilder()
        var queuedAny = false
        var r: AgentResult? = null
        coroutineScope {
            // LAZY so the body cannot run before askJob is assigned: the callback below gates
            // on askJob.isCancelled — a PER-TURN cancellation mark. The global stopRequested
            // flag alone is not enough: a following session (continuous or legacy) resets it,
            // which would un-mute late callbacks from this already-hard-stopped turn.
            lateinit var askJob: Job
            askJob = launch(start = CoroutineStart.LAZY) {
                r = agentOrchestrator.ask(transcript) { sentence ->
                    // Hard stop gate: the SSE loop can emit one more sentence between the ask
                    // job's cancellation and its next suspension point; this callback is
                    // non-suspend, so it must check for itself (the TTS queue is already
                    // superseded by tts.stop(), but the orb dialog repaint is not).
                    if (stopRequested.get() || askJob.isCancelled) return@ask
                    if (queue != null && runCatching { queue.enqueue(sentence) }.getOrDefault(false)) {
                        echoFilter.noteSpoken(sentence)
                        queuedAny = true
                        lastSpeakingSeenMs = System.currentTimeMillis()
                    }
                    if (streamed.isNotEmpty()) streamed.append(' ')
                    streamed.append(sentence)
                    showAnswerHook(streamed.toString())
                }
            }
            cancellableAskJob = askJob
            askJob.start()
            try {
                askJob.join()
            } finally {
                cancellableAskJob = null
            }
        }
        runCatching { queue?.finish() }
        val result = r ?: run {
            record(VoiceJournalEntry.Route.AGENT, transcript, withDecodeMs(transcript, decodeMs),
                VoiceJournalEntry.Outcome.ERROR, null, "Agent ask cancelled by name barge-in")
            return
        }
        // Hard stop gate: if the orb went off between ask.join() returning and this point,
        // the answer must not resurrect state, TTS, or the orb dialog (the path below has
        // no suspension point that would deliver the cancellation).
        if (stopRequested.get()) {
            record(VoiceJournalEntry.Route.AGENT, transcript, withDecodeMs(transcript, decodeMs),
                VoiceJournalEntry.Outcome.ERROR, null, "Agent answer suppressed: hard stop")
            return
        }
        when (result) {
            is AgentResult.Answer -> {
                // Gated on queuedAny (an actual successful enqueue), not "sentences arrived": the
                // queue can fail to start or to enqueue (TTS toggle on but voice model not ready /
                // native exception), and that turn must still fall through to the legacy
                // earcon+speak below instead of going completely silent. When queuedAny is true,
                // the reply was already spoken sentence-by-sentence via the queue above -- the
                // earcon and a second full-text speak() would be noise on top of live audio.
                if (!queuedAny) earcon.ok()
                _state.value = VoiceUiState.AgentAnswer(result.text)
                // Tool outcomes make the turn auditable from the journal screen: the
                // driver sees WHICH tools ran and whether each succeeded (no emoji — DiLink).
                val toolsNote = if (result.tools.isEmpty()) "" else
                    " [инструменты: " + result.tools.joinToString(", ") { "${it.name}:${if (it.ok) "ok" else "err"}" } + "]"
                record(VoiceJournalEntry.Route.AGENT, transcript, withDecodeMs(result.text + toolsNote, decodeMs), VoiceJournalEntry.Outcome.OK, null,
                    "Agent answered: transcript=\"$transcript\" tools=${result.tools.size}")
                if (!queuedAny && gate.ttsEnabled()) {
                    // See announce() for why this is stamped at call time, not only per-frame,
                    // and only when speak() actually enqueued playback.
                    if (runCatching { ttsEngine.speak(result.text) }.getOrDefault(false)) {
                        echoFilter.noteSpoken(result.text)
                        lastSpeakingSeenMs = System.currentTimeMillis()
                    }
                }
                // Orb dialog: this branch does not go through announce(), so feed the orb here.
                showAnswerHook(result.text)
                scheduleClear()
                // Wave P: a successful play_music closes the whole session after the reply -- the orb's
                // presence ducks the very music the agent just started. Music only; every other tool
                // keeps the dialogue open. Gated on sessionJob so the legacy single-shot path (which has
                // no session to close) is untouched.
                val closingJob = sessionJob
                if (closingJob != null && result.tools.any { it.name == "play_music" && it.ok }) {
                    scope.launch {
                        // speak()/enqueue() returns before the TTS worker flips speaking=true, so
                        // waiting for !speaking alone completes immediately and would cut the reply
                        // before it starts. Wait (bounded) for playback to begin, then for it to
                        // end; if it never begins (TTS off, synth failed), the grace elapses and
                        // the session still closes.
                        withTimeoutOrNull(SPEAK_START_GRACE_MS) { ttsEngine.speaking.first { it } }
                        ttsEngine.speaking.first { !it }   // let the agent finish its own reply first
                        // PTT restarting a session stops TTS -- the very signal this coroutine
                        // waits for -- so only close the session this reply belongs to, never a
                        // newer one the user has already started.
                        if (sessionJob === closingJob) stopContinuousSession()
                    }
                }
            }
            AgentResult.Disabled -> {
                earcon.fail(); _state.value = VoiceUiState.NotUnderstood(transcript)
                record(VoiceJournalEntry.Route.NONE, transcript, withDecodeMs(transcript, decodeMs), VoiceJournalEntry.Outcome.NOT_UNDERSTOOD, null,
                    "Agent disabled: transcript=\"$transcript\"")
                announce("Голос", "Не понял: «$transcript»", "Не понял")
            }
            is AgentResult.Error -> {
                earcon.fail(); _state.value = VoiceUiState.Blocked(result.message)
                record(VoiceJournalEntry.Route.AGENT, transcript, withDecodeMs(result.message, decodeMs), VoiceJournalEntry.Outcome.ERROR, result.message,
                    "Agent error: ${result.message} transcript=\"$transcript\"")
                announce("Голос", "Услышал: «$transcript». Отказ: ${result.message}", "Не получилось")
            }
        }
    }

    /** A resolved, actionable command. Decoupled from ParseResult/automation so a transcript can be
     *  resolved once (side-effect-free) and applied later — the basis of early-fire vs final routing. */
    private sealed interface Resolution {
        data class Cmd(val command: String) : Resolution
        data class RelTemp(val sign: Int) : Resolution
        data class Vol(val payload: String) : Resolution
        data class Auto(val ruleId: Long) : Resolution
    }

    companion object {
        private const val TAG = "VoiceController"

        // Dwell on a terminal state before auto-returning to Idle. Short on purpose —
        // long enough to read "не распознал", short enough to feel instant.
        private const val IDLE_RESET_DELAY_MS = 1200L

        // Wave D2: dwell before the orb dialog block ("Ты: …"/"Агент: …") is cleared, measured from
        // when the spoken answer finishes (or from when it is shown, if TTS is off).
        private const val DIALOG_CLEAR_MS = 6_000L

        // Consecutive identical partials (~100 ms each) a resolved command must hold before early-fire.
        // 3 ≈ 300 ms of "no new words", short enough to feel instant, long enough that a phrase still
        // being spoken (whose partial keeps changing) never fires prematurely.
        private const val EARLY_FIRE_STABLE_READS = 3

        // Continuous session (Wave B): silence auto-stop. Wave P removed the hard session cap --
        // long conversations must never be cut off; silence is the only automatic exit.
        private const val SILENCE_AUTOSTOP_MS = 30_000L

        // Wave P play_music auto-close: how long to wait for the reply's playback to actually
        // begin (speaking=false -> true) before giving up and closing anyway. Covers offline
        // synth latency and the online 2 s per-sentence timeout with margin.
        private const val SPEAK_START_GRACE_MS = 5_000L

        // Anti-self-trigger TTS mute window grace period, applied after ttsEngine.speaking
        // transitions to false (see the speakingWatcher in startContinuousSession()).
        private const val TTS_MUTE_GRACE_MS = 500L

        /** Pure so it is unit-testable without a real clock/session: whether capture should
         *  currently be muted -- either TTS is actively speaking right now, or we're still
         *  inside the post-speech grace window. */
        internal fun shouldMute(nowMs: Long, muteUntilMs: Long, speaking: Boolean): Boolean =
            speaking || nowMs < muteUntilMs
    }
}

/** Control-flow signal to auto-stop the continuous session on prolonged silence. A
 *  CancellationException so it tears down the collect chain (mic, VAD, recognizer) cleanly,
 *  mirroring StopCollect below. */
private object StopSession : CancellationException("voice-session-silence-timeout")

/** Control-flow signal to stop collecting the recognition flow on the first actionable event. A
 *  CancellationException so it tears down the upstream capture (releases the mic) cleanly; caught
 *  right around the collect, never reaching the session's failure handler. */
private object StopCollect : CancellationException("voice-asr-stop")
