package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to the on-device ADB daemon at 127.0.0.1:5555 (DiLink has WiFi
 * ADB enabled in dev settings) using a persistent RSA keypair stored in
 * `filesDir/adb_keys/`. Once paired, exposes `exec(cmd)` for one-shot
 * shell commands.
 *
 * Why on-device ADB? `service call autoservice ...` requires either system
 * UID, hidden API access, or shell UID. BYDMate runs as a normal app —
 * ADB shell uid is the only path. See `reference_adb_on_device_pattern.md`.
 *
 * Implementation: hand-rolled binary ADB protocol in [AdbProtocolClient]
 * (no external deps, no `adblib`). Auth flow uses standard RSA pubkey on
 * port 5555 — no TLS pairing / 6-digit code, the user accepts the «Allow USB
 * debugging?» dialog directly on DiLink.
 */
interface AdbOnDeviceClient {
    /** Initiates the ADB handshake. Suspends until handshake completes or fails. */
    suspend fun connect(): Result<Unit>
    suspend fun isConnected(): Boolean
    /** Executes a one-shot shell command and returns stdout, or null on failure. */
    suspend fun exec(cmd: String): String?
    /**
     * Grants PACKAGE_USAGE_STATS appop to our own package via shell uid. The
     * only non-autoservice write we permit through this client — needed so
     * UsageStatsManager.queryEvents returns data for camera-overlay detection.
     * No-op effect if already granted. Returns true on success.
     */
    suspend fun grantUsageStatsAppop(packageName: String): Boolean

    /**
     * Spawns the helper daemon under shell uid via app_process, using the app's
     * own signed base.apk as CLASSPATH (no dex push — integrity comes from the
     * APK signature). The daemon registers itself as the `bydmate_helper` binder
     * service; reachability is verified separately by the binder client's ping,
     * not here. Returns true if the spawn command was dispatched without error
     * (NOT a liveness guarantee).
     *
     * Hardcoded cmdline + process name — caller cannot inject. Uses the raw
     * protocol exec path (the public exec() write barrier only permits autoservice
     * GETs and would reject this).
     */
    suspend fun spawnHelper(): Boolean

    /** Reads the daemon's stdout/stderr log (READY / ERR lines) for diagnostics. Null on transport error. */
    suspend fun readHelperLog(): String?

    /** Read-only check: is a process named `bydmate_helper` running? */
    suspend fun helperHeartbeat(): Boolean

    /** Closes any underlying socket. Idempotent. */
    suspend fun shutdown()
}

@Singleton
class AdbOnDeviceClientImpl @Inject constructor(
    @ApplicationContext context: Context,
    private val keyStore: AdbKeyStore,
) : AdbOnDeviceClient {

    /**
     * Test seam — UNIT TESTS ONLY. Lets the test layer swap the real
     * socket-backed protocol for a fake. Default factory creates the real
     * [AdbProtocolClient] with the persisted keypair.
     */
    @Suppress("unused")  // assigned via internal setter from tests
    internal var protocolFactory: () -> AdbProtocol = {
        AdbProtocolClient(keyStore.loadOrGenerate())
    }

    @Volatile private var protocol: AdbProtocol? = null

    // used for packageCodePath when spawning the helper daemon
    private val ctx = context

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val p = protocol ?: protocolFactory().also { protocol = it }
            val ok = p.connect()
            if (ok) Result.success(Unit) else Result.failure(IOException("ADB connect refused"))
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        protocol?.isConnected() ?: false
    }

    override suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        // Structural barrier against accidental WRITE — only allow GETs to autoservice.
        require(cmd.matches(WRITE_BARRIER_REGEX)) {
            "AdbOnDeviceClient: refused command (write barrier): $cmd"
        }
        val p = protocol ?: return@withContext null
        try {
            // runInterruptible bridges coroutine cancellation to the blocking
            // ADB socket read: a parent withTimeout/withTimeoutOrNull will
            // post a Thread.interrupt(), which the socket implementation
            // raises as InterruptedIOException and unwinds out of p.exec.
            // Without this wrapper, withTimeoutOrNull(900ms) abandons the
            // coroutine but the socket read keeps blocking up to 5 s
            // (SOCKET_TIMEOUT_MS), pinning single-flight in TrackingService.
            kotlinx.coroutines.runInterruptible { p.exec(cmd) }
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    override suspend fun grantUsageStatsAppop(packageName: String): Boolean = withContext(Dispatchers.IO) {
        // Only permit our own package — never grant appops to anything else.
        require(packageName.matches(PACKAGE_NAME_REGEX)) {
            "grantUsageStatsAppop: refused package $packageName"
        }
        val cmd = "appops set $packageName GET_USAGE_STATS allow"
        val p = protocol ?: return@withContext false
        try {
            // appops prints nothing on success; null means transport error.
            // Treat any non-null output as failure (e.g. "Bad permission") too.
            val out = p.exec(cmd) ?: return@withContext false
            out.isBlank()
        } catch (e: Exception) {
            Log.w(TAG, "grantUsageStatsAppop failed: ${e.message}")
            false
        }
    }

    override suspend fun spawnHelper(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: run {
            val r = connect()
            if (r.isFailure) return@withContext false
            protocol ?: return@withContext false
        }
        try {
            // Raw protocol exec — bypasses the public exec() write barrier by design
            // (this is not an autoservice GET). Hardcoded, no caller input.
            // CLASSPATH = the app's own signed base.apk; setsid + redirect detach the
            // daemon from the ADB exec channel so it survives the shell closing.
            val spawnCmd =
                "CLASSPATH=${ctx.packageCodePath} setsid app_process /system/bin " +
                "--nice-name=$HELPER_PROCESS_NAME com.bydmate.app.helper.HelperDaemon " +
                "${android.os.Process.myUid()} </dev/null >$HELPER_LOG_PATH 2>&1 &"
            p.exec(spawnCmd)
            true
        } catch (e: Exception) {
            Log.w(TAG, "spawnHelper failed: ${e.message}")
            false
        }
    }

    override suspend fun readHelperLog(): String? = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext null
        runCatching { p.exec("cat $HELPER_LOG_PATH") }.getOrNull()
    }

    override suspend fun helperHeartbeat(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        val out = runCatching { p.exec("ps -A -o NAME") }.getOrNull() ?: return@withContext false
        out.lineSequence().any { it.trim() == HELPER_PROCESS_NAME }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            try {
                protocol?.disconnect()
            } catch (_: Exception) { /* idempotent */ }
            protocol = null
        }
    }

    companion object {
        private const val TAG = "AdbOnDevice"

        // Block ANY write attempt at the boundary.
        // Allow only: service call autoservice <5|7|9> i32 <dev> i32 <fid>
        // Rejects tx=6 (setInt), tx=8 (setBuffer), and arbitrary shell.
        private val WRITE_BARRIER_REGEX = Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")

        // Narrow whitelist for grantUsageStatsAppop — only our own package.
        private val PACKAGE_NAME_REGEX = Regex("""^com\.bydmate\.app$""")

        // Helper daemon — hardcoded so neither caller can inject paths/cmdlines.
        private const val HELPER_PROCESS_NAME = "bydmate_helper"
        private const val HELPER_LOG_PATH = "/data/local/tmp/bydmate_helper.log"
    }
}
