package com.bydmate.app.data.remote

import com.bydmate.app.data.automation.ActionDispatcher
import com.bydmate.app.data.repository.SettingsRepository
import com.bydmate.app.data.vehicle.VehicleApi
import kotlinx.coroutines.CancellationException
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
    private val settingsRepository: SettingsRepository,
    private val sharedAdaptiveLoop: com.bydmate.app.data.loop.SharedAdaptiveLoop,
    private val vehicleApi: VehicleApi,
    private val appDispatcher: AliceAppActionDispatcher,
    private val apertureController: AliceApertureController,
) {
    companion object {
        private const val LONG_POLL_MS = 2000
        private const val IDLE_DELAY_MS = 100L
        private const val STATE_REPORT_EVERY = 10
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .build()

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null
    private var pollCount = 0

    @Volatile
    var latestData: DiParsData? = null
        private set

    fun start() {
        if (pollingJob?.isActive == true) return
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = owner
        owner.launch {
            sharedAdaptiveLoop.flow.collect {
                latestData = it
                apertureController.latestData = it
            }
        }
        pollingJob = owner.launch {
            while (true) {
                try {
                    poll()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    delay(1000L)
                }
                delay(IDLE_DELAY_MS)
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
        scope?.cancel()
        scope = null
    }

    val isRunning: Boolean
        get() = pollingJob?.isActive == true

    private suspend fun poll() {
        val endpoint = settingsRepository.getString(SettingsRepository.KEY_ALICE_ENDPOINT, "").trimEnd('/')
        val apiKey = settingsRepository.getString(SettingsRepository.KEY_ALICE_API_KEY, "")
        if (endpoint.isBlank() || apiKey.isBlank()) return

        val request = Request.Builder()
            .url("$endpoint/api/poll?wait_ms=$LONG_POLL_MS")
            .header("X-Api-Key", apiKey)
            .build()

        val commands = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return
            val body = response.body?.string() ?: return
            JSONObject(body).optJSONArray("commands") ?: return
        }

        val results = mutableListOf<AckResult>()
        for (index in 0 until commands.length()) {
            val command = commands.optJSONObject(index) ?: continue
            val id = command.optString("id")
            if (id.isBlank()) continue
            val action = command.optString("action").trim().lowercase()
            val result = execute(command, action)
            results += AckResult(id, result.isSuccess, result.exceptionOrNull()?.message?.take(160))
        }

        if (results.isNotEmpty()) ack(endpoint, apiKey, results)

        pollCount++
        if (pollCount >= STATE_REPORT_EVERY) {
            pollCount = 0
            reportState(endpoint, apiKey)
        }
    }

    private suspend fun execute(json: JSONObject, action: String): Result<Unit> {
        if (action in WINDOW_POSITION_ACTIONS) {
            val target = json.valueInt() ?: return Result.failure(IllegalArgumentException("invalid_window_position"))
            return apertureController.positionWindow(action, target, latestData?.speed)
        }

        if (action == "sunroof.position") {
            val target = json.valueInt() ?: return Result.failure(IllegalArgumentException("invalid_sunroof_position"))
            return apertureController.positionSunroof(target, latestData)
        }

        appDispatcher.dispatch(json, latestData)?.let { return it }

        val resolved = AliceBridgeCommandTranslator.resolve(json)
            ?: return Result.failure(IllegalArgumentException("unsupported_action"))

        val data = latestData
        if (ActionDispatcher.isRearTrunkOpenCommand(resolved.vehicleCommand)) {
            val speed = data?.speed ?: return Result.failure(IllegalStateException("rear_trunk_speed_unknown"))
            if (speed != 0) return Result.failure(IllegalStateException("rear_trunk_requires_parked"))
        }

        val blocked = ActionDispatcher.safetyBlockReason(resolved.vehicleCommand, data)
            ?: ActionDispatcher.speedGateBlockReason(resolved.vehicleCommand, data?.speed)
        if (blocked != null) return Result.failure(IllegalStateException(blocked.javaClass.simpleName))

        return vehicleApi.dispatch(resolved.vehicleCommand)
    }

    private fun reportState(endpoint: String, apiKey: String) {
        val data = latestData ?: return
        val body = JSONObject().apply {
            data.soc?.let { put("soc", it) }
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
            data.fanLevel?.let { put("fanLevel", it) }
            data.acWindMode?.let { put("acWindMode", it) }
            data.insideTemp?.let { put("insideTemp", it) }
            data.exteriorTemp?.let { put("exteriorTemp", it) }
        }

        val request = Request.Builder()
            .url("$endpoint/api/state")
            .header("X-Api-Key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    private fun ack(endpoint: String, apiKey: String, results: List<AckResult>) {
        val body = JSONObject().apply {
            put("ids", JSONArray(results.map { it.id }))
            put("results", JSONArray().apply {
                results.forEach { result ->
                    put(JSONObject().apply {
                        put("id", result.id)
                        put("success", result.success)
                        result.error?.let { put("error", it) }
                    })
                }
            })
        }
        val request = Request.Builder()
            .url("$endpoint/api/ack")
            .header("X-Api-Key", apiKey)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        runCatching { client.newCall(request).execute().close() }
    }

    private fun JSONObject.valueInt(): Int? = when (val value = opt("value")) {
        is Number -> value.toInt()
        is String -> value.toIntOrNull()
        else -> null
    }

    private data class AckResult(val id: String, val success: Boolean, val error: String?)

    private val WINDOW_POSITION_ACTIONS = setOf(
        "window.driver.position",
        "window.passenger.position",
        "window.rear_left.position",
        "window.rear_right.position",
    )
}
