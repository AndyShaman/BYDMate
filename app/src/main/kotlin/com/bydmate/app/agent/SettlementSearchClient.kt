package com.bydmate.app.agent

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Settlements around a point from OpenStreetMap via the Overpass API (same servers as
 *  [ChargerSearchClient]). Backs the where_am_i tool: the model must name places from this
 *  list, never from a web search on raw coordinates. */
@Singleton
class SettlementSearchClient @Inject constructor(private val http: OkHttpClient) {

    /** [place] is the OSM place value (city/town/village/hamlet/suburb/isolated_dwelling). */
    data class Settlement(val name: String, val place: String, val lat: Double, val lon: Double)

    /** Test seam so unit tests point at MockWebServer. Tried in order; first 2xx wins. */
    internal var endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private val overpassHttp = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    /** Villages and smaller within [smallRadiusM], cities and towns within [townRadiusM]:
     *  a village radius wide enough for towns would pull hundreds of hamlets. */
    suspend fun search(lat: Double, lon: Double, smallRadiusM: Int, townRadiusM: Int): Result<List<Settlement>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = "[out:json][timeout:10];(" +
                    "node[\"place\"~\"^(village|hamlet|suburb|isolated_dwelling)$\"](around:$smallRadiusM,$lat,$lon);" +
                    "node[\"place\"~\"^(city|town)$\"](around:$townRadiusM,$lat,$lon);" +
                    ");out body;"
                Log.i(TAG, "overpass request: small=${smallRadiusM}m town=${townRadiusM}m")
                val formBody = FormBody.Builder().add("data", query).build()
                var lastError: IOException? = null
                for (ep in endpoints) {
                    try {
                        val request = Request.Builder().url(ep).post(formBody).build()
                        val result = overpassHttp.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                                ?: throw IOException("empty body")
                            val elements = JSONObject(body).optJSONArray("elements") ?: return@use emptyList<Settlement>()
                            (0 until elements.length()).mapNotNull { i ->
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
                        Log.i(TAG, "overpass $ep: ${result.size} settlements")
                        return@runCatching result
                    } catch (e: IOException) {
                        Log.w(TAG, "overpass $ep failed: ${e.message}")
                        lastError = e
                    }
                }
                throw IOException("серверы карт недоступны: ${lastError?.message}")
            }
        }

    private companion object {
        const val TAG = "SettlementSearch"
    }
}
