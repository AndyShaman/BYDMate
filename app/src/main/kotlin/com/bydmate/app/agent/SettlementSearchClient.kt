package com.bydmate.app.agent

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Settlements around a point from OpenStreetMap via the Overpass API (same servers as
 *  [ChargerSearchClient]). Backs the where_am_i tool: the model must name places from this
 *  list, never from a web search on raw coordinates. */
@Singleton
class SettlementSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** [place] is the OSM place value (city/town/village/hamlet/suburb/isolated_dwelling). */
    data class Settlement(val name: String, val place: String, val lat: Double, val lon: Double)

    /** Test seam so unit tests point at MockWebServer. Tried in order; first clean answer wins. */
    internal var endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    /** Test seams. One endpoint may take [callTimeoutMs] (body included); the whole search,
     *  fallback included, never takes longer than [totalTimeoutMs], so a voice turn waits at
     *  most that long for where_am_i. */
    internal var callTimeoutMs = 8_000L
    internal var totalTimeoutMs = 12_000L

    /** Villages and smaller within [smallRadiusM], cities and towns within [townRadiusM]:
     *  a village radius wide enough for towns would pull hundreds of hamlets. */
    suspend fun search(lat: Double, lon: Double, smallRadiusM: Int, townRadiusM: Int): Result<List<Settlement>> =
        withContext(Dispatchers.IO) {
            val query = "[out:json][timeout:10];(" +
                "node[\"place\"~\"^(village|hamlet|suburb|isolated_dwelling)$\"](around:$smallRadiusM,$lat,$lon);" +
                "node[\"place\"~\"^(city|town)$\"](around:$townRadiusM,$lat,$lon);" +
                ");out body;"
            Log.i(TAG, "overpass request: small=${smallRadiusM}m town=${townRadiusM}m")
            val overpassHttp = http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
            val formBody = FormBody.Builder().add("data", query).build()
            // withTimeoutOrNull turns only its own deadline into null; a cancelled voice turn
            // still unwinds as CancellationException (rethrown below, never a Result).
            withTimeoutOrNull(totalTimeoutMs) {
                try {
                    Result.success(searchEndpoints(overpassHttp, formBody))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Result.failure(e)
                }
            } ?: run {
                Log.w(TAG, "overpass: no answer within ${totalTimeoutMs}ms")
                Result.failure(IOException("серверы карт не ответили за ${totalTimeoutMs / 1000} с"))
            }
        }

    private suspend fun searchEndpoints(overpassHttp: OkHttpClient, formBody: FormBody): List<Settlement> {
        var lastError: IOException? = null
        for (ep in endpoints) {
            try {
                val request = Request.Builder().url(ep).post(formBody).build()
                val body = await(overpassHttp.newCall(request))
                val result = parse(ep, body)
                Log.i(TAG, "overpass $ep: ${result.size} settlements")
                return result
            } catch (e: IOException) {
                // A cancel that raced the failure: stop here instead of trying the next server.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "overpass $ep failed: ${e.message}")
                lastError = e
            } catch (e: CancellationException) {
                Log.i(TAG, "overpass $ep: cancelled or out of time, in-flight call dropped")
                throw e
            }
        }
        throw IOException("серверы карт недоступны: ${lastError?.message}")
    }

    /** Overpass reports runtime errors (timeout, out of memory) inside a 200 answer via
     *  `remark`, often with an empty `elements`: that is a failed server, not "nothing around". */
    private fun parse(ep: String, body: String): List<Settlement> {
        val json = try { JSONObject(body) } catch (_: org.json.JSONException) { null }
        val remark = json?.optString("remark").orEmpty()
        if (remark.isNotEmpty()) Log.i(TAG, "overpass $ep remark: $remark")
        val elements = json?.optJSONArray("elements")
            ?.takeUnless { remark.contains("error", ignoreCase = true) }
            ?: throw IOException(
                when {
                    json == null -> "bad JSON"
                    remark.isNotEmpty() -> "remark: $remark"
                    else -> "no elements array"
                }
            )
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.getJSONObject(i)
            val sLat = e.optDouble("lat")
            val sLon = e.optDouble("lon")
            if (sLat.isNaN() || sLon.isNaN()) return@mapNotNull null
            val tags = e.optJSONObject("tags") ?: return@mapNotNull null
            // Russian name first: border areas often carry a local-language "name".
            val name = tags.optString("name:ru").takeIf { it.isNotBlank() }
                ?: tags.optString("name").takeIf { it.isNotBlank() }
                ?: return@mapNotNull null
            Settlement(name, tags.optString("place"), sLat, sLon)
        }
    }

    /** Runs [call] on OkHttp's thread pool and cancels it with the coroutine (a blocking
     *  execute() would keep a cancelled voice turn waiting for the whole call timeout). The
     *  body is read in the callback; returns it for a 2xx answer, throws IOException otherwise. */
    private suspend fun await(call: Call): String = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                val body = try {
                    response.use { resp ->
                        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                        resp.body?.string().takeUnless { it.isNullOrBlank() } ?: throw IOException("empty body")
                    }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(body)
            }
        })
    }

    private companion object {
        const val TAG = "SettlementSearch"
    }
}
