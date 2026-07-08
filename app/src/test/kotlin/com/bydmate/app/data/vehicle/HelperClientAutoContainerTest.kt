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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientAutoContainerTest {

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

    /** Records the transaction code + the single `info` int, replies with [status]. */
    private class RecordingBinder(private val status: Int) : FakeIBinder() {
        var seenCode = -1
        var seenInfo = Int.MIN_VALUE
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            seenCode = code
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            seenInfo = data.readInt()
            reply!!.writeInt(status); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    private fun clientWith(binder: IBinder) =
        object : HelperClientImpl() { override fun resolveBinder(): IBinder = binder }

    @Test fun `enableClusterFullscreen sends info 16 and returns true on status 0`() = runBlocking {
        val fake = RecordingBinder(status = 0)
        val ok = clientWith(fake).enableClusterFullscreen()
        assertTrue(ok)
        assertEquals(HelperBinderProtocol.TX_AUTO_CONTAINER_SEND_INFO, fake.seenCode)
        assertEquals(16, fake.seenInfo)
    }

    @Test fun `stopClusterProjection sends info 18`() = runBlocking {
        val fake = RecordingBinder(status = 0)
        val ok = clientWith(fake).stopClusterProjection()
        assertTrue(ok)
        assertEquals(HelperBinderProtocol.TX_AUTO_CONTAINER_SEND_INFO, fake.seenCode)
        assertEquals(18, fake.seenInfo)
    }

    @Test fun `refreshNativeClusterStream sends info 0`() = runBlocking {
        val fake = RecordingBinder(status = 0)
        val ok = clientWith(fake).refreshNativeClusterStream()
        assertTrue(ok)
        assertEquals(HelperBinderProtocol.TX_AUTO_CONTAINER_SEND_INFO, fake.seenCode)
        assertEquals(0, fake.seenInfo)
    }

    @Test fun `returns false when daemon replies error status`() = runBlocking {
        val fake = RecordingBinder(status = -1)
        assertFalse(clientWith(fake).enableClusterFullscreen())
    }
}
