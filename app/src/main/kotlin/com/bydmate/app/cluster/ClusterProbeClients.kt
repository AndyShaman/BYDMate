package com.bydmate.app.cluster

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import com.byd.spi.ipc.cursor.BinderCursor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The two firmware channels the UI7 cluster probe talks to, both reachable from app uid without the
 * helper daemon: the cluster card menu (`MenuService`, raw binder over a bound service) and the DiCar
 * instrument SPI (`ICarInstrumentService`, raw binder pulled out of a content provider).
 *
 * Everything here is fail-soft and null-on-failure: a probe never throws at the runner, it reports.
 */
private const val TAG = "ClusterProbe"

// ---------------------------------------------------------------------------------------------
// MenuService — which cards the cluster's left/right menus offer and which are selected.
// ---------------------------------------------------------------------------------------------

interface ClusterMenuClient {
    /** [side]: 0 both menus, 1 left, 2 right. Null = no channel; "" = firmware says unsupported. */
    suspend fun getClusterMenu(side: Int): String?

    /**
     * Selection per side. NEVER null arrays: the service does `strArr.length` on both.
     * [MENU_UNCHANGED] alone = leave that side alone, [MENU_ZERO_SELECTED] alone = select nothing.
     * Returns the service's rc, or null when the channel is unavailable.
     */
    suspend fun setClusterMenu(left: Array<String>, right: Array<String>): Int?
}

/** "Do not touch this side", as the stock ClusterMenuApiManager spells it. */
const val MENU_UNCHANGED = "unchanged"

/** "Nothing selected on this side". */
const val MENU_ZERO_SELECTED = "zeroselected"

/**
 * Card ids of the right menu that the firmware currently has selected, or null when the JSON is not
 * the shape we expect (an empty list is a valid answer and means "nothing selected").
 * Accepts both the wrapped answer of `getClusterMenu(0)` and a bare `right_menu` object.
 */
fun parseSelectedRightMenu(json: String?): List<String>? {
    if (json.isNullOrBlank()) return null
    return runCatching {
        val root = JSONObject(json)
        val menus = root.optJSONObject("cluster_menu_info") ?: root
        val right = menus.optJSONObject("right_menu") ?: return null
        val selected = right.optJSONObject("selected") ?: return null
        val all = selected.optJSONArray("all_selected") ?: return null
        (0 until all.length()).mapNotNull { all.optString(it).takeIf { id -> id.isNotBlank() } }
    }.getOrNull()
}

class MenuServiceClient(private val context: Context) : ClusterMenuClient {

    override suspend fun getClusterMenu(side: Int): String? = withBinder { binder ->
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(MENU_DESCRIPTOR)
            data.writeInt(side)
            if (!binder.transact(TX_GET_CLUSTER_MENU, data, reply, 0)) return@withBinder null
            reply.readException()
            reply.readString()
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override suspend fun setClusterMenu(left: Array<String>, right: Array<String>): Int? =
        withBinder { binder ->
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(MENU_DESCRIPTOR)
                data.writeStringArray(left)
                data.writeStringArray(right)
                if (!binder.transact(TX_SET_CLUSTER_MENU, data, reply, 0)) return@withBinder null
                reply.readException()
                reply.readInt()
            } finally {
                data.recycle()
                reply.recycle()
            }
        }

    /** Binds for one call and unbinds after it: the probe has no session to keep alive. */
    private suspend fun <T> withBinder(block: (IBinder) -> T?): T? {
        val ready = CompletableDeferred<IBinder?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                ready.complete(service)
            }

            override fun onServiceDisconnected(name: ComponentName?) = Unit

            override fun onNullBinding(name: ComponentName?) {
                ready.complete(null)
            }
        }
        val intent = Intent().setComponent(ComponentName(MENU_PACKAGE, MENU_SERVICE))
        val bound = runCatching { context.bindService(intent, connection, Context.BIND_AUTO_CREATE) }
            .onFailure { Log.w(TAG, "menu bindService threw", it) }
            .getOrDefault(false)
        if (!bound) {
            Log.w(TAG, "menu service not bound: $MENU_PACKAGE/$MENU_SERVICE")
            runCatching { context.unbindService(connection) }
            return null
        }
        return try {
            val binder = withTimeoutOrNull(BIND_TIMEOUT_MS) { ready.await() }
            if (binder == null) {
                Log.w(TAG, "menu service did not connect within $BIND_TIMEOUT_MS ms")
                null
            } else {
                runCatching { block(binder) }
                    .onFailure { Log.w(TAG, "menu transact threw", it) }
                    .getOrNull()
            }
        } finally {
            runCatching { context.unbindService(connection) }
        }
    }

    private companion object {
        const val MENU_PACKAGE = "com.example.amapservice"
        const val MENU_SERVICE = "com.byd.cluster.menu.service.MenuService"
        const val MENU_DESCRIPTOR = "com.byd.cluster.menu.sdk.IClusterMenu"
        const val TX_GET_CLUSTER_MENU = 1
        const val TX_SET_CLUSTER_MENU = 2
        const val BIND_TIMEOUT_MS = 3000L
    }
}

