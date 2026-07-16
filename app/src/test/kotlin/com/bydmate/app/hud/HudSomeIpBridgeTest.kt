package com.bydmate.app.hud

import android.content.Context
import android.content.pm.PackageInfo
import android.os.IBinder
import android.os.Parcel
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class HudSomeIpBridgeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun installSomeIpPackage() {
        shadowOf(context.packageManager).installPackage(
            PackageInfo().apply { packageName = "com.ts.car.someip.service" })
    }

    @Test fun `probe negative on clean device`() {
        assertFalse(HudSomeIpBridge.isServicePresent(context.packageManager))
    }

    @Test fun `probe positive when gateway package installed`() {
        installSomeIpPackage()
        assertTrue(HudSomeIpBridge.isServicePresent(context.packageManager))
    }

    @Test fun `calls fail soft when unbound`() {
        val bridge = HudSomeIpBridge(context)
        assertEquals(-1, bridge.fireEvent(HudSomeIpBridge.TOPIC_NAVI, byteArrayOf(1)))
        assertEquals(-1, bridge.startService(HudSomeIpBridge.SERVICE_ID_NAVI))
        assertEquals(-1, bridge.stopService(HudSomeIpBridge.SERVICE_ID_NAVI))
        bridge.unbind()   // must not throw
    }

    @Test fun `bind gives up when gateway never connects`() = runTest {
        val bridge = HudSomeIpBridge(context)
        assertFalse(bridge.bind())   // virtual time: 4 attempts x 15 s pass instantly
        bridge.unbind()
    }

    @Test fun `callback answers interface transaction with descriptor`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertTrue(bridge.callback.transact(IBinder.INTERFACE_TRANSACTION, data, reply, 0))
            reply.setDataPosition(0)
            assertEquals("ts.car.someip.sdk.ISomeIpCallback", reply.readString())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test fun `callback event reply carries exception header and status int`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken("ts.car.someip.sdk.ISomeIpCallback")
            data.writeInt(0)   // "no event" flag, mirrors the gateway ping shape
            assertTrue(bridge.callback.transact(IBinder.FIRST_CALL_TRANSACTION, data, reply, 0))
            reply.setDataPosition(0)
            reply.readException()
            assertEquals(4, reply.dataAvail())   // the AIDL int return - absent before the fix
            assertEquals(0, reply.readInt())
        } finally {
            data.recycle(); reply.recycle()
        }
    }

    @Test fun `callback rejects unknown transaction codes`() {
        val bridge = HudSomeIpBridge(context)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            assertFalse(bridge.callback.transact(IBinder.FIRST_CALL_TRANSACTION + 41, data, reply, 0))
        } finally {
            data.recycle(); reply.recycle()
        }
    }
}
