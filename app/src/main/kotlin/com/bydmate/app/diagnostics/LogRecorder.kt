package com.bydmate.app.diagnostics

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The `--- end snapshot ---` section a finished recording closes with: the counters and journal
 * tails as they stand at the end, so a recording started before the drive does not leave only
 * the zeros of its header (#198). [sinceMs] is when the recording started.
 */
fun interface EndSnapshotSource {
    suspend fun lines(sinceMs: Long): List<String>
}

/**
 * Owns the logcat recording started from Settings: the process, the file and the
 * 2h auto-stop.
 *
 * All three used to live in SettingsViewModel, which dies when the user closes the
 * app window: the running logcat became unreachable (no way to stop it, indicator
 * back to "not recording", auto-stop cancelled with viewModelScope) and a second
 * start spawned a parallel logcat whose `logcat -c` wiped the first one's buffer.
 */
@Singleton
class LogRecorder internal constructor(
    private val appContext: Context,
    // Seams for tests: the production limits are unreachable in a unit test.
    private val autoStopMs: Long = LOG_MAX_DURATION_MS,
    private val maxSizeBytes: Long = LOG_MAX_SIZE_BYTES,
    private val endSnapshot: EndSnapshotSource? = null,
    // Seam for tests; production spawns real logcat processes. Last, so callers can
    // pass it as a trailing lambda.
    private val exec: (Array<String>) -> Process,
) {
    @Inject
    constructor(@ApplicationContext appContext: Context, endSnapshot: EndSnapshotSource) :
        this(appContext, endSnapshot = endSnapshot, exec = { Runtime.getRuntime().exec(it) })

    /** What a finished recording left behind, for the status line. */
    data class Stopped(val path: String, val sizeKb: Long)

    data class State(
        val isRecording: Boolean = false,
        val filePath: String? = null,
        val startedAtMs: Long = 0L,
        /** Last stop (manual or auto-stop), so a ViewModel created later can still report it. */
        val lastStopped: Stopped? = null,
    )

    sealed interface StartResult {
        data class Started(val file: File) : StartResult
        object AlreadyRecording : StartResult
        object NoStorage : StartResult
        data class Failed(val message: String) : StartResult
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    /** Everything one recording owns; replaced as a whole, never mutated piecemeal. */
    private class Session(val process: Process, val file: File, val startedAtMs: Long) {
        var pipeJob: Job? = null
        var autoStopJob: Job? = null

        // Distinguishes the two ways the pipe can end: the size limit finishes the
        // recording for good, while EOF or a read error may be a logcat that could not
        // start yet (READ_LOGS lands in the process only on the next app start).
        // Volatile: written by the pipe job, read by the teardown that follows it.
        @Volatile
        var endedByLimit = false
    }

    // Guards the session field, so start/stop/teardown never interleave: a stop()
    // racing a start() either finds no session yet (and does nothing) or waits for
    // the fully built one. Every mutation of [session] happens under this lock.
    private val mutex = Mutex()

    // Volatile: the pipe loop reads it outside the lock to bail out early on stop().
    @Volatile
    private var session: Session? = null

    /**
     * Starts recording. [headerWriter] fills the file with the diagnostic header
     * before the logcat pipe is attached. No-op if a recording is already running.
     */
    suspend fun start(headerWriter: suspend (File) -> Unit): StartResult =
        // Runs in the recorder scope: a caller that goes away mid-start (window closed
        // during the header write) must not leave the recording half-started.
        scope.async { mutex.withLock { startLocked(headerWriter) } }.await()

    private suspend fun startLocked(headerWriter: suspend (File) -> Unit): StartResult {
        if (session != null) return StartResult.AlreadyRecording

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val fileName = "bydmate_logs_$timestamp.txt"

        val saveDir = listOf(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            File("/storage/emulated/0/Download"),
            appContext.getExternalFilesDir(null)
        ).firstOrNull { dir ->
            dir != null && (dir.exists() || dir.mkdirs()) && dir.canWrite()
        } ?: return StartResult.NoStorage

        val target = File(saveDir, fileName)
        var proc: Process? = null
        var published: Session? = null
        try {
            // Diagnostic header — written directly to the file before the logcat pipe
            // so issue #19-style reports include device / setting context up front
            // instead of being buried in logcat noise.
            headerWriter(target)

            // The buffer is kept, not cleared: the minutes before the button press hold the
            // failure the user is about to report (#288). -T replays them from the buffer.
            val startedAtMs = System.currentTimeMillis()
            proc = exec(startArgs(startedAtMs))
            published = publishSession(proc, target, startedAtMs, autoStopMs)
            rememberPending(target, startedAtMs)
            return StartResult.Started(target)
        } catch (e: Exception) {
            // Same single exit path: a failure mid-start leaves neither a live
            // logcat nor a "recording" state behind.
            val started = published
            if (started != null) teardownLocked(started, endSection = false) else proc?.let { destroyQuietly(it) }
            return StartResult.Failed(e.message ?: "?")
        }
    }

    /**
     * Makes [proc] the current recording: publishes the state, arms the auto-stop
     * for [autoStopIn] and attaches the pipe. Shared by a fresh start and a resume,
     * which differ only in the file, the start time and the remaining time.
     */
    private fun publishSession(
        proc: Process,
        target: File,
        startedAtMs: Long,
        autoStopIn: Long,
    ): Session {
        val current = Session(proc, target, startedAtMs)
        session = current
        _state.value = State(
            isRecording = true,
            filePath = target.absolutePath,
            startedAtMs = startedAtMs,
        )

        current.autoStopJob = scope.launch {
            delay(autoStopIn)
            teardown(current)
        }
        // The pipe owns the end of the recording: whether it ends on the size
        // limit, on EOF or on a read error, the same teardown kills logcat and
        // publishes the stopped state (the UI shows "saved" either way).
        current.pipeJob = scope.launch {
            try {
                pipeToFile(proc, target, current)
            } finally {
                // Detached: teardown takes the lock this coroutine may be
                // cancelled from, and the launch outlives that cancellation.
                scope.launch { teardown(current, keepPending = !current.endedByLimit) }
            }
        }
        return current
    }

    /**
     * Picks up a recording the process death interrupted (ignition off/on), appending
     * to the same file until the 2h window of the FIRST start runs out. False when
     * there is nothing to resume, the window expired or a recording is already running.
     */
    suspend fun resumeIfPending(): Boolean =
        scope.async { mutex.withLock { resumeLocked() } }.await()

    private fun resumeLocked(): Boolean {
        if (session != null) return false

        val prefs = pendingPrefs()
        val path = prefs.getString(KEY_FILE_PATH, null)
        val startedAtMs = prefs.getLong(KEY_STARTED_AT_MS, 0L)
        val now = System.currentTimeMillis()
        val elapsed = now - startedAtMs
        val target = path?.let { File(it) }
        // Finally invalid: nothing to come back to, so the pending record goes away.
        if (target == null || startedAtMs <= 0L || elapsed < 0L || elapsed >= autoStopMs ||
            (target.exists() && target.length() >= maxSizeBytes)
        ) {
            forgetPending()
            return false
        }
        // Merely not ready yet: external storage is often still unmounted this early
        // in the service start. Keep the pending record so a later attempt inside the
        // 2h window still resumes the recording.
        if (!target.exists()) return false

        var proc: Process? = null
        return try {
            // No `logcat -c` on resume: the buffer holds the very first seconds after
            // the head unit woke up, which is exactly what the recording is for.
            FileOutputStream(target, /* append = */ true).bufferedWriter().use { writer ->
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date(now))
                writer.write(
                    "=== LOG RESUMED after app restart at $stamp " +
                        "(uptime ${SystemClock.elapsedRealtime() / 1000}s) ==="
                )
                writer.newLine()
            }
            proc = exec(LOGCAT_ARGS)
            publishSession(proc, target, startedAtMs, autoStopMs - elapsed)
            true
        } catch (_: Exception) {
            // Same reasoning as the missing file above: a failed append or spawn may
            // well succeed on the next attempt, so the pending record stays.
            proc?.let { destroyQuietly(it) }
            false
        }
    }

    private fun pendingPrefs() = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun rememberPending(target: File, startedAtMs: Long) {
        pendingPrefs().edit()
            .putString(KEY_FILE_PATH, target.absolutePath)
            .putLong(KEY_STARTED_AT_MS, startedAtMs)
            .apply()
    }

    private fun forgetPending() {
        pendingPrefs().edit().remove(KEY_FILE_PATH).remove(KEY_STARTED_AT_MS).apply()
    }

    /**
     * Stops the recording from any caller; null if nothing was running. A user stop
     * also cancels a pending resume: a pipe that died on its own (EOF) keeps the
     * pending record for a retry, and the user's "stop" must win over that retry.
     */
    suspend fun stop(): Stopped? = withContext(Dispatchers.IO) {
        mutex.withLock {
            forgetPending()
            val current = session ?: return@withLock null
            teardownLocked(current)
        }
    }

    private suspend fun teardown(current: Session, keepPending: Boolean = false) {
        mutex.withLock { teardownLocked(current, keepPending) }
    }

    /**
     * The single exit path of a recording, idempotent: only the session that is
     * still the current one is torn down, so a self-terminating pipe and a
     * concurrent stop() cannot both kill (or double-report) it.
     */
    private suspend fun teardownLocked(
        current: Session,
        keepPending: Boolean = false,
        endSection: Boolean = !keepPending,
    ): Stopped? {
        if (session !== current) return null
        session = null
        // The user, the auto-stop and the size limit end the recording for good. A pipe
        // that merely died keeps the pending record, so a later attempt resumes it.
        if (!keepPending) forgetPending()

        current.autoStopJob?.cancel()
        current.pipeJob?.cancel()
        destroyQuietly(current.process)
        // Only a recording that ends for good gets the end section. NonCancellable: the
        // auto-stop runs this from the very job it just cancelled, and a caller leaving
        // (Settings closed) must not cut the section in half.
        if (endSection) withContext(NonCancellable) { appendEndSnapshot(current) }

        val stopped = Stopped(
            path = current.file.absolutePath,
            sizeKb = current.file.length() / 1024,
        )
        _state.value = State(lastStopped = stopped)
        return stopped
    }

    /** Appends the end section once the pipe has let go of the file. */
    private suspend fun appendEndSnapshot(current: Session) {
        val source = endSnapshot ?: return
        // The pipe closes (and flushes) its writer once the destroyed logcat hits EOF.
        withTimeoutOrNull(PIPE_CLOSE_TIMEOUT_MS) { current.pipeJob?.join() }
        val lines = try {
            withTimeoutOrNull(END_SNAPSHOT_BUDGET_MS) { source.lines(current.startedAtMs) }
                ?: listOf("(timed out)")
        } catch (e: Exception) {
            listOf("(failed: ${e.javaClass.simpleName})")
        }
        try {
            FileOutputStream(current.file, /* append = */ true).bufferedWriter().use { writer ->
                writer.write("--- end snapshot ---")
                writer.newLine()
                lines.forEach { writer.write(it); writer.newLine() }
            }
        } catch (_: Exception) {}
    }

    private fun destroyQuietly(proc: Process) {
        try {
            proc.destroy()
            if (!proc.waitFor(PROCESS_EXIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly()
            }
        } catch (_: Exception) {
        }
    }

    // Pipes logcat to file with a size limit; blocking, runs as a job on the IO scope.
    // Opened in append mode so the diagnostic header is preserved instead of overwritten.
    // Lines go out in batches (PipeFlushPolicy) and the file system is asked the size once per
    // batch, not per line: a flush and a stat per line were the recording's own load on the unit.
    private fun pipeToFile(proc: Process, target: File, current: Session) {
        val lock = Any()
        val policy = PipeFlushPolicy()
        var closed = false
        // Chars between flushes (a lower bound: Cyrillic or CJK take 2-3 bytes each), the file's
        // real length after every flush, the idle one included. Guarded by [lock].
        var size = target.length()
        try {
            proc.inputStream.bufferedReader().use { reader ->
                val writer = FileOutputStream(target, /* append = */ true).bufferedWriter()
                // A quiet logcat must not keep its last lines in memory: they are the ones a
                // user pulling the file mid-recording needs.
                val idleFlusher = scope.launch {
                    while (isActive) {
                        delay(PipeFlushPolicy.MAX_DELAY_MS)
                        synchronized(lock) {
                            if (!closed && policy.dueIdle(System.currentTimeMillis())) {
                                runCatching { writer.flush() }
                                policy.flushed()
                                size = target.length()
                            }
                        }
                    }
                }
                try {
                    var line = reader.readLine()
                    while (line != null && session === current) {
                        if (LogcatLineFilter.keep(line)) {
                            // Stop if file exceeds size limit
                            if (synchronized(lock) { size > maxSizeBytes }) {
                                current.endedByLimit = true
                                synchronized(lock) {
                                    writer.write("--- LOG STOPPED: file size limit reached (50 MB) ---")
                                    writer.newLine()
                                }
                                break
                            }
                            synchronized(lock) {
                                writer.write(line)
                                writer.newLine()
                                size += line.length + 1
                                if (policy.onLine(System.currentTimeMillis())) {
                                    writer.flush()
                                    policy.flushed()
                                    size = target.length()
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                } finally {
                    idleFlusher.cancel()
                    synchronized(lock) {
                        closed = true
                        runCatching { writer.close() }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val LOG_MAX_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours auto-stop
        private const val LOG_MAX_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB max
        private const val PROCESS_EXIT_TIMEOUT_MS = 500L // grace period before destroyForcibly
        private const val PIPE_CLOSE_TIMEOUT_MS = 2_000L
        // The end section reads a few journals and in-memory counters; it must not hold the
        // recorder lock (and the next start) for long.
        private const val END_SNAPSHOT_BUDGET_MS = 3_000L
        // How far back a fresh recording replays the logcat buffer (the buffer itself is the
        // real limit: ~256 KB on DiLink).
        private const val PRE_ROLL_MS = 15 * 60 * 1000L

        // Survives process death so a recording interrupted by ignition-off resumes.
        private const val PREFS_NAME = "log_recorder"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_STARTED_AT_MS = "started_at_ms"

        /** A fresh recording: [LOGCAT_ARGS] replaying the buffer from [PRE_ROLL_MS] before [nowMs]. */
        internal fun startArgs(nowMs: Long): Array<String> {
            val since = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date(nowMs - PRE_ROLL_MS))
            return arrayOf("logcat", "-v", "time", "-T", since) + LOGCAT_ARGS.copyOfRange(3, LOGCAT_ARGS.size)
        }

        private val LOGCAT_ARGS = arrayOf(
            "logcat", "-v", "time",
            "-s", "BootReceiver:*",
            "TrackingService:*", "TripTracker:*",
            "HistoryImporter:*", "EnergyDataReader:*",
            "AutoserviceClient:*", "AdbOnDevice:*",
            "IternioTelemetryClient:*", "BatteryHealthRepository:*",
            "ChargesViewModel:*", "ChargeRepository:*",
            // v3.0.3: widen coverage to write/daemon/automation subsystems
            "HelperClient:*", "HelperBootstrap:*",
            "ActionDispatcher:*", "VehicleApiImpl:*",
            "AutomationEngine:*", "AutoserviceDetector:*",
            "SteeringWheelKeySvc:*",
            // v3.6: voice/audio diagnostics (issue #78 + Song volume reports)
            "AudioCapture:*", "SherpaTtsEngine:*", "VoiceController:*",
            // HUD wave: SOME/IP output + cluster projection diagnostics
            "HudController:*", "HudSomeIpBridge:*", "HudPushLoop:*",
            // HUD navigation status (app and daemon share the tag) and the HUD check's hudprobe lines.
            "HudArming:*", "HudCheck:*",
            // HUD ways 2 and 3: per-route channels, CAN counts, the family's services.
            "HudWayChannels:*",
            "ClusterProjection:*",
            // Direct projection wave: helper daemon (freeform switch diagnostics; visible
            // only once READ_LOGS is granted AND the app process restarted - the daemon
            // runs under the shell uid), guidance feed transitions, grant self-heal.
            "bydmate_helper:*", "HelperBinderRx:*", "HudIconLoader:*",
            "NavA11yFeed:*", "NavGuidanceHub:*", "GrantSelfHeal:*",
            // Amap-channel wave: notification lane + parser tags.
            "MediaSessionListener:*", "NaviNotifLane:*", "NaviNotifParser:*",
            // Cluster music card: what the bridge decided and wrote.
            "ClusterMusicBridge:*",
            // Blindspot wave: AVM camera probe. FidPush carries the daemon's push
            // subscription (register results, events) and the app's apply lines.
            "FidPush:*", "TechPanel:*", "CameraProbe:*", "BlindSpot:*", "CameraMonitor:*",
            // Diagnostic fid recorder (-test builds): summary only — the events go to its own
            // file in /sdcard/Download, not here.
            "FidRec:*",
            // Split-screen wave: session/watchdog decisions, pill+picker overlay, widget tap.
            "SplitSessionMgr:*", "SplitOverlayCtrl:*", "SplitPillView:*",
            "WidgetController:*",
            // DiLink 4 a11y stuck-binding diagnostics: who force-stops / kills our process
            // ("Force stopping com.bydmate.app", "Killing ...") and the framework's own
            // accessibility bookkeeping around ignition off/on.
            "ActivityManager:I", "AccessibilityManagerService:*",
            // #180: the "decode rejected" line lives on this tag and was missing from the filter.
            "NativeParsReader:*",
            // ADB restore on firmwares that close port 5555 at every reboot: one line per state,
            // plus the protocol client, which reports the TLS upgrade and the handshake outcome.
            "AdbRestore:*", "AdbProtocolClient:*",
            // Window channel probe verdicts (percent family vs CTRL, #79/#64).
            "WindowChannelRouter:*",
            // Offline dictionary: load result, self-test and the per-phrase miss line.
            "NluParser:*",
            // Firmware fid catalog: where it came from and every address it moved.
            "FidCatalog:*",
            // Agent wave: one line per model round, tool call and finished turn.
            "AgentLoop:*",
            // Auto-«Поехали»: the click on the Navigator's route preview (ActionDispatcher
            // carries the split exit/restore and the wait lines around it).
            "NaviGoButton:*",
            // Consumption-vs-temperature screen: one line per open with the sample counts.
            "TripTemperature:*",
            // Trip recorder: open/close lines carry the outside temperature of both ends.
            "TripRecorder:*",
            // Tariff periods: period saves, recalculation totals and the per-trip price choice.
            "TARIFF:*",
            // Post-3.17.5 wave: auto backup (scheduler, worker, runner), backup restore path,
            // post-restore check and the APK cleanup after an update. Their acceptance lines
            // never reached the recorded log because the tags were missing here.
            "AutoBackup:*", "BackupManager:*", "PostRestoreCheck:*", "UpdateApkCleanup:*",
            // Voice speed wave: which TTS source spoke and its per-sentence synth time, LLM
            // retries, the per-round token usage line (prompt cache hits via cached_tokens) and
            // the push-to-talk connection prewarm.
            "TtsRouter:*", "LlmAgentBackend:*", "OpenRouterClient:*", "HttpPrewarm:*",
            // Drive mode switch: one line per attempt (flag, target before/after, speed, verdict).
            "DriveModeChannel:*",
            // Telegram reports: build (items taken / skipped, length), send, outbox add/drain/drop.
            "TgReport:*",
            // Power-off report in the helper daemon: arm, power off/on, send attempts, outcome.
            "OffReport:*",
            // Agent tools (where_am_i steps, failed weather requests) and the two Overpass
            // clients behind where_am_i and find_chargers (per-server failures and answers),
            // plus the two Belarus charger sources ahead of Overpass.
            "AgentTools:*", "SettlementSearch:*", "ChargerSearchClient:*", "BetaMapClient:*", "MalankaGateway:*",
            // Log audit 2026-10-06: tags that wrote only to logcat - the second guidance layout,
            // Amap broadcast failures, the cluster frame and probe, the split engines, the
            // HUD text transliterator, the knob, steering heat, the network edge monitor, the
            // rule confirm overlay, the autostart worker, the crash log and the listener grant.
            "NavA11yExtractor:*", "HudAmapBroadcaster:*", "ClusterFrameUi7:*", "ClusterProbe:*",
            "Split37Engine:*", "NativeSplitLauncher:*", "HudTextSanitizer:*", "KnobPlayPause:*",
            "SteeringHeatChannel:*", "NetworkAvailMon:*", "ConfirmOverlay:*", "ServiceStartWorker:*",
            "CrashLog:*", "MediaSessionGrant:*",
            // The event journal: one line per user action, decision and failure, by=#id links.
            "Trace:*"
        )
    }
}
