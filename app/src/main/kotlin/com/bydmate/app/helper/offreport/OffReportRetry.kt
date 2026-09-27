package com.bydmate.app.helper.offreport

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** What one send attempt came to: [rc] for the log, [verdict] for the loop. */
internal class AttemptResult(val rc: String, val verdict: Verdict) {
    enum class Verdict { SENT, RETRY, STOP }
}

/** [refused]: Telegram said no for good (a 4xx other than 429), retrying later will not help. */
internal class RetryResult(val sent: Boolean, val attempts: Int, val rc: String, val refused: Boolean = false)

/**
 * Sends until one attempt goes through, Telegram refuses for good, or [OffReportRetry.DEADLINE_MS]
 * after the power-off has passed. Wi-Fi goes about 1 s after the power-off and the car's mobile data
 * 3-4 s after (L3 measurement 2026-09-27), so attempts follow each other every
 * [OffReportRetry.INTERVAL_MS] with timeouts clamped to what is left of the window.
 *
 * [offAt] and [clock] are on the same monotonic clock; [attempt] gets the connect and read timeouts,
 * each capped to what is left of the window. No attempt starts after the deadline; one already in
 * flight may overrun it by at most its own bound (the sender's watchdog cuts the connection at
 * connect + read timeout; the daemon has a single sender thread, so nothing else sends the same
 * report meanwhile, and a report that did not go out waits on disk). An [attempt] that may not send
 * any more (disarmed, another chat) answers STOP with its reason as rc, ending the loop as refused.
 *
 * Not idempotent (accepted): when Telegram took the POST but the answer was lost with the network,
 * the next attempt or the later pending delivery sends the report a second time. The Bot API has
 * no dedup key for sendMessage.
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
                AttemptResult.Verdict.STOP -> return RetryResult(false, n, rc, refused = true)
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

    /**
     * Runs [block] on a throwaway daemon thread and waits at most [timeoutMs]: DNS lookups ignore
     * the connect timeout and can hang for seconds while the networks go down. Null on timeout (the
     * thread is interrupted and left behind; it cannot send anything by itself); the block's own
     * exception is rethrown.
     */
    fun <T> callWithin(timeoutMs: Long, block: () -> T): T? {
        val task = FutureTask(block)
        Thread(task, "offreport-dns").apply { isDaemon = true }.start()
        return try {
            task.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (@Suppress("SwallowedException") e: TimeoutException) { // the timeout is the answer: null
            task.cancel(true)
            null
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private const val HTTP_OK_FIRST = 200
    private const val HTTP_OK_LAST = 299
    private const val HTTP_TOO_MANY_REQUESTS = 429
    private const val HTTP_SERVER_ERROR = 500
}
