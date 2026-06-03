package com.bydmate.app.data.vehicle

/**
 * Sunroof control modes mapped to WriteAllowlist action names (sunroof_<name>).
 * Values validated on Leopard 3 2026-05-28 via HelperDaemon probe.
 * Source: BYDAutoFeatureIds.SUNROOF_* + competitor-v80 fid config.
 */
enum class SunroofMode(val value: Int) {
    OPEN(1),
    CLOSE(2),
    TILT(3),
    STOP(4),
    UPDIP(5),
    COMFORT(6),
}
