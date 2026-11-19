package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Parcel marshalling needs Robolectric — same pattern as HelperClientAppHiddenTest.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientPressBackTest {

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

    private fun clientAnswering(status: Int, onCode: (Int) -> Unit = {}): HelperClientImpl {
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                onCode(code)
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                assertEquals("no arguments", 0, data.dataAvail())
                reply!!.writeInt(status)
                reply.writeInt(0)
                reply.setDataPosition(0)
                return true
            }
        }
        return object : HelperClientImpl() { override fun resolveBinder(): IBinder = fake }
    }

    @Test fun `pressBack sends TX_PRESS_BACK with no arguments and reads status 0 as ok`() = runBlocking {
        var seenCode = -1
        assertTrue(clientAnswering(0) { seenCode = it }.pressBack())
        assertEquals(HelperBinderProtocol.TX_PRESS_BACK, seenCode)
        assertFalse(clientAnswering(-1).pressBack())
    }

    @Test fun `an old daemon (transact false) is a plain false`() = runBlocking {
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        val client = object : HelperClientImpl() { override fun resolveBinder(): IBinder = fake }
        assertFalse(client.pressBack())
    }
}
