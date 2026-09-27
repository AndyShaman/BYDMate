package com.bydmate.app.helper.offreport

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import kotlin.concurrent.thread

/**
 * The daemon's real sendMessage attempt against a local server that answers a byte at a time and
 * never finishes: the per-attempt watchdog must end it by its deadline either way.
 */
class OffReportSendTest {

    private lateinit var server: ServerSocket
    private val realEndpoint = OffReport.endpoint
    private val clients = mutableListOf<Socket>()

    @Before fun setUp() {
        server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
        OffReport.endpoint = { URL("http://127.0.0.1:${server.localPort}/bot$it/sendMessage") }
    }

    @After fun tearDown() {
        OffReport.endpoint = realEndpoint
        server.close()
        synchronized(clients) { clients.forEach { runCatching { it.close() } } }
    }

    /** Accepts one connection, writes [head] at once, then one byte of [drip] every 50 ms until closed. */
    private fun trickle(head: String, drip: Char) {
        thread(isDaemon = true, name = "trickle") {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(clients) { clients += socket }
            runCatching {
                val out = socket.getOutputStream()
                out.write(head.toByteArray())
                out.flush()
                while (!socket.isClosed) {
                    Thread.sleep(50)
                    out.write(drip.code)
                    out.flush()
                }
            }
        }
    }

    private fun timed(block: () -> AttemptResult): Pair<AttemptResult, Long> {
        val start = System.nanoTime()
        val result = block()
        return result to (System.nanoTime() - start) / 1_000_000
    }

    @Test fun `a status line that never ends is cut at the attempt deadline and retried`() {
        trickle("HTTP/1.1", '1')
        val (result, tookMs) = timed { OffReport.postSendMessage("tok", 42L, "t", 300, 700) }
        assertTrue("took $tookMs ms", tookMs < 3_000)
        assertTrue(result.rc, result.rc.startsWith("io:"))
        assertEquals(AttemptResult.Verdict.RETRY, result.verdict)
    }

    @Test fun `headers that never end are cut at the attempt deadline`() {
        trickle("HTTP/1.1 502 Bad Gateway\r\nX-Slow: ", 'a')
        val (result, tookMs) = timed { OffReport.postSendMessage("tok", 42L, "t", 300, 700) }
        assertTrue("took $tookMs ms", tookMs < 3_000)
        assertEquals(AttemptResult.Verdict.RETRY, result.verdict)
    }

    @Test fun `a 200 whose body never ends counts as sent without waiting for the body`() {
        trickle("HTTP/1.1 200 OK\r\nContent-Length: 1000000\r\n\r\n", 'b')
        val (result, tookMs) = timed { OffReport.postSendMessage("tok", 42L, "t", 300, 700) }
        assertTrue("took $tookMs ms", tookMs < 3_000)
        assertEquals("200", result.rc)
        assertEquals(AttemptResult.Verdict.SENT, result.verdict)
    }
}
