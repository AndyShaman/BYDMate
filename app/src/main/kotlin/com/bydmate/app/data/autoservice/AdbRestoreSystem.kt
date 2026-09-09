package com.bydmate.app.data.autoservice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log
import com.bydmate.app.data.vehicle.HelperBootstrap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * Every platform call [AdbRestoreManager] makes, behind one interface so the manager's
 * decision logic is testable on the JVM (mDNS, TLS sockets and secure settings are not).
 */
interface AdbRestoreSystem {
    /** Is WRITE_SECURE_SETTINGS granted to us? */
    fun hasWriteSecureSettings(): Boolean

    /** Identity of the currently connected Wi-Fi network (BSSID, else SSID), null when there is none. */
    fun wifiNetwork(): String?

    fun readAdbWifiEnabled(): Int

    /** Writes `adb_wifi_enabled`; false when the write itself was rejected. */
    fun writeAdbWifiEnabled(value: Int): Boolean

    /**
     * The TLS port adbd itself publishes in `service.adb.tls.port`, null when the property is
     * absent or not a number. Cheap, and lets us skip an mDNS discovery that costs up to 45 s.
     */
    fun tlsPortFromProperty(): Int?

    /** Finds the local `_adb-tls-connect._tcp` port, null on timeout. */
    suspend fun discoverTlsPort(timeoutMs: Long): Int?

    /** Runs `tcpip:5555` over TLS on [port]; returns the daemon's answer, null on failure. */
    suspend fun restartTcpip(port: Int): String?

    /** True when the classic port 5555 completes the ADB handshake. */
    suspend fun classicConnect(): Boolean

    suspend fun ensureHelperRunning(): Boolean

    suspend fun sleep(ms: Long)

    fun nowMs(): Long
}

