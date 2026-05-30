package com.bydmate.app.data.vehicle

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/** One display as reported by the daemon's DisplayManager.getDisplays(). */
data class DisplayInfo(
    val id: Int,
    val name: String,
    val width: Int,
    val height: Int,
    val densityDpi: Int,
)

/**
 * Client for the in-vehicle helper daemon registered as the `bydmate_helper`
 * binder service (ServiceManager.getService + IBinder.transact).
 *
 * read()/write()/isAlive() return null/false on any failure: daemon not
 * registered, dead binder, transact rejected by the daemon's uid gate, or
 * an autoservice error. Callers must treat null/false as "channel
 * unavailable, retry later".
 *
 * The hidden-API exemption for android.os.ServiceManager is installed in
 * BYDMateApp.onCreate — do NOT add HiddenApiBypass here.
 */
interface HelperClient {
    suspend fun read(dev: Int, fid: Int, tx: Int = 5): Long?
    suspend fun write(dev: Int, fid: Int, value: Int): Boolean
    suspend fun isAlive(): Boolean

    /** All displays visible to the shell-uid daemon, or null if the channel is unavailable. */
    suspend fun listDisplays(): List<DisplayInfo>?

    /**
     * Reads a BYDAuto instrument/setting feature value, or null when no data
     * (feature absent on this trim) or the channel is unavailable. A real 0 is returned as 0.
     */
    suspend fun getInstrumentFeature(featureId: Int): Int?
}

@Singleton
open class HelperClientImpl @Inject constructor() : HelperClient {
    private val mutex = Mutex()
    @Volatile private var cached: IBinder? = null

    override suspend fun read(dev: Int, fid: Int, tx: Int): Long? =
        transact(HelperBinderProtocol.TX_READ) { it.writeInt(tx); it.writeInt(dev); it.writeInt(fid) }
            ?.let { (status, value) -> if (readAccepted(status)) value.toLong() else null }

    override suspend fun write(dev: Int, fid: Int, value: Int): Boolean {
        val status = transact(HelperBinderProtocol.TX_WRITE) {
            it.writeInt(dev); it.writeInt(fid); it.writeInt(value)
        }?.first
        // status forwarded from the autoservice setInt return code: 1 = real action,
        // 0 = accepted no-op (fid ineffective on this trim), <0 = error, null =
        // daemon unreachable. Logged at INFO so a "green" automation that physically
        // did nothing (no-op) is distinguishable from one that actually moved the actuator.
        Log.i(TAG, "write dev=$dev fid=$fid value=$value status=$status accepted=${status != null && writeAccepted(status)}")
        return status?.let { writeAccepted(it) } ?: false
    }

    override suspend fun isAlive(): Boolean =
        transact(HelperBinderProtocol.TX_PING) { }
            ?.let { (status, _) -> readAccepted(status) } ?: false

    override suspend fun listDisplays(): List<DisplayInfo>? =
        transactParsed(HelperBinderProtocol.TX_LIST_DISPLAYS, { /* no args */ }) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed null
            if (status != 0) return@transactParsed null
            val count = if (reply.dataAvail() >= 4) reply.readInt() else 0
            val out = ArrayList<DisplayInfo>(count)
            repeat(count) {
                val id = reply.readInt()
                val name = reply.readString() ?: ""
                val width = reply.readInt()
                val height = reply.readInt()
                val densityDpi = reply.readInt()
                out.add(DisplayInfo(id, name, width, height, densityDpi))
            }
            out
        }

    override suspend fun getInstrumentFeature(featureId: Int): Int? =
        transact(HelperBinderProtocol.TX_GET_INSTRUMENT_FEATURE) { it.writeInt(featureId) }
            ?.let { (status, value) -> if (readAccepted(status)) value else null }

    /** Returns the parsed reply or null on any failure. Retries ONCE on a dead cached binder. */
    private suspend fun <T> transactParsed(
        code: Int,
        writeArgs: (Parcel) -> Unit,
        parse: (Parcel) -> T?,
    ): T? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(REQ_TIMEOUT_MS) {
                mutex.withLock {
                    repeat(2) { attempt ->
                        val binder = ensureBinder() ?: return@withLock null
                        val data = Parcel.obtain()
                        val reply = Parcel.obtain()
                        try {
                            data.writeInterfaceToken(HelperBinderProtocol.DESCRIPTOR)
                            writeArgs(data)
                            val ok = binder.transact(code, data, reply, 0)
                            if (!ok) return@withLock null           // uid gate rejected / not handled
                            return@withLock parse(reply)
                        } catch (e: DeadObjectException) {
                            cached = null                            // stale binder; loop re-resolves once
                            if (attempt == 1) { Log.w(TAG, "binder dead after retry: ${e.message}"); return@withLock null }
                        } catch (e: Exception) {
                            Log.w(TAG, "transact failed: ${e.message}"); return@withLock null
                        } finally {
                            data.recycle(); reply.recycle()
                        }
                    }
                    null
                }
            }
        }

    /** (status, value) wrapper used by read/write/ping/getInstrumentFeature. */
    private suspend fun transact(code: Int, writeArgs: (Parcel) -> Unit): Pair<Int, Int>? =
        transactParsed(code, writeArgs) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed null
            val value = if (reply.dataAvail() >= 4) reply.readInt() else 0
            status to value
        }

    private fun ensureBinder(): IBinder? {
        cached?.takeIf { it.isBinderAlive }?.let { return it }
        return resolveBinder()?.also { cached = it }
    }

    /** Production: look the daemon up by name. Overridable so tests can inject a fake IBinder. */
    internal open fun resolveBinder(): IBinder? = try {
        val sm = Class.forName("android.os.ServiceManager")
        (sm.getMethod("getService", String::class.java)
            .invoke(null, HelperBinderProtocol.SERVICE_NAME) as? IBinder)
    } catch (e: Exception) {
        Log.w(TAG, "getService failed: ${e.message}"); null
    }

    companion object {
        /**
         * The daemon forwards the raw autoservice transact return code in status.
         * Validated on Leopard 3 2026-05-28:
         *   setInt real action → status = 1
         *   setInt no-op       → status = 0
         *   getInt success     → status = 0 (value follows)
         *   error / no reply   → status < 0 (-1 daemon exception, -999 no reply data)
         *
         * A write is accepted on status >= 0 (using == 0 marked every real action
         * as a failure). A read is accepted only on status == 0.
         * See HelperClientStatusTest.
         */
        internal fun writeAccepted(status: Int): Boolean = status >= 0
        internal fun readAccepted(status: Int): Boolean = status == 0

        private const val TAG = "HelperClient"
        private const val REQ_TIMEOUT_MS = 2000L
    }
}
