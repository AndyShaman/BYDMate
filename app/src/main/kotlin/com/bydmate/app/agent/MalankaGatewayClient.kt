package com.bydmate.app.agent

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Charging stations of three Belarus networks from Malanka's public map gateway, no key: the
 * backup when the BETA map is down. Malanka lists stations only; Evika and Battery Fly add
 * whether the station as a whole is free (AVAILABLE / FULLY_USED). No connectors, power or
 * price. The three lists are asked in parallel and merged; one network down leaves the others.
 */
class MalankaGatewayClient(private val http: OkHttpClient) {

    private class Network(val path: String, val operator: String)

    /** Test seams. Measured from the head unit 28.09: 0.7 s per answer. */
    internal var baseUrl = "https://apigateway.malankabn.by/central-system/api/v1/"
    internal var callTimeoutMs = 2_500L

    suspend fun stations(): Result<List<ChargerStation>> = coroutineScope {
        val gatewayHttp = http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
        val lists = NETWORKS.map { net -> async { fetch(gatewayHttp, net) } }.awaitAll().filterNotNull()
        if (lists.isEmpty()) Result.failure(MapBadAnswer("every network failed"))
        else Result.success(lists.flatten())
    }

    /** One network's stations, or null when it failed (already logged). */
    private suspend fun fetch(gatewayHttp: OkHttpClient, net: Network): List<ChargerStation>? {
        val url = "$baseUrl${net.path}locations/map?connectorTypes="
        val host = url.toHttpUrl().host
        val request = Request.Builder().url(url).header("User-Agent", MAP_USER_AGENT).build()
        val startMs = monotonicMs()
        return try {
            val stations = gatewayHttp.newCall(request).awaitRead { resp ->
                parse(resp.body?.string().orEmpty(), net.operator)
            }
            Log.i(TAG, "gateway ${net.operator} $host: ok, ${stations.size} stations, ${monotonicMs() - startMs}ms")
            Trace.call("gateway", host, monotonicMs() - startMs)
            stations
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "gateway ${net.operator} $host: ${mapOutcome(e)}, ${monotonicMs() - startMs}ms")
            Trace.call("gateway", host, monotonicMs() - startMs, e, mapOutcome(e))
            null
        } catch (e: CancellationException) {
            Log.i(TAG, "gateway ${net.operator} $host: cancelled or out of time after ${monotonicMs() - startMs}ms")
            throw e
        }
    }

    private fun parse(body: String, operator: String): List<ChargerStation> {
        if (body.isBlank()) throw MapBadAnswer("empty body")
        val arr = JSONArray(body)
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val lat = o.optDouble("latitude")
            val lon = o.optDouble("longitude")
            if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
            ChargerStation(
                name = o.text("name") ?: o.text("address") ?: DEFAULT_NAME,
                address = o.text("address"),
                operator = operator,
                lat = lat,
                lon = lon,
                status = o.text("status")?.let(STATUSES::get),
            )
        }
    }

    /** Android's org.json turns a JSON null into the string "null" in optString: keep it null. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private companion object {
        const val TAG = "MalankaGateway"
        const val DEFAULT_NAME = "Зарядная станция"
        val NETWORKS = listOf(Network("", "Malanka"), Network("evika/", "Evika"), Network("battery-fly/", "Battery Fly"))
        val STATUSES = mapOf(
            "AVAILABLE" to StationStatus.FREE,
            "FULLY_USED" to StationStatus.BUSY,
            "UNAVAILABLE" to StationStatus.DOWN,
        )
    }
}
