package com.bydmate.app.data.remote

import android.util.Log
import com.bydmate.app.data.repository.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AlicePollingManager @Inject constructor(
    private val httpClient: OkHttpClient,
    private val controlClient: DiParsControlClient,
    private val settingsRepository: SettingsRepository
) {
    // Fast client with short timeouts for polling (main httpClient has 15s)
    private val pollClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()
    companion object {
        private const val TAG = "AlicePolling"
        private const val POLL_INTERVAL_MS = 2500L
        private const val STATE_REPORT_EVERY = 10 // every 10th poll (~25s)
    }

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private var pollCount = 0

    // Set by TrackingService from DiPlus data — no extra DiPlus calls
    @Volatile var latestData: DiParsData? = null

    fun start() {
        if (pollingJob?.isActive == true) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pollingJob = scope?.launch {
            Log.i(TAG, "Polling started")
            while (true) {
                try {
                    poll()
                } catch (e: Exception) {
                    Log.e(TAG, "Poll error: ${e.message}")
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope?.cancel()
        scope = null
        Log.i(TAG, "Polling stopped")
    }

    val isRunning: Boolean get() = pollingJob?.isActive == true

    private suspend fun poll() {
        val endpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "")
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
        if (endpoint.isBlank() || apiKey.isBlank()) return

        val request = Request.Builder()
            .url("$endpoint/api/poll")
            .header("X-Api-Key", apiKey)
            .build()

        val t0 = System.currentTimeMillis()
        val response = pollClient.newCall(request).execute()
        val elapsed = System.currentTimeMillis() - t0
        if (!response.isSuccessful) {
            Log.w(TAG, "Poll HTTP ${response.code} (${elapsed}ms)")
            return
        }
        val body = response.body?.string() ?: return
        val json = JSONObject(body)
        val commands = json.optJSONArray("commands") ?: return

        if (commands.length() == 0) {
            Log.d(TAG, "Poll OK (${elapsed}ms) - empty")
            // Report real device state every Nth poll
            pollCount++
            if (pollCount >= STATE_REPORT_EVERY) {
                pollCount = 0
                reportState(endpoint, apiKey)
            }
            return
        }
        Log.i(TAG, "Received ${commands.length()} command(s) (${elapsed}ms)")

        val ackIds = mutableListOf<String>()
        for (i in 0 until commands.length()) {
            val cmd = commands.getJSONObject(i)
            val id = cmd.getString("id")
            val command = cmd.getString("command")
            Log.i(TAG, "Executing: '$command' (id=$id)")
            val success = controlClient.sendCommand(command)
            Log.i(TAG, "Result: $command → ${if (success) "OK" else "FAIL"}")
            ackIds.add(id)
        }

        if (ackIds.isNotEmpty()) {
            ack(endpoint, apiKey, ackIds)
        }
    }

    private fun reportState(endpoint: String, apiKey: String) {
        val data = latestData ?: return
        try {
            val json = JSONObject().apply {
                data.windowFL?.let { put("windowFL", it) }
                data.windowFR?.let { put("windowFR", it) }
                data.windowRL?.let { put("windowRL", it) }
                data.windowRR?.let { put("windowRR", it) }
                data.sunroof?.let { put("sunroof", it) }
                data.trunk?.let { put("trunk", it) }
                data.lockFL?.let { put("lockFL", it) }
                data.acStatus?.let { put("acStatus", it) }
                data.acTemp?.let { put("acTemp", it) }
                data.acCirc?.let { put("acCirc", it) }
                data.insideTemp?.let { put("insideTemp", it) }
            }
            val request = Request.Builder()
                .url("$endpoint/api/state")
                .header("X-Api-Key", apiKey)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            pollClient.newCall(request).execute()
            Log.d(TAG, "State reported")
        } catch (e: Exception) {
            Log.e(TAG, "State report failed: ${e.message}")
        }
    }

    private fun ack(endpoint: String, apiKey: String, ids: List<String>) {
        try {
            val json = JSONObject().apply {
                put("ids", JSONArray(ids))
            }
            val request = Request.Builder()
                .url("$endpoint/api/ack")
                .header("X-Api-Key", apiKey)
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()
            pollClient.newCall(request).execute()
            Log.i(TAG, "Acked ${ids.size} command(s)")
        } catch (e: Exception) {
            Log.e(TAG, "Ack failed: ${e.message}")
        }
    }
}
