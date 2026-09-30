package com.bydmate.app.hud

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat

/**
 * The car's position for the LAUNCHER_MAP_CN family (HUD check step 4, way 3): the last known fix,
 * GPS first, then network, but only when location access is already granted; otherwise OpenBYD's
 * own starting value [HudLauncherMapCnFrames.Position.DEFAULT]. Never asks for access, and the
 * coordinates never go to a log line, a trace event or the dump.
 */
internal object HudPosition {

    fun lastKnown(context: Context): HudLauncherMapCnFrames.Position {
        val granted = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
        if (!granted) return HudLauncherMapCnFrames.Position.DEFAULT
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            ?: return HudLauncherMapCnFrames.Position.DEFAULT
        for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
            @Suppress("MissingPermission")
            val fix = runCatching { lm.getLastKnownLocation(provider) }.getOrNull() ?: continue
            return HudLauncherMapCnFrames.Position(lat = fix.latitude, lon = fix.longitude)
        }
        return HudLauncherMapCnFrames.Position.DEFAULT
    }
}
