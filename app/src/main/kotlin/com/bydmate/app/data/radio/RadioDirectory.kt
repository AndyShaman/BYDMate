package com.bydmate.app.data.radio

/**
 * One station offered by a directory search, before the user decides to keep it.
 *
 * [lowBitrateUrl] is filled only by directories that actually publish a second stream. Guessing
 * one by mangling the URL is how you end up with a station that silently 404s the moment the
 * driver switches to data-saver mode, so a null here simply means the setting will not apply.
 */
data class RadioSearchResult(
    val name: String,
    val url: String,
    val lowBitrateUrl: String? = null,
    val iconUrl: String? = null,
    val codec: String = "",
    val bitrate: Int = 0,
    val country: String? = null,
    /** Directory this came from, shown in the list so the user can judge the source. */
    val source: String,
)

/** A searchable station directory. Implementations must never throw — they return what they got. */
interface RadioDirectorySource {
    val label: String
    suspend fun search(query: String): List<RadioSearchResult>
}
