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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientDisplaysTest {

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

    private fun clientWith(binder: IBinder?): HelperClientImpl = object : HelperClientImpl() {
        override fun resolveBinder(): IBinder? = binder
    }

    /** Fake that marshals a 2-display list per the TX_LIST_DISPLAYS reply layout. */
    private fun displaysFake(): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(0)   // status
            reply.writeInt(2)     // count
            reply.writeInt(0); reply.writeString("Built-in Screen")
            reply.writeInt(1920); reply.writeInt(1200); reply.writeInt(160)
            reply.writeInt(2); reply.writeString("cluster")
            reply.writeInt(1920); reply.writeInt(720); reply.writeInt(160)
            reply.setDataPosition(0)
            return true
        }
    }

    private fun statusErrFake(): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(-1); reply.writeInt(0)
            reply.setDataPosition(0)
            return true
        }
    }

    private fun featureFake(status: Int, value: Int): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            reply!!.writeInt(status); reply.writeInt(value)
            reply.setDataPosition(0)
            return true
        }
    }

    @Test
    fun `listDisplays parses the marshalled list`() = runBlocking {
        val result = clientWith(displaysFake()).listDisplays()
        assertEquals(2, result!!.size)
        assertEquals(DisplayInfo(0, "Built-in Screen", 1920, 1200, 160), result[0])
        assertEquals(DisplayInfo(2, "cluster", 1920, 720, 160), result[1])
    }

    @Test
    fun `listDisplays returns null on error status`() = runBlocking {
        assertNull(clientWith(statusErrFake()).listDisplays())
    }

    @Test
    fun `getInstrumentFeature returns value when status is 0`() = runBlocking {
        assertEquals(3, clientWith(featureFake(status = 0, value = 3)).getInstrumentFeature(1276313665))
    }

    @Test
    fun `getInstrumentFeature returns null when status is negative`() = runBlocking {
        assertNull(clientWith(featureFake(status = -1, value = 0)).getInstrumentFeature(1276313665))
    }

    @Test
    fun `getInstrumentFeature returns real zero as zero`() = runBlocking {
        assertEquals(0, clientWith(featureFake(status = 0, value = 0)).getInstrumentFeature(1276313665))
    }
}