/** Production wiring of [AdbRestoreSystem] against the head unit. */
@Singleton
class AndroidAdbRestoreSystem @Inject constructor(
    @ApplicationContext private val context: Context,
    private val keyStore: AdbKeyStore,
    private val adbOnDeviceClient: AdbOnDeviceClient,
    private val helperBootstrap: HelperBootstrap,
) : AdbRestoreSystem {

    override fun hasWriteSecureSettings(): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED

    override fun wifiNetwork(): String? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val hasWifi = cm.allNetworks.any { network ->
            cm.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        if (!hasWifi) return null
        val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return UNKNOWN_NETWORK
        @Suppress("DEPRECATION")
        val info = runCatching { wm.connectionInfo }.getOrNull() ?: return UNKNOWN_NETWORK
        @Suppress("DEPRECATION")
        if (info.networkId == -1 && info.bssid == null) return null
        @Suppress("DEPRECATION")
        return info.bssid ?: info.ssid ?: UNKNOWN_NETWORK
    }

    override fun readAdbWifiEnabled(): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, 0) }
            .getOrDefault(0)

    override fun writeAdbWifiEnabled(value: Int): Boolean =
        runCatching { Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, value) }
            .onFailure { Log.w(TAG, "write adb_wifi_enabled=$value failed: ${it.message}") }
            .getOrDefault(false)

    /**
     * `service.adb.tls.port` is readable from an app uid on AOSP, but nothing guarantees it on a
     * BYD build — a missing class, a denied read or a stale value all end the same way here, with
     * null, and the caller falls back to mDNS.
     */
    override fun tlsPortFromProperty(): Int? = runCatching {
        val getter = Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
        (getter.invoke(null, ADB_TLS_PORT_PROP) as? String)?.trim()?.toIntOrNull()
    }.onFailure { Log.d(TAG, "read $ADB_TLS_PORT_PROP failed: ${it.message}") }.getOrNull()

    override suspend fun discoverTlsPort(timeoutMs: Long): Int? =
        withTimeoutOrNull(timeoutMs) { discoverTlsPortInternal() }

    /**
     * Resolves the first `_adb-tls-connect._tcp` advertisement that belongs to THIS device.
     * Another car or phone on the same network can advertise the same service; connecting to
     * it would be both useless and wrong, so a resolved service whose address is not one of
     * our own interfaces is ignored.
     *
     * Every exit path stops the discovery exactly once ([stopped]): a restore that finds a port
     * but fails later is retried, and a discovery left registered would keep firing resolves and
     * pile up one more registration per retry until NsdManager runs out. Resolves are also run
     * one at a time — NsdManager rejects overlapping ones with FAILURE_ALREADY_ACTIVE.
     */
    private suspend fun discoverTlsPortInternal(): Int? = suspendCancellableCoroutine { cont ->
        val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        if (nsd == null) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }
        val stopped = AtomicBoolean(false)
        val resolving = AtomicBoolean(false)
        lateinit var discoveryListener: NsdManager.DiscoveryListener
        fun stopDiscovery() {
            if (stopped.compareAndSet(false, true)) {
                runCatching { nsd.stopServiceDiscovery(discoveryListener) }
                    .onFailure { Log.d(TAG, "mDNS stop failed: ${it.message}") }
            }
        }

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.d(TAG, "mDNS resolve failed: $errorCode")
                resolving.set(false)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (!isLocalAddress(serviceInfo.host)) {
                    Log.d(TAG, "mDNS: ignoring foreign host ${serviceInfo.host}")
                    // Not ours — let the next advertisement be resolved instead.
                    resolving.set(false)
                    return
                }
                stopDiscovery()
                cont.resumeIfActive(serviceInfo.port)
            }
        }
        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery start failed: $errorCode")
                // Discovery never started, so there is nothing to stop — but mark it done so a
                // later cancellation does not stop a registration that does not exist.
                stopped.set(true)
                cont.resumeIfActive(null)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!resolving.compareAndSet(false, true)) return
                @Suppress("DEPRECATION")
                runCatching { nsd.resolveService(serviceInfo, resolveListener) }
                    .onFailure {
                        Log.d(TAG, "mDNS resolve rejected: ${it.message}")
                        resolving.set(false)
                    }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
        }
        // Covers both the caller's cancellation and the 45 s timeout above.
        cont.invokeOnCancellation { stopDiscovery() }
        runCatching {
            nsd.discoverServices(TLS_CONNECT_SERVICE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            Log.w(TAG, "mDNS discovery not available: ${it.message}")
            // The call itself threw, so nothing got registered — there is nothing to stop.
            stopped.set(true)
            cont.resumeIfActive(null)
        }
    }

    private fun CancellableContinuation<Int?>.resumeIfActive(value: Int?) {
        if (isActive) resume(value)
    }

    private fun isLocalAddress(host: InetAddress?): Boolean {
        if (host == null) return false
        if (host.isLoopbackAddress) return true
        return runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence().any { iface ->
                iface.inetAddresses.asSequence().any { it.hostAddress == host.hostAddress }
            }
        }.getOrDefault(false)
    }

    override suspend fun restartTcpip(port: Int): String? = withContext(Dispatchers.IO) {
        val client = AdbProtocolClient(
            keyPair = keyStore.loadOrGenerate(),
            port = port,
            certificateProvider = { keyStore.loadOrGenerateCertificate() },
        )
        try {
            if (!client.connect()) {
                Log.w(TAG, "TLS connect to port $port refused")
                return@withContext null
            }
            Log.i(TAG, "TLS connected on port $port")
            val answer = client.openService("tcpip:$CLASSIC_PORT")
            Log.i(TAG, "tcpip answer=${answer?.take(120)} (port $port)")
            answer
        } catch (e: Exception) {
            Log.w(TAG, "tcpip over TLS failed: ${e.message}")
            null
        } finally {
            runCatching { client.disconnect() }
        }
    }

    override suspend fun classicConnect(): Boolean = adbOnDeviceClient.connect().isSuccess

    override suspend fun ensureHelperRunning(): Boolean = helperBootstrap.ensureRunning()

    override suspend fun sleep(ms: Long) = delay(ms)

    override fun nowMs(): Long = System.currentTimeMillis()

    companion object {
        const val CLASSIC_PORT = 5555
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val TLS_CONNECT_SERVICE = "_adb-tls-connect._tcp"
        private const val ADB_TLS_PORT_PROP = "service.adb.tls.port"
        // Wi-Fi is up but its identity is hidden (no location permission / masked BSSID) —
        // the cooldown then applies to "some network" instead of a specific one.
        private const val UNKNOWN_NETWORK = "wifi"
        private const val TAG = "AdbRestore"
    }
}
