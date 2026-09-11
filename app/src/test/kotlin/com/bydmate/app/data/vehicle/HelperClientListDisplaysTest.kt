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

/** Wire format of TX_LIST_DISPLAYS, the daemon-side display inventory (#194). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientListDisplaysTest {

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

    private fun replyingFake(write: (Parcel) -> Unit): IBinder = object : FakeIBinder() {
        override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            assertEquals(HelperBinderProtocol.TX_LIST_DISPLAYS, code)
            data.setDataPosition(0)
            data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
            write(reply!!)
            reply.setDataPosition(0)
            return true
        }
    }

    @Test
    fun `a two-display inventory is unmarshalled field by field`() = runBlocking {
        val fake = replyingFake { reply ->
            reply.writeInt(0); reply.writeInt(2)
            reply.writeInt(0); reply.writeString("Built-in Screen")
            reply.writeInt(1920); reply.writeInt(1080); reply.writeInt(240)
            reply.writeString(""); reply.writeInt(-1); reply.writeString("")
            reply.writeInt(1); reply.writeString("fission_bg_xdjaVirtualSurface")
            reply.writeInt(1920); reply.writeInt(720); reply.writeInt(320)
            reply.writeString("com.xdja.containerservice"); reply.writeInt(1000)
            reply.writeString("FLAG_PRIVATE,FLAG_PRESENTATION")
        }

        val displays = clientWith(fake).listDisplays()

        assertEquals(2, displays?.size)
        val main = displays!![0]
        assertEquals(0, main.id)
        assertNull("an empty owner package must not become an empty string", main.ownerPkg)
        assertEquals(-1, main.ownerUid)
        assertEquals(emptyList<String>(), main.flags)
        val cluster = displays[1]
        assertEquals(1, cluster.id)
        assertEquals("fission_bg_xdjaVirtualSurface", cluster.name)
        assertEquals(1920, cluster.width)
        assertEquals(720, cluster.height)
        assertEquals(320, cluster.densityDpi)
        assertEquals("com.xdja.containerservice", cluster.ownerPkg)
        assertEquals(1000, cluster.ownerUid)
        assertEquals(listOf("FLAG_PRIVATE", "FLAG_PRESENTATION"), cluster.flags)
    }

    @Test
    fun `a failure status is null, not an empty inventory`() = runBlocking {
        val fake = replyingFake { reply -> reply.writeInt(-1); reply.writeInt(0) }
        assertNull(clientWith(fake).listDisplays())
    }

    @Test
    fun `a reply truncated mid-inventory is null, not a partial inventory`() = runBlocking {
        // A display missing from a partial read would be read as "this car has no cluster".
        val fake = replyingFake { reply ->
            reply.writeInt(0); reply.writeInt(2)
            reply.writeInt(0); reply.writeString("Built-in Screen")
            reply.writeInt(1920); reply.writeInt(1080); reply.writeInt(240)
            reply.writeString(""); reply.writeInt(-1); reply.writeString("")
        }
        assertNull(clientWith(fake).listDisplays())
    }

    @Test
    fun `an old daemon that rejects the transaction answers null`() = runBlocking {
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        assertNull(clientWith(fake).listDisplays())
    }
}
