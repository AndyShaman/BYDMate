package com.bydmate.app.data.radio

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Queries every directory at once and merges the answers.
 *
 * Sources are hit in parallel because the slowest one would otherwise set the latency of the whole
 * search, and a source that fails contributes nothing rather than failing the search — a directory
 * being down is not a reason to show the driver an error when the other one answered.
 *
 * Results are deduplicated by stream URL: the same station is often listed by more than one
 * directory, and the entry that knows a lighter stream wins, since that is the one that will
 * honour the data-saver setting.
 */
@Singleton
class RadioDirectoryRepository @Inject constructor(
    radioBrowser: RadioBrowserSource,
    radioRecord: RadioRecordSource,
) {
    private val sources: List<RadioDirectorySource> = listOf(radioBrowser, radioRecord)

    val sourceLabels: List<String> = sources.map { it.label }

    suspend fun search(query: String): List<RadioSearchResult> = coroutineScope {
        val answers = sources
            .map { source -> async { runCatching { source.search(query) }.getOrDefault(emptyList()) } }
            .map { it.await() }
            .flatten()

        answers
            .groupBy { it.url }
            .values
            .map { duplicates -> duplicates.maxByOrNull { if (it.lowBitrateUrl != null) 1 else 0 }!! }
            .sortedByDescending { it.bitrate }
    }
}
