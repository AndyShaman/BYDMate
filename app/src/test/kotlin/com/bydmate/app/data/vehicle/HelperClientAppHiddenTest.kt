package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Parcel marshalling needs Robolectric — same pattern as HelperClientBinderTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientAppHiddenTest {

    private abstract class FakeIBinder : IBinder {
        override fun isBinderAlive(): Boolean = true
        override fun pingBinder(): Boolean = true
        override fun getInterfaceDescriptor(): String = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) {}
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) {}
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    @Test fun `setAppHidden marshals package and hidden flag and returns true on status 0`() = runBlocking {
        var seenCode = -1
        var seenPkg: String? = null
        var seenHidden = -1
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                seenCode = code
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                seenPkg = data.readString()
                seenHidden = data.readInt()
                reply!!.writeInt(0)   // status OK
                reply.writeInt(0)
                reply.setDataPosition(0)
                return true
            }
        }
        val client = object : HelperClientImpl() { override fun resolveBinder(): IBinder = fake }

        val ok = client.setAppHidden("com.byd.autovoice", hidden = true)
        assertTrue(ok)
        assertEquals(HelperBinderProtocol.TX_SET_APP_HIDDEN, seenCode)
        assertEquals("com.byd.autovoice", seenPkg)
        assertEquals(1, seenHidden)
    }
}
