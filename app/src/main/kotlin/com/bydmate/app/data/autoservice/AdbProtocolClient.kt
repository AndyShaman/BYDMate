package com.bydmate.app.data.autoservice

import android.util.Base64
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.Arrays

/**
 * Minimal seam for [AdbOnDeviceClientImpl] tests — lets the test layer swap a
 * real socket-backed client for a fake. Production binding is [AdbProtocolClient].
 */
internal interface AdbProtocol {
    fun connect(): Boolean
    fun exec(cmd: String): String?
    fun isConnected(): Boolean
    fun disconnect()
}

/**
 * Hand-rolled ADB protocol client for `127.0.0.1:5555` on the DiLink head unit.
 *
 * Why hand-rolled: the standard `adb` host runs on the user's PC, not in-app.
 * Once the user enables «Wireless ADB» in Developer Options, DiLink's `adbd`
 * starts listening on 5555; an in-process client speaking the binary ADB
 * protocol can authenticate via RSA pubkey and run `service call autoservice ...`
 * with shell uid (= hidden API access). See `reference_adb_on_device_pattern.md`.
 *
 * Protocol details ported from competitor (BYD EV Pro, AdbClient.java —
 * proven on Leopard 3): little-endian 24-byte header, payload checksum is the
 * unsigned-byte sum of payload, magic = `~command`, AUTH uses NONEwithRSA over
 * `(15-byte ASN1 padding ‖ token)`, public key is serialized as a 524-byte
 * little-endian blob (`modSizeWords | n0inv | modulus[64] | rr[64] | exponent`)
 * then Base64 + UTF-8.
 *
 * Thread safety: `connect`/`exec`/`disconnect` are mutually exclusive via
 * `synchronized(this)`; concurrent reads from multiple coroutines are safe but
 * serialized — fine for our use case (catch-up + occasional manual probe).
 */
