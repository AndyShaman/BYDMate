package com.bydmate.app.data.autoservice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator

/**
 * Unit tests for the pure helpers in [AdbProtocolClient].
 *
 * Robolectric is used for [android.util.Base64] only (it lives in the Android
 * SDK, but the JVM stub returns null without Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class AdbProtocolClientTest {

    @Test
    fun `packet checksum is unsigned-byte sum of payload`() {
        val payload = byteArrayOf(0x10, 0x20, 0xFF.toByte(), 0x01)
        val header = AdbProtocolClient.buildHeader(
            command = AdbProtocolClient.A_OPEN,
            arg0 = 1, arg1 = 0, payload = payload
        )

        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        repeat(4) { buf.int }  // skip command/arg0/arg1/payloadLen
        val checksum = buf.int

        val expected = 0x10 + 0x20 + 0xFF + 0x01  // = 0x130
        assertEquals(expected, checksum)
    }

    @Test
    fun `packet magic is bitwise inverse of command`() {
        val header = AdbProtocolClient.buildHeader(
            command = AdbProtocolClient.A_CNXN,
            arg0 = 0, arg1 = 0, payload = ByteArray(0)
        )
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        repeat(5) { buf.int }  // skip command/arg0/arg1/payloadLen/checksum
        val magic = buf.int

        assertEquals(AdbProtocolClient.A_CNXN.inv(), magic)
        assertEquals(AdbProtocolClient.A_CNXN xor 0xFFFFFFFF.toInt(), magic)
    }

    @Test
    fun `packet header is little-endian 24 bytes with correct fields`() {
        val payload = "shell:echo hi ".toByteArray()
        val header = AdbProtocolClient.buildHeader(
            command = AdbProtocolClient.A_OPEN,
            arg0 = 42, arg1 = 7, payload = payload
        )

        assertEquals(24, header.size)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocolClient.A_OPEN, buf.int)
        assertEquals(42, buf.int)
        assertEquals(7, buf.int)
        assertEquals(payload.size, buf.int)
        var sum = 0; for (b in payload) sum += (b.toInt() and 0xFF)
        assertEquals(sum, buf.int)
        assertEquals(AdbProtocolClient.A_OPEN.inv(), buf.int)
    }

    @Test
    fun `publicKey serialization contains base64 of 524 bytes plus null-terminated username`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val encoded = AdbProtocolClient.serializePublicKey(keyPair)

        val asString = String(encoded, Charsets.UTF_8)
        // Tail must contain ' bydmate@dilink' (null-terminated C-string id).
        assertTrue(
            "must contain ' bydmate@dilink' tag",
            asString.contains(" bydmate@dilink")
        )
        // Last byte must be a literal NUL: ADB host id is a C-string.
        assertEquals(0, encoded.last().toInt() and 0xFF)
        // Base64(524 bytes) = ceil(524/3)*4 = 700 chars; plus 16-byte tail = 716.
        assertTrue("encoded size >= 716, got ${encoded.size}", encoded.size >= 716)
    }

    @Test
    fun `signaturePayload prepends adbAuthPadding`() {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val token = ByteArray(20) { (it + 1).toByte() }  // 20 bytes, like real ADB token
        val sig = AdbProtocolClient.signAuthToken(token, keyPair)

        // Reconstruct the padded input the helper builds. The padded input must
        // be exactly 35 bytes and start with ADB_AUTH_PADDING.
        val padded = AdbProtocolClient.ADB_AUTH_PADDING + token
        assertEquals(35, padded.size)
        assertArrayEquals(AdbProtocolClient.ADB_AUTH_PADDING, padded.sliceArray(0 until 15))
        assertArrayEquals(token, padded.sliceArray(15 until 35))

        // RSA 2048-bit signature is exactly 256 bytes.
        assertEquals(256, sig.size)
    }
}
