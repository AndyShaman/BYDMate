package com.bydmate.app.data.vehicle

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Client for the in-vehicle helper daemon listening on 127.0.0.1:8765.
 * Long-lived TCP connection (lazy reconnect on disconnect), mutex-serialized
 * request/response pairs. JSON-line protocol — see HelperProtocol.kt.
 *
 * read()/write() return null/false on any failure (daemon down, timeout,
 * parse error, autoservice SecurityException). Callers must treat
 * null/false as "channel unavailable, retry later".
 */
interface HelperClient {
    suspend fun read(dev: Int, fid: Int, tx: Int = 5): Long?
    suspend fun write(dev: Int, fid: Int, value: Int): Boolean
    suspend fun isAlive(): Boolean
}

@Singleton
class HelperClientImpl @Inject constructor() : HelperClient {
    private val mutex = Mutex()
    @Volatile private var socket: Socket? = null

    override suspend fun read(dev: Int, fid: Int, tx: Int): Long? =
        send(HelperRequest(op = "read", tx = tx, dev = dev, fid = fid))?.let {
            if (readAccepted(it.status)) it.value else null
        }

    override suspend fun write(dev: Int, fid: Int, value: Int): Boolean =
        send(HelperRequest(op = "write", tx = 6, dev = dev, fid = fid, value = value.toLong()))
            ?.let { writeAccepted(it.status) } ?: false

    override suspend fun isAlive(): Boolean =
        send(HelperRequest(op = "ping"))?.let { readAccepted(it.status) } ?: false

    private suspend fun send(req: HelperRequest): HelperResponse? = withContext(Dispatchers.IO) {
        withTimeoutOrNull(REQ_TIMEOUT_MS) {
            mutex.withLock {
                val s = ensureConnected() ?: return@withLock null
                try {
                    s.getOutputStream().apply {
                        write(req.toJsonLine().toByteArray())
                        flush()
                    }
                    val line = s.getInputStream().bufferedReader().readLine()
                        ?: return@withLock null
                    HelperResponse.fromJsonLine(line)
                } catch (e: Exception) {
                    Log.w(TAG, "send failed: ${e.message}")
                    runCatching { socket?.close() }
                    socket = null
                    null
                }
            }
        }
    }

    private fun ensureConnected(): Socket? {
        socket?.takeIf { !it.isClosed && it.isConnected }?.let { return it }
        return try {
            Socket().apply {
                connect(InetSocketAddress("127.0.0.1", PORT), CONNECT_TIMEOUT_MS)
                soTimeout = SO_TIMEOUT_MS
            }.also { socket = it }
        } catch (e: Exception) {
            Log.w(TAG, "connect failed: ${e.message}")
            null
        }
    }

    companion object {
        /**
         * The daemon forwards the raw autoservice transact return code in `status`
         * (HelperDaemon: status = reply.readInt()). On Leopard 3 a successful
         * setInt returns 1 for a real actuator move and 0 for a no-op; reads
         * return 0 with the value following; errors / no-data return negative
         * (-1 daemon exception, -999 no reply, -10011 sentinel). A write is
         * therefore accepted on status >= 0 — using == 0 (the old check) marked
         * every real action as a failure. See HelperClientStatusTest.
         */
        internal fun writeAccepted(status: Int): Boolean = status >= 0
        internal fun readAccepted(status: Int): Boolean = status == 0

        private const val TAG = "HelperClient"
        private const val PORT = 8765
        private const val CONNECT_TIMEOUT_MS = 500
        private const val SO_TIMEOUT_MS = 1500
        private const val REQ_TIMEOUT_MS = 2000L
    }
}
