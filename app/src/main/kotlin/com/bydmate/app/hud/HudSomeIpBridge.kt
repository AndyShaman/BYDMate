package com.bydmate.app.hud

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Binder
import android.os.DeadObjectException
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import kotlinx.coroutines.delay

/** Anything that can push a HUD frame; HudPushLoop depends on this, tests fake it. */
interface HudEventSink {
    fun fireEvent(topic: Long, payload: ByteArray): Int
}

/** Raw-Binder client of the DiLink SOME/IP gateway (no SDK on the head unit).
 *  Transaction map (donor SomeIpBridge, field-tested): FIRST_CALL_TRANSACTION+0
 *  registerCallback, +3 startService, +4 stopService, +5 fireEvent.
 *
 *  Lifecycle: [bound] mirrors bindService() bookkeeping (must be balanced by
 *  unbindService), [serverBinder] is the LIVE connection - null between a gateway
 *  crash and the framework's automatic reconnect. On reconnect the callback is
 *  re-registered and the active service re-opened (Codex fix 3). */
class HudSomeIpBridge(
    private val ctx: Context,
    private val onConnectionLost: () -> Unit = {},
) : HudEventSink {

    companion object {
        private const val TAG = "HudSomeIpBridge"
        private const val PKG = "com.ts.car.someip.service"
        private const val SERVER_ACTION = "com.ts.car.someip.SomeIpServerService"
        private const val DESC = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CB_DESC = "ts.car.someip.sdk.ISomeIpCallback"

        private const val TX_REGISTER_CB = IBinder.FIRST_CALL_TRANSACTION
        private const val TX_START_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 3
        private const val TX_STOP_SERVICE = IBinder.FIRST_CALL_TRANSACTION + 4
        private const val TX_FIRE_EVENT = IBinder.FIRST_CALL_TRANSACTION + 5

        const val TOPIC_NAVI = 0x4010a00018001L
        const val SERVICE_ID_NAVI = 0xB010A00010000L

        /** Cheap capability probe - MUST run before any binding or helper-daemon work:
         *  cars without the SOME/IP gateway (no factory HUD) take this exit (Codex fix 1). */
        fun isServicePresent(pm: PackageManager): Boolean =
            runCatching { pm.getPackageInfo(PKG, 0) }.isSuccess
    }

    @Volatile private var bound = false
    @Volatile private var serverBinder: IBinder? = null

    /** Service id to re-open when the framework reconnects after a gateway crash. */
    @Volatile private var activeServiceId: Long? = null

    // The gateway pings callbacks; the reply must follow the AIDL stub contract or the
    // gateway drops our registration (donor SomeIpBridge shape): INTERFACE_TRANSACTION
    // answers with the descriptor, the event transaction replies writeNoException()+
    // writeInt(0), unknown codes fall through to super. Incoming event payloads are
    // intentionally not read - we only push frames, never consume them.
    internal val callback = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean =
            when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(CB_DESC)
                    true
                }
                IBinder.FIRST_CALL_TRANSACTION -> {   // gateway event push (donor code 1)
                    data.enforceInterface(CB_DESC)
                    reply?.writeNoException()
                    reply?.writeInt(0)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
    }

    private val serverConn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            serverBinder = service
            // Reconnect path: the gateway lost our registration when it died.
            registerCallback(service)
            activeServiceId?.let { id ->
                val rc = transact(service, TX_START_SERVICE) { it.writeLong(id) }
                Log.i(TAG, "re-startService(0x${id.toString(16)}) rc=$rc")
            }
            Log.i(TAG, "server connected $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            serverBinder = null   // bound stays true: BIND_AUTO_CREATE reconnects
            Log.w(TAG, "server disconnected $name")
        }

        override fun onBindingDied(name: ComponentName) {
            Log.w(TAG, "binding died $name")
            unbind()
            onConnectionLost()
        }
    }

    /** Donor retry/backoff: 0/1/3/7 s between attempts, up to 15 s connect wait each.
     *  Suspends only on plain delay(), so cancellation aborts promptly; the caller
     *  must unbind() on cancellation (HudController does). */
    suspend fun bind(): Boolean {
        val backoffMs = longArrayOf(0, 1000, 3000, 7000)
        for (attempt in backoffMs.indices) {
            delay(backoffMs[attempt])
            unbind()
            val intent = Intent(SERVER_ACTION).setPackage(PKG)
            bound = runCatching { ctx.bindService(intent, serverConn, Context.BIND_AUTO_CREATE) }
                .getOrDefault(false)
            Log.i(TAG, "bind server rc=$bound attempt=$attempt")
            if (!bound) continue
            var tries = 0
            while (serverBinder == null && tries++ < 75) delay(200)
            if (serverBinder != null) return true
            Log.w(TAG, "server bind timeout attempt=$attempt")
        }
        Log.e(TAG, "server bind failed after ${backoffMs.size} attempts")
        return false
    }

    fun unbind() {
        if (bound) runCatching { ctx.unbindService(serverConn) }
        bound = false
        serverBinder = null
    }

    fun startService(serviceId: Long): Int {
        activeServiceId = serviceId
        val binder = serverBinder ?: return -1
        val rc = transact(binder, TX_START_SERVICE) { it.writeLong(serviceId) }
        Log.i(TAG, "startService(0x${serviceId.toString(16)}) rc=$rc")
        return rc
    }

    fun stopService(serviceId: Long): Int {
        activeServiceId = null
        val binder = serverBinder ?: return -1
        val rc = transact(binder, TX_STOP_SERVICE) { it.writeLong(serviceId) }
        Log.i(TAG, "stopService(0x${serviceId.toString(16)}) rc=$rc")
        return rc
    }

    override fun fireEvent(topic: Long, payload: ByteArray): Int {
        val binder = serverBinder ?: return -1
        return transact(binder, TX_FIRE_EVENT) {
            it.writeInt(1)
            it.writeLong(topic)
            it.writeLong(0L)
            it.writeInt(payload.size)
            it.writeByteArray(payload)
        }
    }

    private fun registerCallback(binder: IBinder): Int {
        val rc = transact(binder, TX_REGISTER_CB) { it.writeStrongBinder(callback) }
        Log.i(TAG, "registerCallback rc=$rc")
        return rc
    }

    private inline fun transact(binder: IBinder, code: Int, write: (Parcel) -> Unit): Int {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(DESC)
            write(data)
            binder.transact(code, data, reply, 0)
            reply.readException()
            reply.readInt()
        } catch (t: Throwable) {
            if (t is DeadObjectException) serverBinder = null  // reconnect will restore
            Log.e(TAG, "transact($code) failed: ${t.message}")
            -2
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
