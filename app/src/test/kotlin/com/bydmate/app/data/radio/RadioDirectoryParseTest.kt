package com.bydmate.app.data.radio

import io.mockk.mockk
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parsing is where directory data actually bites: the feeds are user-submitted and full of blank
 * fields, so these lock in what the app must survive rather than the happy path alone.
 */
class RadioDirectoryParseTest {

    private val browser = RadioBrowserSource(mockk<OkHttpClient>(relaxed = true))
    private val record = RadioRecordSource(mockk<OkHttpClient>(relaxed = true))

    @Test
    fun `radio-browser prefers the resolved stream over the playlist url`() {
        val json = """
            [{"name":"Hit FM","url":"https://x/hit.pls",
              "url_resolved":"https://x/hit128.mp3","favicon":"https://x/i.png",
              "codec":"MP3","bitrate":128,"country":"Russia"}]
        """.trimIndent()

        val result = browser.parse(json).single()

        assertEquals("Hit FM", result.name)
        assertEquals("https://x/hit128.mp3", result.url)
        assertEquals(128, result.bitrate)
        assertEquals("Russia", result.country)
        // The directory lists one stream per entry, so data-saver has nothing to switch to.
        assertNull(result.lowBitrateUrl)
    }

    @Test
    fun `radio-browser entries without a usable stream or name are dropped`() {
        val json = """
            [{"name":"No stream","url":"","url_resolved":""},
             {"name":"","url_resolved":"https://x/a.mp3"},
             {"name":"Good","url_resolved":"https://x/good.mp3"}]
        """.trimIndent()

        val results = browser.parse(json)

        assertEquals(1, results.size)
        assertEquals("Good", results.single().name)
    }

    @Test
    fun `radio-browser blank favicon becomes null rather than an empty url`() {
        val result = browser.parse("""[{"name":"N","url_resolved":"https://x/n.mp3","favicon":""}]""").single()

        assertNull(result.iconUrl)
    }

    @Test
    fun `record maps its bitrate tiers onto the normal and lighter streams`() {
        val json = """
            {"result":{"stations":[
              {"title":"Record","stream_320":"https://r/main96.aacp",
               "stream_128":"https://r/main64.aacp","stream_64":"https://r/main32.aacp",
               "icon_fill_colored":"https://r/logo.png"}
            ]}}
        """.trimIndent()

        val result = record.parse(json).single()

        assertTrue(result.name.contains("Record"))
        assertEquals("https://r/main96.aacp", result.url)
        assertEquals("https://r/main32.aacp", result.lowBitrateUrl)
        assertEquals("https://r/logo.png", result.iconUrl)
    }

    @Test
    fun `record falls back when only one tier exists and never duplicates it`() {
        val json = """
            {"result":{"stations":[
              {"title":"Solo","stream_320":"https://r/solo.aacp","stream_128":"","stream_64":""}
            ]}}
        """.trimIndent()

        val result = record.parse(json).single()

        assertEquals("https://r/solo.aacp", result.url)
        // A "lighter" stream identical to the normal one would make the setting a lie.
        assertNull(result.lowBitrateUrl)
    }

    @Test
    fun `record tolerates a payload with no stations at all`() {
        assertEquals(emptyList<RadioSearchResult>(), record.parse("""{"result":{}}"""))
    }
}
