package com.bydmate.app.agent

import android.util.Log
import com.bydmate.app.BuildConfig
import com.bydmate.app.data.automation.PlaceGeometry
import com.bydmate.app.data.charging.ChargeConnector
import com.bydmate.app.diagnostics.Trace
import com.bydmate.app.diagnostics.TraceArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EV charging stations for the voice agent. Inside Belarus: the BETA association map (live
 * occupancy, power and price per connector), then Malanka's map gateway (stations, a
 * whole-station status for some networks), then OpenStreetMap via Overpass (stations only).
 * Elsewhere OpenStreetMap only. "Inside Belarus" is a bounding box that also holds border
 * towns of the neighbours, so a Belarus source with nothing within the radius passes the
 * search on down the chain. The whole chain answers within [totalTimeoutMs].
 */
@Singleton
class ChargerSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** What [find] found: the stations within the radius and the source that answered. */
    data class Found(val source: ChargerSource, val stations: List<ChargerStation>)

    /** Test seam so unit tests point at MockWebServer. Tried in order; first clean answer wins.
     *  Measured from the head unit 28.09: overpass.openstreetmap.fr answered 4 of 4 small queries
     *  in 0.5-1.4 s, overpass-api.de gave a 504 after 7.5 s and a 200 after 17.9 s. The
     *  maps.mail.ru mirror is gone: the head unit lacks its TLS root (Android 12). */
    internal var endpoints = listOf(
        "https://overpass.openstreetmap.fr/api/interpreter",
        "https://overpass-api.de/api/interpreter",
    )

    /** Test seams. One Overpass server may take [callTimeoutMs], so a slow first one leaves the
     *  second its turn; [find] as a whole never takes longer than [totalTimeoutMs]. */
    internal var callTimeoutMs = 4_000L
    internal var totalTimeoutMs = 10_000L
    internal var beta = BetaMapClient(http)
    internal var gateway = MalankaGatewayClient(http)

    /** Stations within [radiusM] of the point from the first source that has any. [connector]
     *  only shapes the log line (how many have the car's connector, how many are free). */
    suspend fun find(lat: Double, lon: Double, radiusM: Int, connector: ChargeConnector): Result<Found> =
        withContext(Dispatchers.IO) {
            // withTimeoutOrNull turns only its own deadline into null; a cancelled voice turn
            // still unwinds as CancellationException.
            withTimeoutOrNull(totalTimeoutMs) { chain(lat, lon, radiusM, connector) } ?: run {
                Log.w(TAG, "find: no answer within ${totalTimeoutMs}ms")
                Result.failure(IOException("серверы зарядок не ответили за ${totalTimeoutMs / 1000} с"))
            }
        }

    /** OpenStreetMap stations within [radiusM] of the point, via Overpass alone: the last link of
     *  [find]'s chain, open to tests of the Overpass servers. */
    internal suspend fun search(lat: Double, lon: Double, radiusM: Int): Result<List<ChargerStation>> =
        withContext(Dispatchers.IO) { overpass(lat, lon, radiusM) }

    private suspend fun chain(lat: Double, lon: Double, radiusM: Int, connector: ChargeConnector): Result<Found> {
        // A Belarus source that answered "nothing within the radius": still an answer if every
        // later source fails.
        var answeredEmpty: ChargerSource? = null
        if (inBelarus(lat, lon)) {
            beta.stations().getOrNull()?.let { snap ->
                val near = within(snap.stations, lat, lon, radiusM)
                logCounts(ChargerSource.BETA, snap.stations.size, near, connector, snap.fromCache)
                if (near.isNotEmpty()) return Result.success(Found(ChargerSource.BETA, near))
                answeredEmpty = ChargerSource.BETA
            }
            gateway.stations().getOrNull()?.let { all ->
                val near = within(all, lat, lon, radiusM)
                logCounts(ChargerSource.GATEWAY, all.size, near, connector, fromCache = false)
                if (near.isNotEmpty()) return Result.success(Found(ChargerSource.GATEWAY, near))
                answeredEmpty = answeredEmpty ?: ChargerSource.GATEWAY
            }
        }
        return overpass(lat, lon, radiusM).fold(
            onSuccess = { stations ->
                logCounts(ChargerSource.OSM, stations.size, stations, connector, fromCache = false)
                Result.success(Found(ChargerSource.OSM, stations))
            },
            onFailure = { e -> answeredEmpty?.let { Result.success(Found(it, emptyList())) } ?: Result.failure(e) },
        )
    }

    private fun within(stations: List<ChargerStation>, lat: Double, lon: Double, radiusM: Int) =
        stations.filter { PlaceGeometry.distanceMeters(lat, lon, it.lat, it.lon) <= radiusM }

    /** One line per answering source: counts only, never a place, a name or a coordinate. */
    private suspend fun logCounts(
        source: ChargerSource,
        total: Int,
        near: List<ChargerStation>,
        connector: ChargeConnector,
        fromCache: Boolean,
    ) {
        val carType = { s: ChargerStation -> s.connectors?.filter { connector.matches(it.standard) } }
        val withConnector = if (source == ChargerSource.BETA) near.count { carType(it).orEmpty().isNotEmpty() } else null
        val free = when (source) {
            ChargerSource.BETA -> near.count { s -> carType(s).orEmpty().any { it.status == "available" } }
            ChargerSource.GATEWAY -> near.count { it.status == StationStatus.FREE }
            ChargerSource.OSM -> null
        }
        Log.i(TAG, "chain ${source.code}: total=$total near=${near.size} " +
            "with_${connector.key}=${withConnector ?: "-"} free=${free ?: "-"} cache=$fromCache")
        Trace.event(TraceArea.NET, "chargers", "source" to source.code, "total" to total, "near" to near.size,
            "connector" to connector.key, "with_connector" to withConnector, "free" to free,
            "cache" to fromCache.takeIf { it }, by = Trace.cause())
    }

    private suspend fun overpass(lat: Double, lon: Double, radiusM: Int): Result<List<ChargerStation>> {
        val query = "[out:json][timeout:10];" +
            "nwr[\"amenity\"=\"charging_station\"](around:$radiusM,$lat,$lon);" +
            "out center $OVERPASS_LIMIT;"
        val formBody = FormBody.Builder().add("data", query).build()
        val overpassHttp = http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
        var lastError: IOException? = null
        for (ep in endpoints) {
            val host = ep.toHttpUrl().host
            val startMs = monotonicMs()
            try {
                val request = Request.Builder().url(ep).header("User-Agent", MAP_USER_AGENT).post(formBody).build()
                val stations = overpassHttp.newCall(request).awaitRead { resp -> parse(host, resp.body?.string().orEmpty()) }
                Log.i(TAG, "overpass $host: ok, ${stations.size} stations, ${monotonicMs() - startMs}ms")
                Trace.call("overpass", host, monotonicMs() - startMs)
                return Result.success(stations)
            } catch (e: IOException) {
                // A cancel that raced the failure: stop here instead of trying the next server.
                currentCoroutineContext().ensureActive()
                Log.w(TAG, "overpass $host: ${mapOutcome(e)}, ${monotonicMs() - startMs}ms")
                Trace.call("overpass", host, monotonicMs() - startMs, e, mapOutcome(e))
                lastError = e
            } catch (e: CancellationException) {
                Log.i(TAG, "overpass $host: cancelled or out of time after ${monotonicMs() - startMs}ms, in-flight call dropped")
                throw e
            }
        }
        return Result.failure(IOException("зарядочные серверы недоступны: ${lastError?.let(::mapOutcome)}"))
    }

    /** Overpass reports runtime errors (timeout, out of memory) inside a 200 answer via
     *  `remark`: that is a failed server, not "nothing around". A body that is not JSON at all
     *  ends as "bad JSON" in [awaitRead]. */
    private fun parse(host: String, body: String): List<ChargerStation> {
        val json = JSONObject(body)
        val remark = json.optString("remark")
        if (remark.isNotEmpty()) Log.i(TAG, "overpass $host remark: $remark")
        if (remark.contains("error", ignoreCase = true)) throw MapBadAnswer("remark error")
        val elements = json.optJSONArray("elements") ?: throw MapBadAnswer("no elements array")
        return (0 until elements.length()).mapNotNull { i ->
            val e = elements.optJSONObject(i) ?: return@mapNotNull null
            // Nodes carry lat/lon directly; ways/relations via "center". optDouble returns NaN
            // (not null) for an absent key, so a malformed "center" is caught by the NaN check.
            val center = e.optJSONObject("center")
            val cLat = if (e.has("lat")) e.optDouble("lat") else center?.optDouble("lat") ?: Double.NaN
            val cLon = if (e.has("lon")) e.optDouble("lon") else center?.optDouble("lon") ?: Double.NaN
            if (cLat.isNaN() || cLon.isNaN()) return@mapNotNull null
            val tags = e.optJSONObject("tags")
            val operator = tags?.text("operator")
            val address = listOfNotNull(tags?.text("addr:city"), tags?.text("addr:street"), tags?.text("addr:housenumber"))
                .joinToString(", ").ifEmpty { null }
            ChargerStation(tags?.text("name") ?: operator ?: "Зарядная станция", address, operator, cLat, cLon)
        }
    }

    /** Android's org.json turns a JSON null into the string "null" in optString: keep it null. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun inBelarus(lat: Double, lon: Double) = lat in BELARUS_LAT && lon in BELARUS_LON

    private companion object {
        const val TAG = "ChargerSearchClient"
        // Enough to sort by distance ourselves: Overpass has no "nearest first".
        const val OVERPASS_LIMIT = 100
        // Belarus spans 51.26-56.17 N, 23.18-32.78 E.
        val BELARUS_LAT = 51.2..56.2
        val BELARUS_LON = 23.1..32.8
    }
}

/** overpass-api.de answers 406 to OkHttp's default User-Agent (`okhttp/x.y.z`) and serves a
 *  client that names itself; Nominatim's usage policy asks for the same. Shared by every map
 *  and charging-station client. */
internal const val MAP_USER_AGENT = "BYDMate/${BuildConfig.VERSION_NAME} (+https://github.com/AndyShaman/BYDMate)"
