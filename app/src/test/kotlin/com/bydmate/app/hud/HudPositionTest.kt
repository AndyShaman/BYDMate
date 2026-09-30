package com.bydmate.app.hud

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** The position the LAUNCHER_MAP_CN family carries: a fix only with location access already granted. */
@RunWith(RobolectricTestRunner::class)
class HudPositionTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun fix(provider: String, lat: Double, lon: Double) =
        shadowOf(lm).setLastKnownLocation(provider, Location(provider).apply { latitude = lat; longitude = lon })

    @Test fun `without location access the default point goes out even when a fix exists`() {
        fix(LocationManager.GPS_PROVIDER, 53.9, 27.56)
        assertEquals(HudLauncherMapCnFrames.Position.DEFAULT, HudPosition.lastKnown(context))
    }

    @Test fun `with access the GPS fix wins over the network one`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        fix(LocationManager.GPS_PROVIDER, 53.9, 27.56)
        fix(LocationManager.NETWORK_PROVIDER, 52.1, 23.7)
        assertEquals(HudLauncherMapCnFrames.Position(53.9, 27.56), HudPosition.lastKnown(context))
    }

    @Test fun `with coarse access and no GPS fix the network fix goes out`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        fix(LocationManager.NETWORK_PROVIDER, 52.1, 23.7)
        assertEquals(HudLauncherMapCnFrames.Position(52.1, 23.7), HudPosition.lastKnown(context))
    }

    @Test fun `with access and no fix at all the default point goes out`() {
        shadowOf(context).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        assertEquals(HudLauncherMapCnFrames.Position.DEFAULT, HudPosition.lastKnown(context))
    }
}
