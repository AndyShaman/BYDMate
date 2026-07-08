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

/** EV charging stations from OpenStreetMap via the Overpass API. Free, no key; data quality
 *  is community-maintained, so results carry no availability/power guarantees. */
@Singleton
class ChargerSearchClient @Inject constructor(private val http: OkHttpClient) {

    data class Charger(val name: String, val lat: Double, val lon: Double)

    /** Test seam so unit tests point at MockWebServer. Tried in order; first 2xx wins. */
    internal var endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter"
    )

    private val overpassHttp = http.newBuilder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun search(lat: Double, lon: Double, radiusM: Int): Result<List<Charger>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val query = "[out:json][timeout:10];" +
                    "nwr[\"amenity\"=\"charging_station\"](around:$radiusM,$lat,$lon);" +
                    "out center 20;"
                val formBody = FormBody.Builder().add("data", query).build()
                var lastError: IOException? = null
                for (ep in endpoints) {
                    try {
                        val request = Request.Builder().url(ep).post(formBody).build()
                        val result = overpassHttp.newCall(request).execute().use { resp ->
                            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
                            val body = resp.body?.string().takeUnless { it.isNullOrBlank() }
                                ?: throw IOException("empty body")
                            val elements = JSONObject(body).optJSONArray("elements") ?: return@use emptyList<Charger>()
                            (0 until elements.length()).mapNotNull { i ->
                                val e = elements.getJSONObject(i)
                                // Nodes carry lat/lon directly; ways/relations via "center".
                                val center = e.optJSONObject("center")
                                // optDouble returns NaN (not null) for an absent key, so a present-but-
                                // malformed "center" would leak NaN past the elvis without this guard.
                                val cLat = if (e.has("lat")) e.getDouble("lat") else center?.optDouble("lat") ?: return@mapNotNull null
                                val cLon = if (e.has("lon")) e.getDouble("lon") else center?.optDouble("lon") ?: return@mapNotNull null
                                if (cLat.isNaN() || cLon.isNaN()) return@mapNotNull null
                                val tags = e.optJSONObject("tags")
                                val name = tags?.optString("name")?.takeIf { it.isNotBlank() }
                                    ?: tags?.optString("operator")?.takeIf { it.isNotBlank() }
                                    ?: "Зарядная станция"
                                Charger(name, cLat, cLon)
                            }
                        }
                        return@runCatching result
                    } catch (e: IOException) {
                        Log.w("ChargerSearchClient", "overpass $ep failed: ${e.message}")
                        lastError = e
                    }
                }
                throw IOException("зарядочные серверы недоступны: ${lastError?.message}")
            }
        }
}
