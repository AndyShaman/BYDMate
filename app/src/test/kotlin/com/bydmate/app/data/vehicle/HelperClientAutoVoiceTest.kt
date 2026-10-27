package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** TX_SET_AUTO_VOICE on the app side (#353): an old daemon is UNSUPPORTED, a broken one is no answer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientAutoVoiceTest {

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

    private fun clientOver(binder: IBinder) = object : HelperClientImpl() { override fun resolveBinder(): IBinder = binder }

    @Test fun `daemon that does not handle the code answers unsupported`() = runBlocking {
        var seenCode = -1
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                seenCode = code
                return false
            }
        }

        val r = clientOver(fake).setAutoVoiceActive(true)

        assertEquals(HelperBinderProtocol.TX_SET_AUTO_VOICE, seenCode)
        assertNotNull(r)
        assertEquals(HelperBinderProtocol.AUTO_VOICE_UNSUPPORTED, r!!.outcome)
    }

    @Test fun `daemon that throws is no answer, not unsupported`() = runBlocking {
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = error("boom")
        }

        assertNull(clientOver(fake).setAutoVoiceActive(true))
    }

    @Test fun `daemon that handles the code returns its reply`() = runBlocking {
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeInt(HelperBinderProtocol.AUTO_VOICE_APPLIED)
                reply.writeString("ok")
                reply.setDataPosition(0)
                return true
            }
        }

        val r = clientOver(fake).setAutoVoiceActive(false)

        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, r!!.outcome)
        assertEquals("ok", r.detail)
    }
}
