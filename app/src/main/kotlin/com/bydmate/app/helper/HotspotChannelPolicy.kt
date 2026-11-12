package com.bydmate.app.helper

/**
 * Hotspot on "5 GHz, automatic channel" never starts on some DiLink firmware (seen on a Sealion 07, chipset 23,
 * firmware V1.8.1, 2026-11-12): the Wi-Fi overlay turns ACS on (config_wifi_softap_acs_supported) but leaves
 * config_wifiSoftap5gChannelList empty, so hostapd gets `channels={2=0}` -> "Malformed channel value detected" ->
 * SoftApManager start failed. A fixed 5 GHz channel works (36 tested, survives reboots), and the car's own Band menu
 * writes exactly the broken {2=0} whenever 5 GHz is picked. [HotspotChannelGuard] puts channel 36 back.
 */
internal object HotspotChannelPolicy {
    const val BAND_5GHZ = 2 // SoftApConfiguration.BAND_5GHZ
    const val CHANNEL = 36
    // WifiManager.WIFI_AP_STATE_*
    const val AP_DISABLING = 10
    const val AP_DISABLED = 11
    const val AP_ENABLING = 12
    const val AP_ENABLED = 13
    const val AP_FAILED = 14

    /** What the firmware's Wi-Fi overlay says about 5 GHz auto channel. */
    enum class Firmware { BROKEN, OK, UNKNOWN }

    /**
     * [acsSupported] = config_wifi_softap_acs_supported, [channelList5g] = config_wifiSoftap5gChannelList, null when
     * the resource could not be read. Without ACS the framework picks the channel itself and never reads the list.
     */
    fun classify(acsSupported: Boolean?, channelList5g: String?): Firmware = when {
        acsSupported == false -> Firmware.OK
        channelList5g != null && channelList5g.isNotBlank() -> Firmware.OK
        acsSupported == true && channelList5g != null -> Firmware.BROKEN
        else -> Firmware.UNKNOWN
    }

    /**
     * [channels] = SoftApConfiguration.getChannels(): band -> channel, 0 = auto. Only 5 GHz alone on auto breaks.
     * Known-broken firmware is pinned at once; when the overlay could not be read, only after a start has failed.
     */
    fun needsPin(channels: Map<Int, Int>, firmware: Firmware, apState: Int): Boolean {
        if (channels != mapOf(BAND_5GHZ to 0)) return false
        return when (firmware) {
            Firmware.BROKEN -> true
            Firmware.UNKNOWN -> apState == AP_FAILED
            Firmware.OK -> false
        }
    }
}

/**
 * Whether to start the hotspot again after a pin. The Band menu stops the hotspot and starts it on the broken config
 * (it fails), so after pinning the hotspot comes back only if it was on (or failing) just before, once per pin, and
 * at most [maxStarts] times per [capWindowMs] so an app fighting over the setting can't loop it.
 */
internal class HotspotRestartTracker(
    private val wantMs: Long = 30_000L,
    private val settleMs: Long = 4_000L,
    private val maxStarts: Int = 3,
    private val capWindowMs: Long = 600_000L,
) {
    private var lastOnAt = -1L
    private var pinnedAt = -1L
    private var wanted = false
    private val starts = ArrayDeque<Long>()

    /** One poll: [apState] = WIFI_AP_STATE_*, [pinned] = this poll just pinned the channel. True = start the hotspot now. */
    fun tick(now: Long, apState: Int, pinned: Boolean): Boolean {
        val up = apState == HotspotChannelPolicy.AP_ENABLING || apState == HotspotChannelPolicy.AP_ENABLED
        val onOrFailing = up || apState == HotspotChannelPolicy.AP_FAILED
        if (pinned) {
            pinnedAt = now
            wanted = onOrFailing || (lastOnAt >= 0 && now - lastOnAt <= wantMs)
        }
        if (onOrFailing) lastOnAt = now
        if (pinnedAt < 0 || now - pinnedAt < settleMs) return false
        pinnedAt = -1L
        return wanted && !up && takeStart(now)
    }

    /** Counts a start against the cap; false when [maxStarts] already happened inside [capWindowMs]. */
    private fun takeStart(now: Long): Boolean {
        while (starts.isNotEmpty() && now - starts.first() >= capWindowMs) starts.removeFirst()
        if (starts.size >= maxStarts) return false
        starts.addLast(now)
        return true
    }
}
