package com.bydmate.app.data.autoservice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator

/**
 * The STLS (wireless debugging) branch of [AdbProtocolClient], driven by a scripted daemon:
 * a fake socket replays adbd's answers and records everything the client writes. The TLS
 * upgrade itself is a passthrough — the packet protocol above it is what this covers.
 */
class AdbProtocolClientStlsTest {

    private val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private class FakeSocket(script: ByteArray) : Socket() {
        val written = ByteArrayOutputStream()
        private val script = ByteArrayInputStream(script)

        override fun getInputStream(): InputStream = script
        override fun getOutputStream(): OutputStream = written
        override fun isConnected(): Boolean = true
        override fun isClosed(): Boolean = false
        override fun setSoTimeout(timeout: Int) {}
        override fun setTcpNoDelay(on: Boolean) {}
        override fun close() {}
    }

    private fun packet(command: Int, arg0: Int, arg1: Int, payload: ByteArray = ByteArray(0)): ByteArray =
        AdbProtocolClient.buildHeader(command, arg0, arg1, payload) + payload

    /** Header + payload of one packet, read off a raw stream. */
    private data class Parsed(val command: Int, val arg0: Int, val arg1: Int, val payload: ByteArray)

    private fun parseAll(bytes: ByteArray): List<Parsed> {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val out = mutableListOf<Parsed>()
        while (buf.remaining() >= 24) {
            val command = buf.int
            val arg0 = buf.int
            val arg1 = buf.int
            val length = buf.int
            buf.int  // checksum
            buf.int  // magic
            val payload = ByteArray(length)
            buf.get(payload)
            out += Parsed(command, arg0, arg1, payload)
        }
        return out
    }

    private fun tcpipScript(answer: String): ByteArray =
        packet(AdbProtocolClient.A_STLS, AdbProtocolClient.A_STLS_VERSION, 0) +
            packet(
                AdbProtocolClient.A_CNXN, AdbProtocolClient.A_VERSION_AUTH,
                AdbProtocolClient.MAX_PAYLOAD, "device::\u0000".toByteArray()
            ) +
            packet(AdbProtocolClient.A_OKAY, REMOTE_ID, LOCAL_ID) +
            packet(AdbProtocolClient.A_WRTE, REMOTE_ID, LOCAL_ID, answer.toByteArray()) +
            packet(AdbProtocolClient.A_CLSE, REMOTE_ID, LOCAL_ID)

    @Test
    fun `daemon offering STLS gets an STLS answer and the socket is upgraded`() {
        val socket = FakeSocket(tcpipScript("ok\n"))
        var upgraded = 0
        val client = AdbProtocolClient(
            keyPair = keyPair,
            port = 39943,
            socketFactory = { _, _ -> socket },
            tlsUpgrade = { it.also { upgraded++ } },
        )

        assertTrue(client.connect())
        assertEquals(1, upgraded)

        val sent = parseAll(socket.written.toByteArray())
        assertEquals(AdbProtocolClient.A_CNXN, sent[0].command)
        assertEquals(AdbProtocolClient.A_STLS, sent[1].command)
        assertEquals(AdbProtocolClient.A_STLS_VERSION, sent[1].arg0)
        assertEquals(0, sent[1].arg1)
        assertEquals(0, sent[1].payload.size)
    }

    @Test
    fun `tcpip service opens a stream and returns the daemon answer`() {
        val socket = FakeSocket(tcpipScript("restarting in TCP mode port: 5555\n"))
        val client = AdbProtocolClient(
            keyPair = keyPair,
            port = 39943,
            socketFactory = { _, _ -> socket },
            tlsUpgrade = { it },
        )

        assertTrue(client.connect())
        val answer = client.openService("tcpip:5555")

        assertEquals("restarting in TCP mode port: 5555", answer)
        val open = parseAll(socket.written.toByteArray()).first { it.command == AdbProtocolClient.A_OPEN }
        assertEquals("tcpip:5555\u0000", String(open.payload, Charsets.UTF_8))
    }

    @Test
    fun `plain CNXN handshake never touches TLS`() {
        val script = packet(
            AdbProtocolClient.A_CNXN, AdbProtocolClient.A_VERSION_AUTH,
            AdbProtocolClient.MAX_PAYLOAD, "device::\u0000".toByteArray()
        )
        val socket = FakeSocket(script)
        var upgraded = 0
        val client = AdbProtocolClient(
            keyPair = keyPair,
            socketFactory = { _, _ -> socket },
            tlsUpgrade = { it.also { upgraded++ } },
        )

        assertTrue(client.connect())
        assertEquals(0, upgraded)
    }

    private companion object {
        // The client numbers its first stream 1; the daemon side is arbitrary.
        const val LOCAL_ID = 1
        const val REMOTE_ID = 7
    }
}
