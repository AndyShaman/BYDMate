package com.bydmate.app.diagnostics

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

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
    // Seam for tests; production spawns real logcat processes. Last, so callers can
    // pass it as a trailing lambda.
    private val exec: (Array<String>) -> Process,
) {
    @Inject
    constructor(@ApplicationContext appContext: Context) :
        this(appContext, exec = { Runtime.getRuntime().exec(it) })

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
    private class Session(val process: Process, val file: File) {
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

            // Clear logcat buffer and start continuous recording
            exec(arrayOf("logcat", "-c")).waitFor()

            proc = exec(LOGCAT_ARGS)
            val startedAtMs = System.currentTimeMillis()
            published = publishSession(proc, target, startedAtMs, autoStopMs)
            rememberPending(target, startedAtMs)
            return StartResult.Started(target)
        } catch (e: Exception) {
            // Same single exit path: a failure mid-start leaves neither a live
            // logcat nor a "recording" state behind.
            val started = published
            if (started != null) teardownLocked(started) else proc?.let { destroyQuietly(it) }
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
        val current = Session(proc, target)
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
    private fun teardownLocked(current: Session, keepPending: Boolean = false): Stopped? {
        if (session !== current) return null
        session = null
        // The user, the auto-stop and the size limit end the recording for good. A pipe
        // that merely died keeps the pending record, so a later attempt resumes it.
        if (!keepPending) forgetPending()

        current.autoStopJob?.cancel()
        current.pipeJob?.cancel()
        destroyQuietly(current.process)

        val stopped = Stopped(
            path = current.file.absolutePath,
            sizeKb = current.file.length() / 1024,
        )
        _state.value = State(lastStopped = stopped)
        return stopped
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
    private fun pipeToFile(proc: Process, target: File, current: Session) {
        try {
            proc.inputStream.bufferedReader().use { reader ->
                FileOutputStream(target, /* append = */ true)
                    .bufferedWriter().use { writer ->
                    var line = reader.readLine()
                    while (line != null && session === current) {
                        // Stop if file exceeds size limit
                        if (target.length() > maxSizeBytes) {
                            current.endedByLimit = true
                            writer.write("--- LOG STOPPED: file size limit reached (50 MB) ---")
                            writer.newLine()
                            break
                        }
                        writer.write(line)
                        writer.newLine()
                        writer.flush()
                        line = reader.readLine()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    companion object {
        private const val LOG_MAX_DURATION_MS = 2 * 60 * 60 * 1000L // 2 hours auto-stop
        private const val LOG_MAX_SIZE_BYTES = 50 * 1024 * 1024L // 50 MB max
        private const val PROCESS_EXIT_TIMEOUT_MS = 500L // grace period before destroyForcibly

        // Survives process death so a recording interrupted by ignition-off resumes.
        private const val PREFS_NAME = "log_recorder"
        private const val KEY_FILE_PATH = "file_path"
        private const val KEY_STARTED_AT_MS = "started_at_ms"

        private val LOGCAT_ARGS = arrayOf(
            "logcat", "-v", "time",
            "-s", "BootReceiver:*",
            "TrackingService:*", "TripTracker:*",
            "HistoryImporter:*", "EnergyDataReader:*",
            "AutoserviceClient:*", "AdbOnDeviceClient:*",
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
            "ClusterProjection:*",
            // Direct projection wave: helper daemon (freeform switch diagnostics; visible
            // only once READ_LOGS is granted AND the app process restarted - the daemon
            // runs under the shell uid), guidance feed transitions, grant self-heal.
            "bydmate_helper:*", "HelperBinderRx:*", "HudIconLoader:*",
            "NavA11yFeed:*", "NavGuidanceHub:*", "GrantSelfHeal:*",
            // Amap-channel wave: notification lane + parser tags.
            "MediaSessionListener:*", "NaviNotifLane:*", "NaviNotifParser:*",
            // Blindspot wave: observe-mode fid subscriptions + AVM camera probe.
            "FidSubscription:*", "CameraProbe:*", "BlindSpot:*",
            // Split-screen wave: session/watchdog decisions, pill+picker overlay, widget tap.
            "SplitSessionMgr:*", "SplitOverlayCtrl:*", "SplitPillView:*",
            "WidgetController:*"
        )
    }
}
