package com.bydmate.app.helper.offreport

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.util.concurrent.atomic.AtomicReference
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

    @Test fun `the body asks for Telegram HTML with no link preview`() {
        val body = OffReport.sendMessageBody(42L, "<b>a & b</b>")
        val fields = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("42", fields["chat_id"])
        assertEquals("HTML", fields["parse_mode"])
        assertEquals("""{"is_disabled":true}""", fields["link_preview_options"])
        assertEquals("<b>a & b</b>", fields["text"])
    }

    @Test fun `the point body carries the chat, both coordinates and no notification`() {
        val body = OffReport.sendLocationBody(42L, -22.906847, 27.56)
        val fields = body.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals(
            mapOf("chat_id" to "42", "latitude" to "-22.906847", "longitude" to "27.560000", "disable_notification" to "true"),
            fields,
        )
    }

    @Test fun `the point goes to sendLocation of the same bot`() {
        val requestLine = AtomicReference("")
        thread(isDaemon = true, name = "answer") {
            val socket = runCatching { server.accept() }.getOrNull() ?: return@thread
            synchronized(clients) { clients += socket }
            requestLine.set(socket.getInputStream().bufferedReader().readLine().orEmpty())
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                flush()
            }
        }
        val result = OffReport.postSendLocation("tok", 42L, 53.9, 27.56, 1_000, 2_000)
        assertEquals("POST /bottok/sendLocation HTTP/1.1", requestLine.get())
        assertEquals(AttemptResult.Verdict.SENT, result.verdict)
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
