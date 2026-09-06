package com.bydmate.app.service

import android.os.Bundle
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Regression guard for the Android 10 crash `AbstractMethodError: LocationListener.onProviderDisabled`.
 * compileSdk 34 declares onProviderEnabled/onProviderDisabled/onStatusChanged as default interface
 * methods, so the compiler does not force overrides; on API 29 (DiLink 3.0/4.0) the same methods
 * are abstract and the framework's ListenerTransport crashes the process when the GPS provider is
 * toggled at ignition off/on. The class itself must therefore declare all three.
 */
class TrackingServiceLocationListenerTest {

    private fun assertDeclared(name: String, vararg params: Class<*>) {
        val m = runCatching { TrackingService::class.java.getDeclaredMethod(name, *params) }.getOrNull()
        assertNotNull("TrackingService must declare $name itself (abstract on API 29)", m)
    }

    @Test
    fun `declares onProviderDisabled`() = assertDeclared("onProviderDisabled", String::class.java)

    @Test
    fun `declares onProviderEnabled`() = assertDeclared("onProviderEnabled", String::class.java)

    @Test
    fun `declares onStatusChanged`() =
        assertDeclared("onStatusChanged", String::class.java, Int::class.javaPrimitiveType!!, Bundle::class.java)
}