// ---------------------------------------------------------------------------------------------
// DiCar SPI — ICarInstrumentService, expected to answer 20004 (no BYDAUTO_INSTRUMENT_* to app uid).
// ---------------------------------------------------------------------------------------------

/**
 * One decoded answer of the DiCar SPI. [code] 0 = success, 20004 = the server's permission refusal;
 * [value] is Int / Double / Boolean for the getters, always null for the setters (Status).
 */
data class DiCarResult(val code: Int, val message: String?, val value: Any?) {
    val ok: Boolean get() = code == CODE_OK

    override fun toString(): String = if (ok) "${value ?: "ok"}" else "err$code"

    companion object {
        const val CODE_OK = 0
    }
}

interface DiCarInstrumentClient {
    /** Getter returning `Result<T>`. Null = no channel, transact refused, or a null payload. */
    suspend fun result(tx: Int, arg: Int? = null): DiCarResult?

    /** Setter returning `Status`. Null as above. */
    suspend fun status(tx: Int, arg: Int): DiCarResult?
}

/** `Result<T>` off the wire; null when the server sent a null payload. Throws what the remote threw. */
fun decodeResult(reply: Parcel): DiCarResult? {
    reply.readException()
    if (reply.readInt() == 0) return null
    val code = reply.readInt()
    val message = reply.readString()
    val value = when (reply.readString()) {
        "java.lang.Integer" -> reply.readInt()
        "java.lang.Double" -> reply.readDouble()
        "java.lang.Boolean" -> reply.readInt() != 0
        else -> null
    }
    return DiCarResult(code, message, value)
}

/** `Status` off the wire (code + message, no payload). */
fun decodeStatus(reply: Parcel): DiCarResult? {
    reply.readException()
    if (reply.readInt() == 0) return null
    return DiCarResult(reply.readInt(), reply.readString(), null)
}

class DiCarProviderClient(private val context: Context) : DiCarInstrumentClient {

    override suspend fun result(tx: Int, arg: Int?): DiCarResult? = transact(tx, arg, ::decodeResult)

    override suspend fun status(tx: Int, arg: Int): DiCarResult? = transact(tx, arg, ::decodeStatus)

    private fun transact(tx: Int, arg: Int?, decode: (Parcel) -> DiCarResult?): DiCarResult? {
        val binder = binder() ?: return null
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(INSTRUMENT_DESCRIPTOR)
            arg?.let { data.writeInt(it) }
            if (!binder.transact(tx, data, reply, 0)) null else decode(reply)
        } catch (e: Throwable) {
            Log.w(TAG, "dicar transact tx=$tx threw", e)
            null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /** The instrument service binder, handed over in the provider cursor's extras. */
    private fun binder(): IBinder? = runCatching {
        context.contentResolver
            .query(Uri.parse(SYNC_BINDER_URI), null, null, arrayOf(INSTRUMENT_SERVICE), null)
            ?.use { cursor ->
                val extras = cursor.extras ?: return@use null
                extras.classLoader = BinderCursor.BinderParcelable::class.java.classLoader
                @Suppress("DEPRECATION")
                (extras.getParcelable<Parcelable>(EXTRA_BINDER) as? BinderCursor.BinderParcelable)
                    ?.binder
            }
    }.onFailure { Log.w(TAG, "dicar binder lookup threw", it) }.getOrNull()

    companion object {
        private const val SYNC_BINDER_URI =
            "content://com.byd.car.server.provider.CarServiceProvider/sync_binder"
        private const val INSTRUMENT_SERVICE = "com.byd.car.feature.instrument.ICarInstrumentService"
        private const val INSTRUMENT_DESCRIPTOR = INSTRUMENT_SERVICE
        private const val EXTRA_BINDER = "binder"

        // ICarInstrumentService transaction codes (decompiled DiCarServer 3.0.0-beta.14).
        const val TX_GET_INSTRUMENT_CONFIG = 1
        const val TX_GET_MENU_VERSION = 2
        const val TX_GET_INSTRUMENT_THEME = 5
        const val TX_SET_INSTRUMENT_THEME = 6
        const val TX_GET_MENU_TYPE = 7
        const val TX_IS_MENU_VISIBLE = 8
        const val TX_SET_MENU_VISIBLE = 9
        const val TX_GET_NAVIGATION_TYPE = 12
        const val TX_SET_NAVIGATION_TYPE = 13
    }
}
