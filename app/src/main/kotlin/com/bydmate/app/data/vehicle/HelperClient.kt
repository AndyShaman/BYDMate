package com.bydmate.app.data.vehicle

import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import android.view.Surface
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

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

    /** Creates a VirtualDisplay backed by [surface]; returns its displayId (>0) or null. */
    suspend fun createVirtualDisplay(
        name: String, width: Int, height: Int, density: Int, flags: Int, surface: Surface,
    ): Int?
    suspend fun releaseVirtualDisplay(displayId: Int): Boolean
    suspend fun launchApp(packageName: String): Boolean
    /** Task id of [packageName]'s running task, or null if not running / channel unavailable. */
    suspend fun getTaskId(packageName: String): Int?
    suspend fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean
    suspend fun setTaskBounds(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean
    suspend fun setFocusedTask(taskId: Int): Boolean
    suspend fun setTaskWindowingMode(taskId: Int, windowingMode: Int): Boolean
    /**
     * Narrow appops grant to our own package: SYSTEM_ALERT_WINDOW (draw the cluster overlay)
     * and PROJECT_MEDIA (third-party access to the fission screen-projection display, else
     * getDisplay(clusterId) returns null). Returns true only if both grants succeed.
     */
    suspend fun grantOverlayPermission(): Boolean
    /** Launches [packageName] on [displayId] and pins it (move+bounds+focus loop). Long-running. */
    suspend fun launchAndForce(packageName: String, displayId: Int, width: Int, height: Int): Boolean
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

    override suspend fun createVirtualDisplay(
        name: String, width: Int, height: Int, density: Int, flags: Int, surface: Surface,
    ): Int? = transactParsed(HelperBinderProtocol.TX_CREATE_VIRTUAL_DISPLAY, { d ->
        d.writeString(name); d.writeInt(width); d.writeInt(height); d.writeInt(density); d.writeInt(flags)
        surface.writeToParcel(d, 0)
    }) { reply ->
        val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed null
        val id = if (reply.dataAvail() >= 4) reply.readInt() else -1
        if (readAccepted(status) && id > 0) id else null
    }

    override suspend fun releaseVirtualDisplay(displayId: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_RELEASE_VIRTUAL_DISPLAY) { it.writeInt(displayId) }

    override suspend fun launchApp(packageName: String): Boolean =
        statusOk(HelperBinderProtocol.TX_LAUNCH_APP) { it.writeString(packageName) }

    override suspend fun getTaskId(packageName: String): Int? =
        transact(HelperBinderProtocol.TX_GET_TASK_ID) { it.writeString(packageName) }
            ?.let { (status, value) -> if (readAccepted(status) && value > 0) value else null }

    override suspend fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_MOVE_TASK_TO_DISPLAY) { it.writeInt(taskId); it.writeInt(displayId) }

    override suspend fun setTaskBounds(taskId: Int, left: Int, top: Int, right: Int, bottom: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_TASK_BOUNDS) {
            it.writeInt(taskId); it.writeInt(left); it.writeInt(top); it.writeInt(right); it.writeInt(bottom)
        }

    override suspend fun setFocusedTask(taskId: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_FOCUSED_TASK) { it.writeInt(taskId) }

    override suspend fun setTaskWindowingMode(taskId: Int, windowingMode: Int): Boolean =
        statusOk(HelperBinderProtocol.TX_SET_TASK_WINDOWING_MODE) { it.writeInt(taskId); it.writeInt(windowingMode) }

    override suspend fun grantOverlayPermission(): Boolean =
        statusOk(HelperBinderProtocol.TX_GRANT_OVERLAY_PERMISSION) { }

    override suspend fun launchAndForce(packageName: String, displayId: Int, width: Int, height: Int): Boolean =
        transactParsed(HelperBinderProtocol.TX_LAUNCH_AND_FORCE, { d ->
            d.writeString(packageName); d.writeInt(displayId); d.writeInt(width); d.writeInt(height)
        }, timeoutMs = FORCE_TIMEOUT_MS) { reply ->
            val status = if (reply.dataAvail() >= 4) reply.readInt() else return@transactParsed false
            readAccepted(status)
        } ?: false

    /** (status,value) reply; true iff status == 0. Shared by the boolean projection ops. */
    private suspend fun statusOk(code: Int, writeArgs: (Parcel) -> Unit): Boolean =
        transact(code, writeArgs)?.let { (status, _) -> readAccepted(status) } ?: false

    /** Returns the parsed reply or null on any failure. Retries ONCE on a dead cached binder. */
    private suspend fun <T> transactParsed(
        code: Int,
        writeArgs: (Parcel) -> Unit,
        timeoutMs: Long = REQ_TIMEOUT_MS,
        parse: (Parcel) -> T?,
    ): T? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(timeoutMs) {
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

    /** (status, value) wrapper used by read/write/ping. */
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
        private const val FORCE_TIMEOUT_MS = 15000L
    }
}
