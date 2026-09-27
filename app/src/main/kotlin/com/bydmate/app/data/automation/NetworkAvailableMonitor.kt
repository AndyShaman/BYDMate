package com.bydmate.app.data.automation

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the most recent moment a default network can REALLY reach the public
 * internet. Android's `NET_CAPABILITY_VALIDATED` is a necessary but not
 * sufficient signal on DiLink: BYD ships several APNs (e.g. ims/system APN1)
 * that are flagged INTERNET+VALIDATED so the system can keep VoLTE / map
 * updates / telematics alive even when the user has toggled "Cellular Data"
 * off — but user-space apps can't actually reach the wider internet through
 * those APNs. Listening to VALIDATED alone fired the trigger in those cases.
 *
 * To distinguish a real-world reachable network we run a lightweight TCP
 * probe to a list of well-known DNS servers (Yandex / Quad9, port 53)
 * each time Android signals a fresh VALIDATED edge. The probe socket is
 * bound to the candidate network so traffic cannot silently fall back to a
 * different default. Only after the probe succeeds do we publish
 * [_lastAvailableAt] for the AutomationEngine.
 */
@Singleton
class NetworkAvailableMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    @Volatile private var _lastAvailableAt: Long = 0L
    // Tracks which network is currently validated. Prevents repeated capabilities
    // callbacks for the SAME network from re-running the probe — without this,
    // every signal-strength tick on a stable WiFi would re-arm the trigger.
    @Volatile private var validatedNetworkId: Long = -1L
    // True while an async TCP probe is in flight. AutomationEngine consults this
    // before seeding the per-rule watermark on first observation: seeding 0
    // mid-probe and then handing the about-to-publish edge as "new" would
    // re-introduce the false-fire it tries to prevent. One extra poll tick of
    // latency is a fair price.
    @Volatile private var _probePending: Boolean = false

    val lastAvailableAt: Long get() = _lastAvailableAt
    val probePending: Boolean get() = _probePending

    private val _edges = MutableStateFlow(0L)
    /** [lastAvailableAt] as a flow, for work that waits for the internet (the Telegram report outbox). */
    val edges: StateFlow<Long> = _edges

    private val cm: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var registered = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    // Single in-flight probe — newer VALIDATED edges cancel any earlier
    // probe still running, avoiding stacked socket attempts when the
    // system spams capability changes during a network transition.
    @Volatile private var probeJob: Job? = null

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val nid = network.networkHandle
            if (hasInternet && validated) {
                if (validatedNetworkId != nid) {
                    validatedNetworkId = nid
                    Log.i(TAG, "candidate validated network (nid=$nid), probing reachability")
                    probeReachability(network, nid)
                }
            } else if (validatedNetworkId == nid) {
                validatedNetworkId = -1L
                Log.i(TAG, "validation lost on tracked network (nid=$nid)")
            }
        }

        override fun onLost(network: Network) {
            if (validatedNetworkId == network.networkHandle) {
                validatedNetworkId = -1L
                Log.i(TAG, "tracked network lost (nid=${network.networkHandle})")
            }
        }
    }

    private fun probeReachability(network: Network, nid: Long) {
        probeJob?.cancel()
        _probePending = true
        probeJob = scope.launch {
            try {
                val reached = withContext(Dispatchers.IO) { tryProbe(network) }
                // Race guard: by the time the probe returns, the user may have
                // already switched to yet another network. Only publish the edge
                // if the network we just confirmed is still the tracked one.
                if (!reached) {
                    Log.i(TAG, "probe failed for nid=$nid — not publishing edge")
                    return@launch
                }
                if (validatedNetworkId != nid) {
                    Log.i(TAG, "probe ok for nid=$nid but tracked network changed — dropped")
                    return@launch
                }
                _lastAvailableAt = System.currentTimeMillis()
                _edges.value = _lastAvailableAt
                Log.i(TAG, "validated+reachable network edge at $_lastAvailableAt (nid=$nid)")
            } finally {
                _probePending = false
            }
        }
    }

    private fun tryProbe(network: Network): Boolean {
        for ((host, port) in PROBE_TARGETS) {
            val socket = Socket()
            try {
                network.bindSocket(socket)
                socket.connect(InetSocketAddress(host, port), PROBE_TIMEOUT_MS)
                Log.i(TAG, "probe ok via $host:$port")
                return true
            } catch (e: Exception) {
                Log.d(TAG, "probe to $host:$port failed: ${e.message}")
            } finally {
                runCatching { socket.close() }
            }
        }
        return false
    }

    fun start() {
        if (registered) return
        runCatching {
            // No initial-state guard. ConnectivityManager replays the current
            // VALIDATED network state to a freshly-registered callback — that
            // replay IS the "internet is available now" edge we want users to
            // be able to react to on app launch. Repeat-fires from service
            // restarts are best handled by the rule's cooldown setting.
            cm.registerDefaultNetworkCallback(callback)
            registered = true
            Log.i(TAG, "started")
        }.onFailure { Log.w(TAG, "start failed: ${it.message}") }
    }

    fun stop() {
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
        probeJob?.cancel()
        probeJob = null
        Log.i(TAG, "stopped")
    }

    private companion object {
        const val TAG = "NetworkAvailMon"
        const val PROBE_TIMEOUT_MS = 2000
        // Yandex DNS primary/secondary + Quad9 as a neutral fallback. We
        // explicitly avoid 1.1.1.1 / 8.8.8.8: BYD ships a kernel routing
        // allow-list on the system APN (rmnet_data1, table 20) that keeps
        // ~80 OEM-related IPs reachable even when the user has toggled
        // "Cellular Data" off — and Cloudflare's 1.1.1.1 is one of them.
        // Probing it produced false-positive edges where regular apps had
        // zero internet but our trigger still fired. Yandex / Quad9 DNS are
        // not on that allow-list, so a successful TCP handshake to any of
        // them really does prove user-space apps have working internet.
        val PROBE_TARGETS: List<Pair<String, Int>> = listOf(
            "77.88.8.8" to 53,
            "77.88.8.1" to 53,
            "9.9.9.9" to 53,
        )
    }
}
