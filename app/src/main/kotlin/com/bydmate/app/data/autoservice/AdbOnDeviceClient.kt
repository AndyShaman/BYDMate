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
     * Starts D+ MainService directly via shell uid `am start-foreground-service`.
     * Used by the watchdog when D+ has gone silent — `startActivity` against
     * StartMainServiceActivity crashes on Android 12+ due to background-service
     * restrictions in cached/idle state, but the shell-uid path bypasses them.
     * Returns true when am succeeded.
     */
    suspend fun launchDiPlusService(): Boolean
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

    @Suppress("unused")  // kept for future-proofing; AdbKeyStore already has Context.
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
            p.exec(cmd)
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

    override suspend fun launchDiPlusService(): Boolean = withContext(Dispatchers.IO) {
        val p = protocol ?: return@withContext false
        try {
            // am start-foreground-service prints "Starting service: Intent { ... }"
            // on success and "Error: ..." on failure. Treat any output starting
            // with "Error" as a failure.
            val out = p.exec(LAUNCH_DIPLUS_CMD) ?: return@withContext false
            !out.trimStart().startsWith("Error")
        } catch (e: Exception) {
            Log.w(TAG, "launchDiPlusService failed: ${e.message}")
            false
        }
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

        // Hardcoded — no params, so no injection surface.
        private const val LAUNCH_DIPLUS_CMD =
            "am start-foreground-service -n com.van.diplus/com.van.diplus.service.MainService"
    }
}
