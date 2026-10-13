package com.bydmate.app.data.weather

import android.Manifest
import android.app.Application
import android.content.Context
import android.location.Location
import android.location.LocationManager
import androidx.test.core.app.ApplicationProvider
import com.bydmate.app.service.TrackingService
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], application = Application::class)
class StockWeatherPermissionTest {
    @Test fun `permission granted after service startup registers the existing GPS listener`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val service = Robolectric.buildService(TrackingService::class.java).get()
        val start = TrackingService::class.java.getDeclaredMethod("startLocationUpdates").apply { isAccessible = true }
        start.invoke(service)
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        val seed = Location("gps").apply { latitude = 12.34; longitude = 56.78; time = System.currentTimeMillis() }
        @Suppress("DEPRECATION")
        shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, seed)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val instance = TrackingService::class.java.getDeclaredField("instance").apply { isAccessible = true }
        instance.set(null, service)
        try {
            TrackingService.refreshLocationPermission(app)
            val fix = TrackingService.lastLocation.value!!
            assertEquals(seed.latitude, fix.latitude, 0.0)
            assertEquals(seed.longitude, fix.longitude, 0.0)
            assertEquals(seed.time, fix.time)
        } finally {
            lm.removeUpdates(service)
            instance.set(null, null)
        }
    }
}
