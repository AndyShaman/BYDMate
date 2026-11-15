package com.bydmate.app.voice.online

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class OnlineTtsFailureTest {

    private fun fixture(): String =
        javaClass.getResourceAsStream("/voice-fixtures/openrouter-tts-404-data-policy.txt")!!
            .bufferedReader().readText().trim()

    private fun failure(cause: Throwable?, backendId: String = "gemini") =
        OnlineTtsFailure(backendId, cause, spokenOffline = true)

    @Test
    fun `a data policy refusal from OpenRouter is classified as DATA_POLICY`() {
        val f = failure(IOException("OpenRouter TTS HTTP 404: " + fixture()))
        assertEquals(OnlineTtsFailureKind.DATA_POLICY, f.kind())
    }

    @Test
    fun `other failures are classified as OTHER`() {
        assertEquals(OnlineTtsFailureKind.OTHER, failure(IOException("OpenRouter TTS HTTP 500: boom")).kind())
        assertEquals(OnlineTtsFailureKind.OTHER, failure(null).kind())
        assertEquals(OnlineTtsFailureKind.OTHER, failure(SocketTimeoutException("timeout")).kind())
    }

    @Test
    fun `the notifier shows a failure once per backend and kind`() {
        val shown = mutableListOf<Pair<OnlineTtsFailureKind, Boolean>>()
        val notifier = OnlineTtsFailureNotifier { kind, offline -> shown += kind to offline }
        val policy = IOException("HTTP 404: data policy")

        notifier.onFailure(failure(policy))
        assertEquals(listOf(OnlineTtsFailureKind.DATA_POLICY to true), shown)

        notifier.onFailure(failure(policy))
        assertEquals(1, shown.size)

        notifier.onFailure(failure(IOException("HTTP 500")))
        assertEquals(2, shown.size)

        notifier.onFailure(failure(policy, backendId = "minimax"))
        assertEquals(3, shown.size)
    }
}
