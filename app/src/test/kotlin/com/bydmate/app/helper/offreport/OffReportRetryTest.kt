package com.bydmate.app.helper.offreport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The send loop on a fake clock: every attempt and every pause moves the time by hand. */
class OffReportRetryTest {

    private var now = 1_000L
    private val offAt = 1_000L
    private val attemptsAt = mutableListOf<Long>()
    private val timeouts = mutableListOf<Pair<Int, Int>>()

    private fun run(attemptCostMs: Long, answer: (n: Int) -> AttemptResult): RetryResult =
        OffReportRetry.run(
            offAt = offAt,
            clock = { now },
            sleep = { now += it },
            attempt = { connectMs, readMs ->
                attemptsAt += now - offAt
                timeouts += connectMs to readMs
                now += attemptCostMs
                answer(attemptsAt.size)
            },
            onAttempt = { _, _, _ -> },
        )

    private val io = AttemptResult("io:UnknownHostException", AttemptResult.Verdict.RETRY)

    @Test fun `the first attempt goes out at once and a success ends the loop`() {
        val result = run(100) { AttemptResult("200", AttemptResult.Verdict.SENT) }
        assertTrue(result.sent)
        assertEquals(1, result.attempts)
        assertEquals(listOf(0L), attemptsAt)
    }

    @Test fun `failures are retried every half second until one goes through`() {
        val result = run(100) { n -> if (n < 3) io else AttemptResult("200", AttemptResult.Verdict.SENT) }
        assertTrue(result.sent)
        assertEquals(3, result.attempts)
        assertEquals(listOf(0L, 600L, 1_200L), attemptsAt)
    }

    @Test fun `nothing is attempted past 8 s after the power-off`() {
        val result = run(100) { io }
        assertFalse(result.sent)
        assertEquals("io:UnknownHostException", result.rc)
        assertFalse(result.refused)
        assertTrue(attemptsAt.all { it <= OffReportRetry.DEADLINE_MS - OffReportRetry.MIN_ATTEMPT_MS })
        assertTrue(now - offAt <= OffReportRetry.DEADLINE_MS)
        assertEquals(13, result.attempts)
    }

    @Test fun `timeouts shrink to what is left of the window`() {
        run(1_000) { io }
        assertEquals(OffReportRetry.CONNECT_TIMEOUT_MS.toInt() to OffReportRetry.READ_TIMEOUT_MS.toInt(), timeouts.first())
        val (lastConnect, lastRead) = timeouts.last()
        val lastLeft = OffReportRetry.DEADLINE_MS - attemptsAt.last()
        assertTrue(lastConnect <= lastLeft && lastRead <= lastLeft)
    }

    @Test fun `a refusal for good stops at once`() {
        val result = run(100) { AttemptResult("400", OffReportRetry.verdictFor(400)) }
        assertFalse(result.sent)
        assertEquals(1, result.attempts)
        assertEquals("400", result.rc)
        assertTrue(result.refused)
    }

    @Test fun `a cancel seen before an attempt ends the loop as refused with its reason`() {
        var cancel: String? = null
        val result = OffReportRetry.run(
            offAt = offAt,
            clock = { now },
            sleep = { now += it },
            attempt = { _, _ ->
                attemptsAt += now - offAt
                now += 100
                cancel = "disarm"
                io
            },
            onAttempt = { _, _, _ -> },
            cancelled = { cancel },
        )
        assertFalse(result.sent)
        assertTrue(result.refused)
        assertEquals("disarm", result.rc)
        assertEquals(1, result.attempts)
        assertEquals(1, attemptsAt.size)
    }

    @Test fun `busy and server errors retry, other codes stop`() {
        assertEquals(AttemptResult.Verdict.SENT, OffReportRetry.verdictFor(200))
        assertEquals(AttemptResult.Verdict.RETRY, OffReportRetry.verdictFor(429))
        assertEquals(AttemptResult.Verdict.RETRY, OffReportRetry.verdictFor(502))
        assertEquals(AttemptResult.Verdict.STOP, OffReportRetry.verdictFor(401))
        assertEquals(AttemptResult.Verdict.STOP, OffReportRetry.verdictFor(403))
    }

    @Test fun `a hanging lookup is given up after its bound`() {
        val release = CountDownLatch(1)
        val started = System.nanoTime()
        val answer = OffReportRetry.callWithin(200L) { release.await(10, TimeUnit.SECONDS); "late" }
        val tookMs = (System.nanoTime() - started) / 1_000_000
        release.countDown()
        assertNull(answer)
        assertTrue("took $tookMs ms", tookMs < 2_000)
    }

    @Test fun `a quick lookup returns its value and its own exception comes through`() {
        assertEquals("ok", OffReportRetry.callWithin(2_000L) { "ok" })
        val thrown = runCatching { OffReportRetry.callWithin(2_000L) { throw UnknownHostException("x") } }
        assertTrue(thrown.exceptionOrNull() is UnknownHostException)
    }
}
