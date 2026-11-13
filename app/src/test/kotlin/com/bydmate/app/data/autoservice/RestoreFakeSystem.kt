package com.bydmate.app.data.autoservice

/** Minimal [AdbRestorePreferences] for the restore tests; every enable-write is counted. */
class RestoreFakePrefs(private var enabled: Boolean = true) : AdbRestorePreferences {
    var network: String? = null
    var writeAt: Long = 0L
    var writes = 0

    override fun isEnabled(): Boolean = enabled
    override fun setEnabled(enabled: Boolean) { this.enabled = enabled }
    override fun lastWriteNetwork(): String? = network
    override fun lastWriteAtMs(): Long = writeAt
    override fun recordWrite(network: String?, atMs: Long) {
        this.network = network
        this.writeAt = atMs
        writes++
    }
}

/**
 * Head-unit fake that logs every call into [calls], in order, so a test can assert what happened
 * before what. Uses only members the interface has today; the tests for the reworked restore
 * subclass it ([RestoreFakeSystemV2]) to add the new members.
 */
open class RestoreFakeSystem : AdbRestoreSystem {
    val calls = mutableListOf<String>()
    var permission = true
    var wifi: String? = "02:00:00:aa:bb:cc"
    var adbWifiEnabled = 0
    var a11yBound = false
    var a11yDaemonResult = true
    var now = 1_000_000L

    /** Does the classic port answer right now? Default: only once a tcpip request went out. */
    var classicAlive: () -> Boolean = { calls.any { it.startsWith("tcpip") } }

    override fun hasWriteSecureSettings(): Boolean = permission
    override suspend fun selfGrantWriteSecureSettings(): Boolean {
        calls += "self-grant"
        permission = true
        return true
    }
    override fun wifiNetwork(): String? = wifi
    override fun readAdbWifiEnabled(): Int = adbWifiEnabled
    override fun writeAdbWifiEnabled(value: Int): Boolean {
        calls += "adb_wifi_enabled=$value"
        adbWifiEnabled = value
        return true
    }
    override fun tlsPortFromProperty(): Int? = null
    override suspend fun discoverTlsPort(timeoutMs: Long): Int? {
        calls += "discover"
        return 39943
    }
    override suspend fun restartTcpip(port: Int): String? {
        calls += "tcpip"
        return "restarting in TCP mode port: 5555"
    }
    override suspend fun classicConnect(): Boolean {
        val alive = classicAlive()
        calls += "classic=$alive"
        return alive
    }
    override suspend fun ensureHelperRunning(): Boolean {
        calls += "helper"
        return true
    }
    override fun isAccessibilityServiceBound(): Boolean = a11yBound
    override suspend fun enableAccessibilityService(): Boolean {
        calls += "a11y-daemon"
        if (a11yDaemonResult) a11yBound = true
        return a11yDaemonResult
    }
    override suspend fun sleep(ms: Long) { now += ms }
    override fun nowMs(): Long = now
    override fun sdkInt(): Int = 30

    /** Settings writes of any kind, in call order. */
    fun writesOfAnyKind(): List<String> =
        calls.filter { it.startsWith("adb_wifi_enabled=") || it.startsWith("adb_enabled=") || it.startsWith("prop ") }
}
