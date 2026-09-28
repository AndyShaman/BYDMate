package com.bydmate.app.agent

import android.util.Log
import com.bydmate.app.diagnostics.Trace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.Reader
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Every charging station of Belarus from the BETA association map (beta.by/map): per connector
 * its standard, occupancy, power and price. There is no public API (robots.txt closes /api/, so
 * it is never called): the map page itself, asked with `RSC: 1`, answers with the React Server
 * Components payload the page renders from, the whole country in one ~3.6 MB answer. The parsed
 * list is kept in memory for [cacheTtlMs], so a follow-up question and the route request right
 * after it do not download it again; an older list is never served as live occupancy.
 *
 * Memory: the payload is parsed as a stream (see [BetaMapParser]), never held as one String;
 * the kept list of ~1,500 stations is about 2-3 MB, peak while parsing a few MB more.
 */
class BetaMapClient(private val http: OkHttpClient) {

    /** What [stations] returns; [fromCache] = no download happened. */
    data class Snapshot(val stations: List<ChargerStation>, val fromCache: Boolean)

    /** Test seams. Measured from the head unit 28.09: the whole answer in 2.6 s; the parser
     *  stops after the first of the two station arrays in it, about half of the body. */
    internal var url = "https://beta.by/map"
    internal var callTimeoutMs = 4_500L
    internal var cacheTtlMs = 4 * 60_000L
    internal var clockMs: () -> Long = ::monotonicMs

    // One download at a time: a second caller waits for it and then reads the fresh cache.
    private val fetchTurn = Mutex()
    private var cached: List<ChargerStation>? = null // guarded by fetchTurn
    private var cachedAtMs = 0L // guarded by fetchTurn

    suspend fun stations(): Result<Snapshot> = fetchTurn.withLock {
        val kept = cached
        val ageMs = clockMs() - cachedAtMs
        if (kept != null && ageMs < cacheTtlMs) {
            Log.i(TAG, "beta: cache hit, age ${ageMs / 1000}s, ${kept.size} stations")
            return@withLock Result.success(Snapshot(kept, fromCache = true))
        }
        download().map { fresh ->
            cached = fresh
            cachedAtMs = clockMs()
            Snapshot(fresh, fromCache = false)
        }
    }

    private suspend fun download(): Result<List<ChargerStation>> {
        val host = url.toHttpUrl().host
        val request = Request.Builder().url(url)
            .header("RSC", "1")
            .header("User-Agent", MAP_USER_AGENT)
            .build()
        val betaHttp = http.newBuilder().callTimeout(callTimeoutMs, TimeUnit.MILLISECONDS).build()
        val startMs = monotonicMs()
        return try {
            val stations = betaHttp.newCall(request).awaitRead { resp ->
                val body = resp.body ?: throw MapBadAnswer("empty body")
                BetaMapParser.parse(body.charStream())
            }
            Log.i(TAG, "beta $host: ok, ${stations.size} stations, ${monotonicMs() - startMs}ms")
            Trace.call("beta", host, monotonicMs() - startMs)
            Result.success(stations)
        } catch (e: IOException) {
            currentCoroutineContext().ensureActive()
            Log.w(TAG, "beta $host: ${mapOutcome(e)}, ${monotonicMs() - startMs}ms")
            Trace.call("beta", host, monotonicMs() - startMs, e, mapOutcome(e))
            Result.failure(e)
        } catch (e: CancellationException) {
            Log.i(TAG, "beta $host: cancelled or out of time after ${monotonicMs() - startMs}ms, in-flight call dropped")
            throw e
        }
    }

    private companion object {
        const val TAG = "BetaMapClient"
    }
}

/**
 * Reads the station list out of the beta.by/map payload without holding the payload in memory:
 * it scans the character stream for the first `"stations":[`, then cuts the array into one
 * station object at a time and parses each on its own. It stops at the end of that array, so
 * the rest of the body (a second copy of the list among other things) is never read.
 *
 * Tolerant of the two shapes the data comes in: raw JSON (the RSC answer to `RSC: 1`) and the
 * same JSON inside a JS string, every quote escaped (the HTML page, should a proxy hand that
 * back instead). Anything else is a [MapBadAnswer] with a fixed reason code, never a crash.
 */
internal object BetaMapParser {

    private const val RAW_MARKER = "\"stations\":["
    private const val ESCAPED_MARKER = "\\\"stations\\\":["
    private const val RING = 16
    // The largest real station (18 connectors) is ~4 KB of JSON; anything past this is not one.
    private const val MAX_STATION_CHARS = 64 * 1024

    /** Nesting levels of one station, its own object included; a real one goes 3-4 deep. */
    internal const val MAX_DEPTH = 16

    /** Stations in the array; the real map holds 1544 (28.09). */
    internal const val MAX_STATIONS = 10_000
    private const val HEX_DIGITS = 4
    private const val HEX_RADIX = 16
    private const val FORM_FEED = 0x0C
    private const val DEFAULT_NAME = "Зарядная станция"
    // Tariff units read as a price per kWh. BETA labels one operator's tariffs PER_KW with the
    // same amounts others label PER_KWH (0.55 AC, 0.73 DC): nobody sells charging per kW.
    private val PER_KWH_UNITS = setOf("PER_KWH", "PER_KW")

