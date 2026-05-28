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
            if (it.status == 0) it.value else null
        }

    override suspend fun write(dev: Int, fid: Int, value: Int): Boolean =
        send(HelperRequest(op = "write", tx = 6, dev = dev, fid = fid, value = value.toLong()))
            ?.let { it.status == 0 } ?: false

    override suspend fun isAlive(): Boolean =
        send(HelperRequest(op = "ping"))?.status == 0

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
        private const val TAG = "HelperClient"
        private const val PORT = 8765
        private const val CONNECT_TIMEOUT_MS = 500
        private const val SO_TIMEOUT_MS = 1500
        private const val REQ_TIMEOUT_MS = 2000L
    }
}
