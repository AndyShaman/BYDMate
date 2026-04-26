package com.bydmate.app.data.autoservice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to the on-device ADB daemon at 127.0.0.1:5555 (DiLink has WiFi
 * ADB enabled in dev settings) using a persistent RSA keypair stored in
 * the Android Keystore. Once paired, exposes `exec(cmd)` for one-shot
 * shell commands.
 *
 * Why on-device ADB? `service call autoservice ...` requires either system
 * UID, hidden API access, or shell UID. BYDMate runs as a normal app —
 * ADB shell uid is the only path. See reference_adb_on_device_pattern.md.
 *
 * Library: com.cgutman:adblib (planned via JitPack — com.github.cgutman:AdbLib).
 * If unavailable, fallback is a hand-rolled port of EV Pro's AdbClient.java
 * (artifacts in .research/competitor/, see reference_competitor_byd_ev_pro.md).
 */
interface AdbOnDeviceClient {
    suspend fun isConnected(): Boolean
    /** Executes a one-shot shell command and returns stdout, or null on failure. */
    suspend fun exec(cmd: String): String?
    /** Closes any underlying socket. Idempotent. */
    suspend fun shutdown()
}

@Singleton
class AdbOnDeviceClientImpl @Inject constructor(
    private val context: Context
) : AdbOnDeviceClient {

    @Volatile private var connection: AdbConnectionHandle? = null

    /**
     * Wraps an active adblib connection plus a guard against reentrancy.
     * Replace AdbConnectionHandle with the concrete adblib type during
     * the manual smoke step below — kept abstract here so the class
     * compiles before adblib is wired up.
     */
    private class AdbConnectionHandle

    override suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        connection != null && tryPing()
    }

    override suspend fun exec(cmd: String): String? = withContext(Dispatchers.IO) {
        // Structural barrier against accidental WRITE — only allow GETs to autoservice.
        require(cmd.matches(WRITE_BARRIER_REGEX)) {
            "AdbOnDeviceClient: refused command (write barrier): $cmd"
        }
        try {
            ensureConnected()
            doExec(cmd)
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            null
        }
    }

    override suspend fun shutdown() {
        withContext(Dispatchers.IO) {
            try {
                connection = null
            } catch (_: Exception) { /* idempotent */ }
        }
    }

    private suspend fun ensureConnected() {
        if (connection != null) return
        // TODO(adblib smoke): instantiate adblib connection here. See "Manual smoke
        // section" in this task's commit body. Throws on RSA pairing rejection or
        // socket failure — caller treats as null exec result.
        throw IllegalStateException("ADB connection not yet wired — Phase 1 smoke pending")
    }

    private fun tryPing(): Boolean = false  // wired during smoke — see commit body

    private fun doExec(cmd: String): String? {
        // TODO(adblib smoke): adb shell <cmd> via the persistent connection.
        return null
    }

    companion object {
        private const val TAG = "AdbOnDevice"
        private const val HOST = "127.0.0.1"
        private const val PORT = 5555

        // Block ANY write attempt at the boundary.
        // Allow only: service call autoservice <5|7|9> i32 <dev> i32 <fid>
        // Rejects tx=6 (setInt), tx=8 (setBuffer), and arbitrary shell.
        private val WRITE_BARRIER_REGEX = Regex("""^service call autoservice [579] i32 \d+ i32 -?\d+$""")
    }
}
