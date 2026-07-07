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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientGlobalSettingTest {

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

    @Test fun `putGlobalSetting marshals key and value and returns true on status 0`() = runBlocking {
        var seenCode = -1
        var seenKey: String? = null
        var seenValue = -1
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                seenCode = code
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                seenKey = data.readString()
                seenValue = data.readInt()
                reply!!.writeInt(0); reply.writeInt(0)
                reply.setDataPosition(0)
                return true
            }
        }
        val client = object : HelperClientImpl() { override fun resolveBinder(): IBinder = fake }

        val ok = client.putGlobalSetting("sentrymode_enabled_switch", 1)
        assertTrue(ok)
        assertEquals(HelperBinderProtocol.TX_PUT_GLOBAL_SETTING, seenCode)
        assertEquals("sentrymode_enabled_switch", seenKey)
        assertEquals(1, seenValue)
    }
}