    /** [maxStations] is a test seam; production keeps [MAX_STATIONS]. */
    fun parse(reader: Reader, maxStations: Int = MAX_STATIONS): List<ChargerStation> {
        val chars = CharStream(reader)
        val escaped = findMarker(chars) ?: throw MapBadAnswer("no stations array")
        val stations = readArray(if (escaped) Unescaping(chars) else chars, maxStations)
        if (stations.isEmpty()) throw MapBadAnswer("empty stations")
        return stations
    }

    /** Reads up to and including the first marker: false = raw JSON follows, true = escaped,
     *  null = the stream ended without one. */
    private fun findMarker(input: CharStream): Boolean? {
        val ring = CharArray(RING)
        var n = 0L
        while (true) {
            val c = input.next()
            if (c < 0) return null
            ring[(n % RING).toInt()] = c.toChar()
            n++
            if (c == '['.code) {
                if (endsWith(ring, n, RAW_MARKER)) return false
                if (endsWith(ring, n, ESCAPED_MARKER)) return true
            }
        }
    }

    private fun endsWith(ring: CharArray, n: Long, marker: String): Boolean {
        if (n < marker.length) return false
        return marker.indices.all { i -> ring[((n - marker.length + i) % RING).toInt()] == marker[i] }
    }

    private fun readArray(input: CharSource, maxStations: Int): List<ChargerStation> {
        val stations = ArrayList<ChargerStation>()
        val obj = StringBuilder()
        var count = 0
        while (true) {
            val c = input.next()
            when {
                c < 0 -> throw MapBadAnswer("truncated")
                c == ']'.code -> return stations
                c == ','.code || Character.isWhitespace(c) -> continue
                // Anything but a station object, or far more of them than the real map has.
                c != '{'.code || ++count > maxStations -> throw MapBadAnswer("bad JSON")
                else -> {
                    readObject(input, obj)
                    toStation(obj)?.let(stations::add)
                }
            }
        }
    }

    /** Copies one `{...}` object into [out], tracking strings so a brace inside a name does not count. */
    private fun readObject(input: CharSource, out: StringBuilder) {
        out.setLength(0)
        out.append('{')
        var depth = 1
        var inString = false
        var escape = false
        while (depth > 0) {
            // Past either limit it is no real station, and a deep nest would overflow the
            // recursive JSONObject parser with a StackOverflowError nobody catches.
            if (out.length >= MAX_STATION_CHARS || depth > MAX_DEPTH) throw MapBadAnswer("bad JSON")
            val c = input.next()
            if (c < 0) throw MapBadAnswer("truncated")
            val ch = c.toChar()
            out.append(ch)
            when {
                escape -> escape = false
                inString -> if (ch == '\\') escape = true else if (ch == '"') inString = false
                ch == '"' -> inString = true
                ch == '{' || ch == '[' -> depth++
                ch == '}' || ch == ']' -> depth--
            }
        }
    }

    private fun toStation(text: CharSequence): ChargerStation? {
        val o = try {
            JSONObject(text.toString())
        } catch (_: JSONException) {
            throw MapBadAnswer("bad JSON")
        }
        val lat = o.optDouble("latitude")
        val lon = o.optDouble("longitude")
        if (lat.isNaN() || lon.isNaN()) return null
        val list = o.optJSONArray("connectors")
        return ChargerStation(
            name = o.text("name") ?: o.text("address") ?: DEFAULT_NAME,
            address = o.text("address"),
            operator = o.optJSONObject("operator")?.text("name"),
            lat = lat,
            lon = lon,
            connectors = (0 until (list?.length() ?: 0)).mapNotNull { i -> list?.optJSONObject(i)?.let(::toConnector) },
            statusAtMs = o.text("lastImportedAt")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() },
        )
    }

    private fun toConnector(c: JSONObject): StationConnector {
        val tariff = c.optJSONObject("tariff")
        val amount = tariff?.optDouble("amount")?.takeIf { !it.isNaN() && it > 0.0 }
        return StationConnector(
            standard = c.text("standard") ?: "UNKNOWN",
            powerKw = c.optDouble("powerKw").takeIf { !it.isNaN() },
            status = c.text("status"),
            pricePerKwh = amount?.takeIf { tariff?.text("unit") in PER_KWH_UNITS },
            currency = tariff?.text("currency"),
        )
    }

    /** Android's org.json turns a JSON null into the string "null" in optString: keep it null. */
    private fun JSONObject.text(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private interface CharSource {
        /** The next char, or -1 at the end of the stream. */
        fun next(): Int
    }

    private class CharStream(private val reader: Reader) : CharSource {
        private val buf = CharArray(8 * 1024)
        private var len = 0
        private var pos = 0

        override fun next(): Int {
            if (pos == len) {
                val read = reader.read(buf)
                if (read <= 0) return -1
                len = read
                pos = 0
            }
            return buf[pos++].code
        }
    }

    /** One level of JS string escapes undone: `\"` -> `"`, `\\` -> `\`, `\/` -> `/`, `\uXXXX`. */
    private class Unescaping(private val input: CharSource) : CharSource {
        override fun next(): Int {
            val c = input.next()
            if (c != '\\'.code) return c
            return when (val e = input.next()) {
                'n'.code -> '\n'.code
                't'.code -> '\t'.code
                'r'.code -> '\r'.code
                'b'.code -> '\b'.code
                'f'.code -> FORM_FEED
                'u'.code -> hex()
                else -> e
            }
        }

        private fun hex(): Int {
            var v = 0
            repeat(HEX_DIGITS) {
                val d = Character.digit(input.next(), HEX_RADIX)
                if (d < 0) throw MapBadAnswer("bad JSON")
                v = v * HEX_RADIX + d
            }
            return v
        }
    }
}
