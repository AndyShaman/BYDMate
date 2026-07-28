package com.bydmate.app.data.radio

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Radio Record's own catalogue — one broadcaster, but ~117 channels and, crucially, three
 * declared bitrate tiers per channel.
 *
 * That is why it earns a place next to the general directory: it is the only source here that can
 * fill [RadioSearchResult.lowBitrateUrl] honestly, so a station added from it actually responds to
 * the data-saver switch. It also ships real logos instead of scraped favicons.
 *
 * The catalogue is small and static, so it is fetched once and filtered in memory — searching by
 * name server-side is not offered by the API.
 */
@Singleton
class RadioRecordSource @Inject constructor(
    private val http: OkHttpClient,
) : RadioDirectorySource {

    override val label = "Radio Record"

    @Volatile private var cache: List<RadioSearchResult>? = null

    override suspend fun search(query: String): List<RadioSearchResult> = withContext(Dispatchers.IO) {
        val term = query.trim()
        if (term.isEmpty()) return@withContext emptyList()
        val all = cache ?: runCatching { parse(get(CATALOGUE_URL)) }
            .onFailure { Log.w(TAG, "catalogue fetch failed: ${it.message}") }
            .getOrDefault(emptyList())
            .also { if (it.isNotEmpty()) cache = it }

        // "record" should also surface the whole family, so the brand name matches every channel.
        val brandMatch = label.contains(term, ignoreCase = true) || term.contains("record", true)
        all.filter { brandMatch || it.name.contains(term, ignoreCase = true) }.take(LIMIT)
    }

    private fun get(url: String): String {
        val request = Request.Builder().url(url).header("User-Agent", USER_AGENT).build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("HTTP ${response.code}")
            return response.body?.string().orEmpty()
        }
    }

    internal fun parse(json: String): List<RadioSearchResult> {
        val stations = JSONObject(json)
            .optJSONObject("result")
            ?.optJSONArray("stations")
            ?: return emptyList()

        return (0 until stations.length()).mapNotNull { i ->
            val item = stations.getJSONObject(i)
            // Field names are the broadcaster's own tiers, not real kbps: stream_320 is the best
            // stream on offer and stream_64 the thriftiest, whatever they actually encode at.
            val best = item.optString("stream_320").ifBlank { item.optString("stream_128") }
            if (best.isBlank()) return@mapNotNull null
            val light = item.optString("stream_64").ifBlank { item.optString("stream_128") }
            val title = item.optString("title").trim()
            if (title.isBlank()) return@mapNotNull null

            RadioSearchResult(
                name = "$label — $title",
                url = best,
                lowBitrateUrl = light.takeIf { it.isNotBlank() && it != best },
                iconUrl = item.optString("icon_fill_colored")
                    .ifBlank { item.optString("icon_fill_white") }
                    .takeIf { it.isNotBlank() },
                codec = "AAC",
                bitrate = 0,
                country = "Russia",
                source = label,
            )
        }
    }

    companion object {
        private const val TAG = "RadioRecordSource"
        private const val LIMIT = 40
        private const val CATALOGUE_URL = "https://www.radiorecord.ru/api/stations/"
        private const val USER_AGENT = "BYDMate/1.0"
    }
}
