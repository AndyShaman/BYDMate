package com.bydmate.app.service

import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

/**
 * #361: DiLink 3.0/4.0 switch the GPS provider off with the ignition (see
 * TrackingServiceLocationListenerTest). A service created during an ignition-off wake used to skip
 * requestLocationUpdates because isProviderEnabled() was false at that instant, and nothing ever
 * registered again: the process lives through the head unit's sleep, so the next drive got no fix
 * at all and its trip had no map. The listener must be registered whatever the provider state;
 * Android delivers fixes once the provider comes back on.
 */
class TrackingServiceGpsRegistrationTest {

    private val lm = mockk<LocationManager>(relaxed = true)
    private val listener = mockk<LocationListener>(relaxed = true)
    private val looper = mockk<Looper>(relaxed = true)

    @Test
    fun `registers GPS updates while the provider is off`() {
        every { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns false

        registerGpsUpdates(lm, listener, looper)

        verify(exactly = 1) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 8.0f, listener, looper)
        }
    }

    @Test
    fun `registers GPS updates while the provider is on`() {
        every { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) } returns true

        registerGpsUpdates(lm, listener, looper)

        verify(exactly = 1) {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 8.0f, listener, looper)
        }
    }

    @Test
    fun `a head unit without a GPS provider does not crash the service`() {
        every { lm.isProviderEnabled(any()) } returns false
        every {
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 2000L, 8.0f, listener, looper)
        } throws IllegalArgumentException("provider doesn't exist: gps")

        registerGpsUpdates(lm, listener, looper) // must not throw
    }
}
