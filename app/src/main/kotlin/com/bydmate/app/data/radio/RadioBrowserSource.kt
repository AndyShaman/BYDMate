package com.bydmate.app.data.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * radio-browser.info — the open, community-maintained station directory. No key, no account.
 *
 * Two query parameters carry most of the weight. `hidebroken` drops stations the service failed to
 * open on its last sweep, which is the whole class of problem we hit with a dead preset; ordering
 * by votes pushes the real station above the dozens of misfiled duplicates the open submission
 * model produces.
 *
 * The service is a pool of mirrors, so the host is discovered once and reused. A custom
 * User-Agent is not optional — anonymous clients get throttled.
 */
@Singleton
class RadioBrowserSource @Inject constructor(
    private val http: OkHttpClient,
) : RadioDirectorySource {

    override val label = "radio-browser"

    @Volatile private var host: String? = null

    override suspend fun search(query: String): List<RadioSearchResult> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val server = resolveHost() ?: return@withContext emptyList()

        val url = "https://$server/json/stations/search".toHttpUrlOrNull()
            ?.newBuilder()
            ?.addQueryParameter("name", term)
            ?.addQueryParameter("hidebroken", "true")
            ?.addQueryParameter("order", "votes")
            ?.addQueryParameter("reverse", "true")
            ?.addQueryParameter("limit", LIMIT.toString())
            ?.build()
            ?: return@withContext emptyList()

        runCatching { parse(get(url.toString())) }
            .onFailure { Log.w(TAG, "search failed: ${it.message}") }
            .getOrDefault(emptyList())
    }

    /** Picks a mirror once per process; falls back to the well-known round-robin host. */
    private fun resolveHost(): String? {
        host?.let { return it }
        val discovered = runCatching {
            val servers = JSONArray(get("https://all.api.radio-browser.info/json/servers"))
            (0 until servers.length())
                .mapNotNull { servers.getJSONObject(it).optString("name").takeIf(String::isNotBlank) }
                .distinct()
                .firstOrNull()
        }.getOrNull() ?: FALLBACK_HOST
        host = discovered
        return discovered
    }

    private fun get(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    internal fun parse(json: String): List<RadioSearchResult> {
        val array = JSONArray(json)
        return (0 until array.length()).mapNotNull { i ->
            val item = array.getJSONObject(i)
            // url_resolved is the stream itself; url may still be a .pls/.m3u wrapper.
            val stream = item.optString("url_resolved").ifBlank { item.optString("url") }
            val name = item.optString("name").trim()
            if (stream.isBlank() || name.isBlank()) return@mapNotNull null
            RadioSearchResult(
                name = name,
                url = stream,
                lowBitrateUrl = null,   // the directory knows one stream per entry
                iconUrl = item.optString("favicon").takeIf { it.isNotBlank() },
                codec = item.optString("codec"),
                bitrate = item.optInt("bitrate"),
                country = item.optString("country").takeIf { it.isNotBlank() },
                source = label,
            )
        }
    }

    companion object {
        private const val TAG = "RadioBrowser"
        private const val LIMIT = 40
        private const val FALLBACK_HOST = "de1.api.radio-browser.info"
        private const val USER_AGENT = "BYDMate/1.0 (+https://github.com/AndyShaman/BYDMate)"
    }
}
