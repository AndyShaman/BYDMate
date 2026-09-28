package com.bydmate.app.agent

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Settlements around a point from OpenStreetMap via the Overpass API (same server as
 *  [ChargerSearchClient]); when Overpass fails, the point's own address from Nominatim reverse
 *  geocoding. Backs the where_am_i tool: the model must name places from this answer, never
 *  from a web search on raw coordinates. */
@Singleton
class SettlementSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** [place] is the OSM place value (city/town/village/hamlet/suburb/isolated_dwelling). */
    data class Settlement(val name: String, val place: String, val lat: Double, val lon: Double)

    /** What [search] found around the point. */
    sealed interface Surroundings {
        /** Overpass: settlement centres around the point. */
        data class Nearby(val settlements: List<Settlement>) : Surroundings

        /** Nominatim: the address of the point itself. [settlement] (OSM [place] city, town,
         *  village or hamlet) holds the point or lies next to it; there is no centre, so no
         *  distance. Any part may be missing. */
        data class Address(
            val settlement: String?,
            val place: String?,
            val road: String?,
            val district: String?,
            val region: String?,
            val country: String?,
        ) : Surroundings
    }

    /** Test seam so unit tests point at MockWebServer. Tried in order; first clean answer wins.
     *  The maps.mail.ru mirror is gone: the head unit lacks its TLS root (Android 12), and it
     *  answered in 9-12 s against the 8 s call timeout (field 28.09). */
    internal var endpoints = listOf("https://overpass-api.de/api/interpreter")

    /** Test seam: Nominatim reverse geocoding, asked once every Overpass endpoint has failed. */
    internal var nominatimUrl = "https://nominatim.openstreetmap.org/reverse"

    /** Endpoint that answered the most recent [search] call, for diagnostics; carries no place
     *  or coordinate data, safe for a user-shared log. */
    @Volatile var lastEndpoint: String? = null
        private set

    /** Test seams. One Overpass endpoint may take [callTimeoutMs], Nominatim
     *  [nominatimCallTimeoutMs] (body included); the whole search, fallback included, never takes
     *  longer than [totalTimeoutMs], so a voice turn waits at most that long for where_am_i. */
    internal var callTimeoutMs = 8_000L
    internal var nominatimCallTimeoutMs = 3_000L
    internal var totalTimeoutMs = 10_000L

    // Nominatim usage policy: at most one request per second. where_am_i is a rare question
    // asked by hand, so a request inside that second simply waits its turn.
    private val nominatimTurn = Mutex()
    private var lastNominatimMs: Long? = null // guarded by nominatimTurn

    /** Villages and smaller within [smallRadiusM], cities and towns within [townRadiusM]:
     *  a village radius wide enough for towns would pull hundreds of hamlets. */
    suspend fun search(lat: Double, lon: Double, smallRadiusM: Int, townRadiusM: Int): Result<Surroundings> =
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
                    Result.success(nearbyOrAddress(overpassHttp, formBody, lat, lon))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Result.failure(e)
                }
            } ?: run {
                Log.w(TAG, "search: no answer within ${totalTimeoutMs}ms")
                Result.failure(IOException("серверы карт не ответили за ${totalTimeoutMs / 1000} с"))
            }
        }

    private suspend fun nearbyOrAddress(overpassHttp: OkHttpClient, formBody: FormBody, lat: Double, lon: Double): Surroundings =
        try {
            Surroundings.Nearby(searchEndpoints(overpassHttp, formBody))
        } catch (overpassError: IOException) {
            try {
                reverse(lat, lon)
            } catch (e: IOException) {
                throw IOException("серверы карт недоступны: overpass ${outcome(overpassError)}, nominatim ${outcome(e)}")
            }
        }

    private suspend fun searchEndpoints(overpassHttp: OkHttpClient, formBody: FormBody): List<Settlement> {
        var lastError: IOException? = null
        for (ep in endpoints) {
            val host = ep.toHttpUrl().host
            val startMs = monotonicMs()
            try {
                val request = Request.Builder().url(ep).header("User-Agent", MAP_USER_AGENT).post(formBody).build()
                val body = await(overpassHttp.newCall(request))
                val result = parse(host, body)
                Log.i(TAG, "overpass $host: ok, ${result.size} settlements, ${monotonicMs() - startMs}ms")
                Trace.call("overpass", host, monotonicMs() - startMs)
                lastEndpoint = ep
                return result
            } catch (e: IOException) {
                // A cancel that raced the failure: stop here instead of trying the next server.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "overpass $host: ${outcome(e)}, ${monotonicMs() - startMs}ms")
                Trace.call("overpass", host, monotonicMs() - startMs, e, outcome(e))
                lastError = e
            } catch (e: CancellationException) {
                Log.i(TAG, "overpass $host: cancelled or out of time after ${monotonicMs() - startMs}ms, in-flight call dropped")
                throw e
            }
        }
        throw lastError ?: IOException("no overpass endpoint")
    }

    /** Nominatim reverse geocoding: the address of the nearest street (zoom 17 = major and minor
     *  streets, no buildings), names in Russian where OSM has them. */
    private suspend fun reverse(lat: Double, lon: Double): Surroundings.Address {
        awaitNominatimTurn()
        val url = nominatimUrl.toHttpUrl()
        val request = Request.Builder()
            .url(url.newBuilder()
                .addQueryParameter("format", "jsonv2")
                .addQueryParameter("lat", String.format(Locale.US, "%.6f", lat))
                .addQueryParameter("lon", String.format(Locale.US, "%.6f", lon))
                .addQueryParameter("zoom", NOMINATIM_ZOOM.toString())
                .addQueryParameter("addressdetails", "1")
                .addQueryParameter("accept-language", "ru")
                .build())
            .header("User-Agent", MAP_USER_AGENT)
            .build()
        val nominatimHttp = http.newBuilder().callTimeout(nominatimCallTimeoutMs, TimeUnit.MILLISECONDS).build()
        val startMs = monotonicMs()
        try {
            val address = parseAddress(await(nominatimHttp.newCall(request)))
            Log.i(TAG, "nominatim ${url.host}: ok, settlement=${address.settlement != null} " +
                "street=${address.road != null}, ${monotonicMs() - startMs}ms")
            Trace.call("nominatim", url.host, monotonicMs() - startMs)
            lastEndpoint = nominatimUrl
            return address
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "nominatim ${url.host}: ${outcome(e)}, ${monotonicMs() - startMs}ms")
            Trace.call("nominatim", url.host, monotonicMs() - startMs, e, outcome(e))
            throw e
        } catch (e: CancellationException) {
            Log.i(TAG, "nominatim ${url.host}: cancelled or out of time after ${monotonicMs() - startMs}ms, in-flight call dropped")
            throw e
        }
    }

    private suspend fun awaitNominatimTurn() = nominatimTurn.withLock {
        lastNominatimMs?.let { last ->
            val waitMs = last + NOMINATIM_INTERVAL_MS - monotonicMs()
            if (waitMs > 0) {
                Log.i(TAG, "nominatim: waiting ${waitMs}ms, one request per second")
                delay(waitMs)
            }
        }
        lastNominatimMs = monotonicMs()
    }

    /** Nominatim answers "nothing here" (open sea) with `error` inside a 200. */
    private fun parseAddress(body: String): Surroundings.Address {
        val json = try { JSONObject(body) } catch (_: org.json.JSONException) { null }
        val error = json?.optString("error").orEmpty()
        val parts = json?.optJSONObject("address")?.takeIf { error.isEmpty() }
            ?: throw BadAnswer(
                when {
                    json == null -> "bad JSON"
                    error.isNotEmpty() -> "error answer"
                    else -> "no address"
                }
            )
        fun part(vararg keys: String) = keys.firstNotNullOfOrNull { key -> parts.optString(key).takeIf { it.isNotBlank() } }
        val place = SETTLEMENT_KEYS.firstOrNull { parts.optString(it).isNotBlank() }
        val address = Surroundings.Address(
            settlement = place?.let { parts.optString(it) },
            place = place,
            road = part("road"),
            district = part("suburb", "city_district"),
            region = part("state"),
            country = part("country"),
        )
        if (listOf(address.settlement, address.road, address.district, address.region, address.country).all { it == null }) {
            throw BadAnswer("empty address")
        }
        return address
    }

    /** Overpass reports runtime errors (timeout, out of memory) inside a 200 answer via
     *  `remark`, often with an empty `elements`: that is a failed server, not "nothing around". */
    private fun parse(host: String, body: String): List<Settlement> {
        val json = try { JSONObject(body) } catch (_: org.json.JSONException) { null }
        val remark = json?.optString("remark").orEmpty()
        if (remark.isNotEmpty()) Log.i(TAG, "overpass $host remark: $remark")
        val elements = json?.optJSONArray("elements")
            ?.takeUnless { remark.contains("error", ignoreCase = true) }
            ?: throw BadAnswer(
                when {
                    json == null -> "bad JSON"
                    remark.isNotEmpty() -> "remark error"
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
                        if (!resp.isSuccessful) throw BadAnswer("HTTP ${resp.code}")
                        resp.body?.string().takeUnless { it.isNullOrBlank() } ?: throw BadAnswer("empty body")
                    }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                    return
                }
                cont.resume(body)
            }
        })
    }

    /** A server answered, but with nothing usable; the message is safe for a user-shared log. */
    private class BadAnswer(message: String) : IOException(message)

    private companion object {
        const val TAG = "SettlementSearch"
        const val NOMINATIM_ZOOM = 17
        const val NOMINATIM_INTERVAL_MS = 1_000L
        // Nominatim address keys for a settlement, largest first.
        val SETTLEMENT_KEYS = listOf("city", "town", "village", "hamlet")

        /** For the per-server log line: the HTTP code or what was wrong with the answer, else the
         *  exception class - never a URL, coordinates or a place name. */
        fun outcome(e: IOException): String = if (e is BadAnswer) e.message.orEmpty() else e.javaClass.simpleName

        fun monotonicMs(): Long = System.nanoTime() / 1_000_000
    }
}
