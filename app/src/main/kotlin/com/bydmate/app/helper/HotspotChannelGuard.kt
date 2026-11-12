package com.bydmate.app.helper

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.lang.reflect.Proxy

/**
 * Runs [HotspotChannelPolicy] in the daemon (shell holds NETWORK_SETTINGS + TETHER_PRIVILEGED): when the saved
 * hotspot config is 5 GHz on auto and this firmware can't start that, it is re-saved on channel 36 (name, password
 * and the rest kept), and a hotspot that was on is started again the way BYD's settings app does it
 * (ITetheringConnector.startTethering, Wi-Fi type, no entitlement exemption: asking for one returns error 14).
 * Firmware whose overlay has a 5 GHz channel list (or no ACS) is left alone and the thread exits.
 * Hidden APIs (Android 11+), so reflection.
 */
internal object HotspotChannelGuard {
    private const val CHECK_MS = 2_000L
    private const val TETHERING_WIFI = 0
    private const val SHELL_PKG = "com.android.shell"
    private const val WIFI_RESOURCES_ACTION = "com.android.server.wifi.intent.action.SERVICE_WIFI_RESOURCES_APK"

    private fun log(msg: String) { println("HOTSPOT $msg"); System.out.flush() }

    private fun service(name: String): IBinder =
        Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, name) as IBinder

    private val wifi by lazy {
        Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("wifi"))!!
    }

    /** Reads the two overlay values from the Wi-Fi resources APK (the one in the wifi APEX, as WifiContext picks it). */
    internal fun firmware(ctx: Context?): Pair<HotspotChannelPolicy.Firmware, String> {
        if (ctx == null) return HotspotChannelPolicy.Firmware.UNKNOWN to "no context"
        val pm = ctx.packageManager
        val pkg = runCatching {
            pm.queryIntentActivities(Intent(WIFI_RESOURCES_ACTION), 0x00100000 /* MATCH_SYSTEM_ONLY */)
                .map { it.activityInfo.applicationInfo }
                .firstOrNull { it.sourceDir?.startsWith("/apex/com.android.wifi") == true }?.packageName
        }.getOrNull() ?: return HotspotChannelPolicy.Firmware.UNKNOWN to "no wifi resources apk"
        val res = runCatching { pm.getResourcesForApplication(pkg) }.getOrNull()
            ?: return HotspotChannelPolicy.Firmware.UNKNOWN to "no resources for $pkg"
        val acsId = res.getIdentifier("config_wifi_softap_acs_supported", "bool", pkg)
        val listId = res.getIdentifier("config_wifiSoftap5gChannelList", "string", pkg)
        val acs = if (acsId != 0) runCatching { res.getBoolean(acsId) }.getOrNull() else null
        val list = if (listId != 0) runCatching { res.getString(listId) }.getOrNull() else null
        return HotspotChannelPolicy.classify(acs, list) to "$pkg acs=$acs list5g=\"${list ?: "?"}\""
    }

    private fun config(): Any = wifi.javaClass.getMethod("getSoftApConfiguration").invoke(wifi)!!

    private fun apState(): Int = wifi.javaClass.getMethod("getWifiApEnabledState").invoke(wifi) as Int

    private fun channels(cfg: Any): Map<Int, Int> {
        val sparse = cfg.javaClass.getMethod("getChannels").invoke(cfg)!! // SparseIntArray
        val size = sparse.javaClass.getMethod("size").invoke(sparse) as Int
        val keyAt = sparse.javaClass.getMethod("keyAt", Int::class.java)
        val valueAt = sparse.javaClass.getMethod("valueAt", Int::class.java)
        return (0 until size).associate { keyAt.invoke(sparse, it) as Int to valueAt.invoke(sparse, it) as Int }
    }

    private fun pin(cfg: Any): Boolean {
        val sc = Class.forName("android.net.wifi.SoftApConfiguration")
        val bc = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = bc.getConstructor(sc).newInstance(cfg)
        bc.getMethod("setChannel", Int::class.java, Int::class.java)
            .invoke(builder, HotspotChannelPolicy.CHANNEL, HotspotChannelPolicy.BAND_5GHZ)
        val pinned = bc.getMethod("build").invoke(builder)
        val set = wifi.javaClass.methods.first { it.name == "setSoftApConfiguration" }
        val r = if (set.parameterCount == 2) set.invoke(wifi, pinned, SHELL_PKG) else set.invoke(wifi, pinned)
        return r == true
    }

    @Suppress("SpreadOperator") // one reflective call per restart, the copy is nothing
    private fun startTethering() {
        val connector = Class.forName("android.net.ITetheringConnector\$Stub").getMethod("asInterface", IBinder::class.java)
            .invoke(null, service("tethering"))!!
        val rp = Class.forName("android.net.TetheringRequestParcel")
        val req = rp.getConstructor().newInstance()
        rp.getField("tetheringType").setInt(req, TETHERING_WIFI)
        runCatching { rp.getField("showProvisioningUi").setBoolean(req, false) }
        runCatching { rp.getField("exemptFromEntitlementCheck").setBoolean(req, false) }
        val li = Class.forName("android.net.IIntResultListener")
        val binder = object : Binder() {
            override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                runCatching { data.enforceInterface(li.name); log("start result ${data.readInt()}") }
                return true
            }
        }
        val listener = Proxy.newProxyInstance(li.classLoader, arrayOf(li)) { _, m, _ -> if (m.name == "asBinder") binder else null }
        val start = connector.javaClass.methods.first { it.name == "startTethering" }
        val args = arrayOfNulls<Any>(start.parameterCount)
        args[0] = req; args[1] = SHELL_PKG; args[start.parameterCount - 1] = listener
        start.invoke(connector, *args)
    }

    fun start(ctx: Context?) {
        Thread({
            val (firmware, detail) = runCatching { firmware(ctx) }
                .getOrElse { HotspotChannelPolicy.Firmware.UNKNOWN to it.toString() }
            log("firmware 5 GHz auto channel: $firmware ($detail)")
            if (firmware == HotspotChannelPolicy.Firmware.OK) return@Thread
            if (runCatching { Class.forName("android.net.wifi.SoftApConfiguration") }.isFailure) {
                log("no SoftApConfiguration (Android 10 or older), guard off"); return@Thread
            }
            val tracker = HotspotRestartTracker()
            var failing = ""
            while (true) {
                runCatching {
                    val cfg = config()
                    val state = apState()
                    var pinned = false
                    if (HotspotChannelPolicy.needsPin(channels(cfg), firmware, state)) {
                        pinned = pin(cfg)
                        log("5 GHz on auto channel can't start here, " +
                            if (pinned) "set channel ${HotspotChannelPolicy.CHANNEL}" else "setting channel ${HotspotChannelPolicy.CHANNEL} FAILED")
                    }
                    if (tracker.tick(SystemClock.elapsedRealtime(), state, pinned)) {
                        log("was on before the band change, starting it again")
                        startTethering()
                    }
                    failing = ""
                }.onFailure { val e = it.toString(); if (e != failing) { failing = e; log("guard: $e") } }
                Thread.sleep(CHECK_MS)
            }
        }, "bydmate-hotspot").apply { isDaemon = true }.start()
    }
}
