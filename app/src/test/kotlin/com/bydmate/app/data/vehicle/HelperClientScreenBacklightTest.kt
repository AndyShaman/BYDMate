package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TX_SET_SCREEN_BACKLIGHT (#275): the request the daemon reads and the reply the app tells apart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientScreenBacklightTest {

    private abstract class FakeIBinder : IBinder {
        override fun isBinderAlive(): Boolean = true
        override fun pingBinder(): Boolean = true
        override fun getInterfaceDescriptor(): String = HelperBinderProtocol.DESCRIPTOR
        override fun queryLocalInterface(descriptor: String): IInterface? = null
        @Suppress("OVERRIDE_DEPRECATION")
        override fun dump(fd: java.io.FileDescriptor, args: Array<String>?) = Unit
        override fun dumpAsync(fd: java.io.FileDescriptor, args: Array<String>?) = Unit
        override fun linkToDeath(recipient: IBinder.DeathRecipient, flags: Int) = Unit
        override fun unlinkToDeath(recipient: IBinder.DeathRecipient, flags: Int): Boolean = true
    }

    private fun clientWith(binder: IBinder): HelperClientImpl =
        object : HelperClientImpl() { override fun resolveBinder(): IBinder = binder }

    @Test fun `setScreenBacklight marshals on and off and reads outcome, status and detail`() = runBlocking {
        val seen = mutableListOf<Pair<Int, Int>>()
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                seen += code to data.readInt()
                reply!!.writeInt(HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED); reply.writeInt(1)
                reply.writeString("turnBacklightOff reads=1,1,1,1,1")
                reply.setDataPosition(0)
                return true
            }
        }
        val client = clientWith(fake)
        val off = client.setScreenBacklight(false)
        client.setScreenBacklight(true)
        assertEquals(
            listOf(HelperBinderProtocol.TX_SET_SCREEN_BACKLIGHT to 0, HelperBinderProtocol.TX_SET_SCREEN_BACKLIGHT to 1),
            seen,
        )
        assertEquals(
            ScreenBacklightReply(HelperBinderProtocol.SCREEN_BACKLIGHT_NOT_FOLLOWED, 1, "turnBacklightOff reads=1,1,1,1,1"),
            off,
        )
    }

    @Test fun `outdated daemon without the transaction answers null`() = runBlocking {
        // An old daemon falls through to Binder.onTransact, which returns false for unknown codes.
        val stale = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        assertNull(clientWith(stale).setScreenBacklight(false))
    }

    @Test fun `the transaction code is appended after the last one`() {
        assertEquals(IBinder.FIRST_CALL_TRANSACTION + 58, HelperBinderProtocol.TX_SET_SCREEN_BACKLIGHT)
    }
}
