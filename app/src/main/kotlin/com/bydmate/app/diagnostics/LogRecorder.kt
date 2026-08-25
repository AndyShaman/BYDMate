package com.bydmate.app.diagnostics

import android.content.Context
import android.os.Environment
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
            val current = Session(proc, target)
            session = current
            published = current
            _state.value = State(
                isRecording = true,
                filePath = target.absolutePath,
                startedAtMs = System.currentTimeMillis(),
            )

            current.autoStopJob = scope.launch {
                delay(autoStopMs)
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
                    scope.launch { teardown(current) }
                }
            }
            return StartResult.Started(target)
        } catch (e: Exception) {
            // Same single exit path: a failure mid-start leaves neither a live
            // logcat nor a "recording" state behind.
            val started = published
            if (started != null) teardownLocked(started) else proc?.let { destroyQuietly(it) }
            return StartResult.Failed(e.message ?: "?")
        }
    }

    /** Stops the recording from any caller; null if nothing was running. */
    suspend fun stop(): Stopped? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val current = session ?: return@withLock null
            teardownLocked(current)
        }
    }

    private suspend fun teardown(current: Session) {
        mutex.withLock { teardownLocked(current) }
    }

    /**
     * The single exit path of a recording, idempotent: only the session that is
     * still the current one is torn down, so a self-terminating pipe and a
     * concurrent stop() cannot both kill (or double-report) it.
     */
    private fun teardownLocked(current: Session): Stopped? {
        if (session !== current) return null
        session = null

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
            "FidSubscription:*", "CameraProbe:*",
            // Split-screen wave: session/watchdog decisions, pill+picker overlay, widget tap.
            "SplitSessionMgr:*", "SplitOverlayCtrl:*", "SplitPillView:*",
            "WidgetController:*"
        )
    }
}
