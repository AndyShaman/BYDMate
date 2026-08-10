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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Anything that can push a HUD frame; HudPushLoop depends on this, tests fake it. */
interface HudEventSink {
    fun fireEvent(topic: Long, payload: ByteArray): Int
}

/** Raw-Binder client of the DiLink SOME/IP gateway (no SDK on the head unit).
 *  Transaction map (donor SomeIpBridge, field-tested): FIRST_CALL_TRANSACTION+0
 *  registerCallback, +3 startService, +4 stopService, +5 fireEvent.
 *
 *  Lifecycle: [activeConn] is the connection currently registered with the framework
 *  (must be balanced by unbindService), [serverBinder] is the LIVE connection - null
 *  between a gateway crash and the framework's automatic reconnect. On reconnect the
 *  callback is re-registered and the active service re-opened (Codex fix 3). */
class HudSomeIpBridge(
    private val ctx: Context,
    private val onConnectionLost: () -> Unit = {},
) : HudEventSink {

    companion object {
        private const val TAG = "HudSomeIpBridge"
        private const val PKG = "com.ts.car.someip.service"
        private const val SERVER_ACTION = "com.ts.car.someip.SomeIpServerService"
        private const val SERVER_CLASS = "com.ts.car.someip.service.manager.SomeIpServerService"
        private const val DESC = "ts.car.someip.sdk.ISomeIpServerInterface"
        private const val CB_DESC = "ts.car.someip.sdk.ISomeIpCallback"

        /** Connect wait per bind candidate: 75 x 200 ms = 15 s. */
        private const val CONNECT_TRIES = 75
        private const val CONNECT_POLL_MS = 200L

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

    @Volatile private var serverBinder: IBinder? = null

    /** The connection handed to bindService and not yet unbound. A candidate that lost
     *  its slot still gets its callbacks delivered - the framework has no way to cancel
     *  them - so ownership is re-checked while publishing, under [connLock]. */
    private var activeConn: CandidateConnection? = null

    /** Guards the pair (activeConn, serverBinder): checking ownership and publishing the
     *  binder must be one step, or a detach landing between the two lets a candidate that
     *  is no longer active publish its binder anyway. */
    private val connLock = Any()

    /** bind() is a multi-second state machine over one connection slot; a second caller
     *  must wait rather than unbind the registration the first one is waiting on. */
    private val bindMutex = Mutex()

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

    /** One bind candidate. Its own [binder] / [nullBinding] carry the connect wait, so a
     *  candidate that already lost its slot can only ever write to fields nobody reads
     *  again; touching the shared state additionally requires still being [activeConn]. */
    private inner class CandidateConnection : ServiceConnection {
        @Volatile var binder: IBinder? = null
        @Volatile var nullBinding = false

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            binder = service
            val owned = synchronized(connLock) {
                if (this !== activeConn) false else { serverBinder = service; true }
            }
            if (!owned) {
                Log.w(TAG, "connect from a detached candidate ignored $name")
                return
            }
            // Reconnect path: the gateway lost our registration when it died.
            registerCallback(service)
            activeServiceId?.let { id ->
                val rc = transact(service, TX_START_SERVICE) { it.writeLong(id) }
                Log.i(TAG, "re-startService(0x${id.toString(16)}) rc=$rc")
            }
            Log.i(TAG, "server connected $name")
        }

        override fun onServiceDisconnected(name: ComponentName) {
            binder = null
            val owned = synchronized(connLock) {
                // The registration stands: BIND_AUTO_CREATE reconnects.
                if (this !== activeConn) false else { serverBinder = null; true }
            }
            if (owned) Log.w(TAG, "server disconnected $name")
        }

        override fun onBindingDied(name: ComponentName) {
            if (synchronized(connLock) { this !== activeConn }) return
            Log.w(TAG, "binding died $name")
            unbind()
            onConnectionLost()
        }

        override fun onNullBinding(name: ComponentName?) {
            // The service exists but handed back no binder: waiting out the full connect
            // timeout only delays the next candidate.
            Log.w(TAG, "null binding $name")
            nullBinding = true
        }
    }

    /** Donor retry/backoff: 0/1/3/7 s between attempts, up to 15 s connect wait each.
     *  Every attempt tries the donor's service class first and the class the package
     *  manager resolves for the SOME/IP action second.
     *  Suspends only on plain delay(), so cancellation aborts promptly; the caller
     *  must unbind() on cancellation (HudController does). */
    suspend fun bind(): Boolean = bindMutex.withLock {
        val backoffMs = longArrayOf(0, 1000, 3000, 7000)
        for (attempt in backoffMs.indices) {
            delay(backoffMs[attempt])
            unbind()
            if (tryBind(explicitIntent(), "explicit", attempt)) return@withLock true
            val fallback = resolvedFallbackIntent() ?: continue
            if (tryBind(fallback, "resolved(${fallback.component?.className})", attempt)) {
                return@withLock true
            }
        }
        Log.e(TAG, "server bind failed after ${backoffMs.size} attempts")
        false
    }

    /** One bind candidate, on its own connection: connected within the timeout = true,
     *  everything else leaves the slot free for the next candidate. */
    private suspend fun tryBind(intent: Intent, path: String, attempt: Int): Boolean {
        val conn = CandidateConnection()
        // Claimed before bindService: the framework may call back from inside it. The
        // framework also registers the connection when bindService refuses, so from here
        // on it has to be balanced by unbindService.
        synchronized(connLock) { activeConn = conn }
        val rc = runCatching { ctx.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        Log.i(TAG, "bind server rc=$rc path=$path attempt=$attempt")
        if (!rc) {
            unbind()
            return false
        }
        var tries = 0
        while (conn.binder == null && !conn.nullBinding && tries++ < CONNECT_TRIES) delay(CONNECT_POLL_MS)
        // Our own binder arrived, and onServiceConnected published it while we were still
        // the active candidate - nothing else can have been published in between.
        if (conn.binder != null) return true
        Log.w(TAG, "server bind ${if (conn.nullBinding) "null binding" else "timeout"} path=$path attempt=$attempt")
        unbind()
        return false
    }

    /** Bind intent both donors use: the gateway's onUnbind does requireNonNull(getType()),
     *  so a typeless bind takes down the whole com.ts.car.someip.service process - which
     *  also hosts the factory ARHUD. setType only survives on an explicit component
     *  (intent filters without a MIME type stop matching once a type is set), hence the
     *  class name; the type itself is our package name, per byd-hud. The action stays on
     *  the intent for gateways that check it in onBind. */
    private fun explicitIntent(): Intent = typedIntent(ComponentName(PKG, SERVER_CLASS))

    private fun typedIntent(component: ComponentName): Intent = Intent(SERVER_ACTION)
        .setComponent(component)
        .setType(ctx.packageName)

    /** Firmware where the donor's class does not exist: ask the package manager which
     *  service answers the SOME/IP action and bind THAT explicitly. Resolution is the
     *  only use of a component-less intent - such an intent must never reach bindService,
     *  because a type on it would stop the filter from matching and no type at all is
     *  what kills the gateway. Null when nothing resolves or when it resolves to the
     *  class already tried. */
    private fun resolvedFallbackIntent(): Intent? {
        val info = runCatching {
            @Suppress("DEPRECATION")
            ctx.packageManager.resolveService(Intent(SERVER_ACTION).setPackage(PKG), 0)
        }.getOrNull()?.serviceInfo ?: return null
        val className = info.name ?: return null
        if (className == SERVER_CLASS) return null
        return typedIntent(ComponentName(info.packageName ?: PKG, className))
    }

    fun unbind() {
        // Detach first, release after: unbindService is a binder call and must not run
        // under the lock the callbacks take.
        val conn = synchronized(connLock) {
            val previous = activeConn
            activeConn = null
            serverBinder = null
            previous
        }
        conn?.let { runCatching { ctx.unbindService(it) } }
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
            // Reconnect will restore it - but only the binder this call actually died on
            // is dropped: a slow transact must not wipe the binder a reconnect published
            // in the meantime, because nothing would publish it again.
            if (t is DeadObjectException) {
                synchronized(connLock) { if (serverBinder === binder) serverBinder = null }
            }
            Log.e(TAG, "transact($code) failed: ${t.message}")
            -2
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}
