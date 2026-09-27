package com.bydmate.app.helper.offreport

/** What one send attempt came to: [rc] for the log, [verdict] for the loop. */
internal class AttemptResult(val rc: String, val verdict: Verdict) {
    enum class Verdict { SENT, RETRY, STOP }
}

internal class RetryResult(val sent: Boolean, val attempts: Int, val rc: String)

/**
 * Sends until one attempt goes through, Telegram refuses for good, or [OffReportRetry.DEADLINE_MS]
 * after the power-off has passed. Wi-Fi goes about 1 s after the power-off and the car's mobile data
 * 3-4 s after (L3 measurement 2026-09-27), so attempts follow each other every
 * [OffReportRetry.INTERVAL_MS] with timeouts clamped to what is left of the window.
 *
 * [offAt] and [clock] are on the same monotonic clock; [attempt] gets the connect and read timeouts.
 */
internal object OffReportRetry {
    const val DEADLINE_MS = 8_000L
    const val INTERVAL_MS = 500L
    const val CONNECT_TIMEOUT_MS = 1_500L
    const val READ_TIMEOUT_MS = 2_500L

    /** Below this much time left an attempt could not even connect, so the loop stops. */
    const val MIN_ATTEMPT_MS = 250L

    @Suppress("LongParameterList") // the loop is exactly these seams
    fun run(
        offAt: Long,
        clock: () -> Long,
        sleep: (Long) -> Unit,
        attempt: (connectMs: Int, readMs: Int) -> AttemptResult,
        onAttempt: (n: Int, rc: String, sinceOffMs: Long) -> Unit,
    ): RetryResult {
        var n = 0
        var rc = OFF_REPORT_NO_RC
        while (true) {
            val left = offAt + DEADLINE_MS - clock()
            if (left < MIN_ATTEMPT_MS) break
            n++
            val result = attempt(minOf(CONNECT_TIMEOUT_MS, left).toInt(), minOf(READ_TIMEOUT_MS, left).toInt())
            rc = result.rc
            onAttempt(n, rc, clock() - offAt)
            when (result.verdict) {
                AttemptResult.Verdict.SENT -> return RetryResult(true, n, rc)
                AttemptResult.Verdict.STOP -> return RetryResult(false, n, rc)
                AttemptResult.Verdict.RETRY -> Unit
            }
            val wait = minOf(INTERVAL_MS, offAt + DEADLINE_MS - clock())
            if (wait > 0) sleep(wait)
        }
        return RetryResult(false, n, rc)
    }

    /** 2xx sent; 429 and 5xx are Telegram busy (retry); any other code will not heal by retrying. */
    fun verdictFor(httpCode: Int): AttemptResult.Verdict = when {
        httpCode in HTTP_OK_FIRST..HTTP_OK_LAST -> AttemptResult.Verdict.SENT
        httpCode == HTTP_TOO_MANY_REQUESTS || httpCode >= HTTP_SERVER_ERROR -> AttemptResult.Verdict.RETRY
        else -> AttemptResult.Verdict.STOP
    }

    private const val HTTP_OK_FIRST = 200
    private const val HTTP_OK_LAST = 299
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500
}