internal class AdbProtocolClient(
    private val keyPair: KeyPair,
    private val host: String = "127.0.0.1",
    private val port: Int = 5555
) : AdbProtocol {

    @Volatile private var socket: Socket? = null
    @Volatile private var input: InputStream? = null
    @Volatile private var output: OutputStream? = null
    @Volatile private var localStreamId: Int = 1

    @Synchronized
    override fun connect(): Boolean {
        if (isConnectedInternal()) {
            Log.d(TAG, "Already connected, skipping reconnect")
            return true
        }
        disconnectInternal()
        try {
            val s = Socket(host, port).apply {
                soTimeout = SOCKET_TIMEOUT_MS
                tcpNoDelay = true
            }
            socket = s
            input = s.getInputStream()
            output = s.getOutputStream()

            val hostBanner = "host::\u0000".toByteArray(Charsets.UTF_8)
            writePacket(A_CNXN, A_VERSION_AUTH, MAX_PAYLOAD, hostBanner)

            val first = readPacket()
            return when (first.command) {
                A_CNXN -> {
                    Log.i(TAG, "Connected (no auth required)")
                    true
                }
                A_AUTH -> {
                    if (first.arg0 == AUTH_TOKEN) {
                        authenticate(first.payload)
                    } else {
                        Log.e(TAG, "Unexpected AUTH arg0=${first.arg0}")
                        disconnectInternal()
                        false
                    }
                }
                else -> {
                    Log.e(TAG, "Unexpected handshake response cmd=0x${first.command.toString(16)}")
                    disconnectInternal()
                    false
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Connect failed: ${e.message}")
            disconnectInternal()
            return false
        }
    }

    @Synchronized
    override fun exec(cmd: String): String? {
        if (!isConnectedInternal()) return null
        return try {
            val localId = localStreamId
            localStreamId += 1

            val payload = "shell:$cmd\u0000".toByteArray(Charsets.UTF_8)
            writePacket(A_OPEN, localId, 0, payload)

            // Wait up to 20 packets for OKAY matching our localId.
            var remoteId = 0
            var gotOkay = false
            for (i in 0 until 20) {
                val pkt = readPacket()
                if (pkt.command == A_OKAY && pkt.arg1 == localId) {
                    remoteId = pkt.arg0
                    gotOkay = true
                    break
                }
                handleStalePacket(pkt, localId)
            }
            if (!gotOkay) {
                Log.e(TAG, "shell: never got OKAY for localId=$localId")
                return null
            }

            // Accumulate WRTE payloads; ack each; stop on CLSE (and ack it back).
            val sb = StringBuilder()
            for (i in 0 until 500) {
                val pkt = readPacket()
                when {
                    pkt.command == A_WRTE && pkt.arg0 == remoteId && pkt.arg1 == localId -> {
                        sb.append(String(pkt.payload, Charsets.UTF_8))
                        writePacket(A_OKAY, localId, remoteId, EMPTY)
                    }
                    pkt.command == A_CLSE && pkt.arg0 == remoteId -> {
                        writePacket(A_CLSE, localId, remoteId, EMPTY)
                        break
                    }
                    else -> handleStalePacket(pkt, localId)
                }
            }
            sb.toString().trim()
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${e.message}")
            disconnectInternal()
            null
        }
    }

    @Synchronized
    override fun isConnected(): Boolean = isConnectedInternal()

    @Synchronized
    override fun disconnect() {
        disconnectInternal()
    }

    // --- internals ---

    private fun isConnectedInternal(): Boolean {
        val s = socket ?: return false
        return s.isConnected && !s.isClosed
    }

    private fun disconnectInternal() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
        input = null
        output = null
        localStreamId = 1
    }

    /**
     * Handles the AUTH challenge:
     * 1. Sign `(ADB_AUTH_PADDING ‖ token)` with our private key, send AUTH(SIG).
     * 2. If device returns CNXN — key was already authorized (cached on device).
     * 3. If device returns AUTH(TOKEN) again — key not recognized; send the
     *    524-byte ADB-format pubkey (AUTH(RSAPUBLICKEY)). DiLink shows the
     *    native «Allow USB debugging?» dialog — we wait up to 60s for the user
     *    to accept; on accept device sends CNXN.
     */
    private fun authenticate(token: ByteArray): Boolean {
        val sig = signAuthToken(token, keyPair)
        Log.d(TAG, "Auth step 1: sending signature (${sig.size}B)")
        writePacket(A_AUTH, AUTH_SIGNATURE, 0, sig)

        var resp = readPacket()
        if (resp.command == A_CNXN) {
            Log.i(TAG, "Connected via signature auth (key was known)")
            return true
        }
        if (resp.command == A_AUTH && resp.arg0 == AUTH_TOKEN) {
            val pub = serializePublicKey(keyPair)
            Log.d(TAG, "Sending RSA public key for device approval (${pub.size}B)")
            writePacket(A_AUTH, AUTH_RSAPUBLICKEY, 0, pub)

            // User must tap "Allow" on DiLink — give them up to 60s.
            val s = socket
            try {
                s?.soTimeout = USER_PROMPT_TIMEOUT_MS
                resp = readPacket()
            } finally {
                s?.soTimeout = SOCKET_TIMEOUT_MS
            }
            if (resp.command == A_CNXN) {
                Log.i(TAG, "Connected via public key auth (user accepted)")
                return true
            }
        }
        Log.e(TAG, "Auth failed. Last response: 0x${resp.command.toString(16)}")
        disconnectInternal()
        return false
    }

    /** Per competitor `a()` — silently reply CLSE/OKAY for stale streams. */
    private fun handleStalePacket(pkt: Packet, currentLocalId: Int) {
        when (pkt.command) {
            A_CLSE -> writePacket(A_CLSE, pkt.arg1, pkt.arg0, EMPTY)
            A_WRTE -> writePacket(A_OKAY, pkt.arg1, pkt.arg0, EMPTY)
            else -> {
                Log.d(TAG, "shell: skipping stale 0x${pkt.command.toString(16)} " +
                    "(arg0=${pkt.arg0} arg1=${pkt.arg1}, current=$currentLocalId)")
            }
        }
    }

    private fun writePacket(command: Int, arg0: Int, arg1: Int, payload: ByteArray) {
        val out = output ?: throw IOException("not connected")
        val header = buildHeader(command, arg0, arg1, payload)
        out.write(header)
        if (payload.isNotEmpty()) out.write(payload)
        out.flush()
    }

    private fun readPacket(): Packet {
        val header = ByteBuffer.wrap(readBytes(24)).order(ByteOrder.LITTLE_ENDIAN)
        val command = header.int
        val arg0 = header.int
        val arg1 = header.int
        val payloadLen = header.int
        header.int  // checksum — ignored on read
        header.int  // magic — ignored on read
        val payload = if (payloadLen > 0) readBytes(payloadLen) else EMPTY
        return Packet(command, arg0, arg1, payload)
    }

    private fun readBytes(n: Int): ByteArray {
        val ins = input ?: throw IOException("not connected")
        val buf = ByteArray(n)
        var read = 0
        while (read < n) {
            val r = ins.read(buf, read, n - read)
            if (r < 0) throw IOException("EOF after $read of $n bytes")
            read += r
        }
        return buf
    }

    private data class Packet(
        val command: Int,
        val arg0: Int,
        val arg1: Int,
        val payload: ByteArray
    )

    companion object {
        // ADB protocol magic constants (little-endian ASCII) — see system/core/adb/transport.cpp.
        const val A_CNXN: Int = 0x4E584E43          // "CNXN"
        const val A_AUTH: Int = 0x48545541          // "AUTH"
        const val A_OPEN: Int = 0x4E45504F          // "OPEN"
        const val A_OKAY: Int = 0x59414B4F          // bytes 'O','K','A','Y' LE
        const val A_CLSE: Int = 0x45534C43          // bytes 'C','L','S','E' LE
        const val A_WRTE: Int = 0x45545257          // bytes 'W','R','T','E' LE

        const val A_VERSION_AUTH: Int = 0x01000001  // ADB version negotiated for AUTH-style handshake
        const val MAX_PAYLOAD: Int = 262144

        const val AUTH_TOKEN: Int = 1
        const val AUTH_SIGNATURE: Int = 2
        const val AUTH_RSAPUBLICKEY: Int = 3

        const val SOCKET_TIMEOUT_MS = 5_000
        const val USER_PROMPT_TIMEOUT_MS = 60_000

        private const val TAG = "AdbProtocolClient"

        private val EMPTY = ByteArray(0)

        /**
         * 15-byte ASN.1 DigestInfo prefix for SHA-1 used by ADB's AUTH.
         * The device verifies signature with `RSA(PKCS1v15, SHA-1)` but expects
         * the host to pre-pad — hence `NONEwithRSA` + this exact prefix.
         */
        internal val ADB_AUTH_PADDING: ByteArray = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2B, 0x0E,
            0x03, 0x02, 0x1A, 0x05, 0x00, 0x04, 0x14
        )

        // ADB daemon expects a null-terminated host id after the base64 pubkey
        // (see system/core/adb/transport.cpp). Competitor uses the same shape.
        private const val ADB_PUBKEY_USERNAME = " bydmate@dilink\u0000"

        /**
         * Builds the 24-byte little-endian ADB packet header.
         *
         * Layout: `[command(4)][arg0(4)][arg1(4)][payloadLen(4)][checksum(4)][magic(4)]`.
         * - `checksum` = sum of `payload[i] and 0xFF` over all payload bytes (low-byte unsigned).
         * - `magic`    = `command.inv()` (bitwise complement).
         */
        internal fun buildHeader(command: Int, arg0: Int, arg1: Int, payload: ByteArray): ByteArray {
            val buf = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(command)
            buf.putInt(arg0)
            buf.putInt(arg1)
            buf.putInt(payload.size)
            var checksum = 0
            for (b in payload) checksum += (b.toInt() and 0xFF)
            buf.putInt(checksum)
            buf.putInt(command.inv())
            return buf.array()
        }

        /**
         * Pre-pends [ADB_AUTH_PADDING] to the AUTH token and signs with RSA.
         * Algorithm `NONEwithRSA` skips digest, performs only PKCS#1 v1.5
         * padding — that's what the ADB daemon verifies against.
         */
        internal fun signAuthToken(token: ByteArray, keyPair: KeyPair): ByteArray {
            val padded = Arrays.copyOf(ADB_AUTH_PADDING, ADB_AUTH_PADDING.size + token.size)
            System.arraycopy(token, 0, padded, ADB_AUTH_PADDING.size, token.size)
            val sig = Signature.getInstance("NONEwithRSA")
            sig.initSign(keyPair.private)
            sig.update(padded)
            return sig.sign()
        }

        /**
         * Serializes an RSA public key into the ADB pubkey format the daemon
         * expects: 524 bytes little-endian, then Base64-encoded (no wrap),
         * then `" bydmate@dilink\u0000"` is appended as the host id (NUL-terminated).
         */
        internal fun serializePublicKey(keyPair: KeyPair): ByteArray {
            val pub = keyPair.public as RSAPublicKey
            var modulus = pub.modulus
            val ONE = BigInteger.ONE
            val r32 = ONE.shiftLeft(32)
            val mask32 = r32.subtract(ONE)
            val n0inv = modulus.and(mask32).modInverse(r32).negate().mod(r32)
            var rr = ONE.shiftLeft(4096).mod(modulus)

            val buf = ByteBuffer.allocate(524).order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(64)               // modulus size in 32-bit words
            buf.putInt(n0inv.toInt())    // n0inv
            for (i in 0 until 64) {
                buf.putInt(modulus.and(mask32).toInt())
                modulus = modulus.shiftRight(32)
            }
            for (i in 0 until 64) {
                buf.putInt(rr.and(mask32).toInt())
                rr = rr.shiftRight(32)
            }
            buf.putInt(pub.publicExponent.toInt())

            val b64 = Base64.encodeToString(buf.array(), Base64.NO_WRAP)
            return (b64 + ADB_PUBKEY_USERNAME).toByteArray(Charsets.UTF_8)
        }
    }
}
