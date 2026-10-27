package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.split.fakeSplitSharedPrefs
import com.bydmate.app.voice.AutoVoiceDuck
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private fun appliedReply(reply: Parcel?) {
        reply!!.writeInt(HelperBinderProtocol.AUTO_VOICE_APPLIED)
        reply.writeString("ok")
        reply.setDataPosition(0)
    }

    // #353: only the wait for the channel is bounded, the daemon's answer is never dropped.

    @Test fun `a reply slower than the old 2s budget is still delivered as APPLIED`() = runBlocking {
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                Thread.sleep(3_000)
                appliedReply(reply)
                return true
            }
        }

        val r = clientOver(fake).setAutoVoiceActive(true)

        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, r?.outcome)
    }

    @Test fun `a call queued behind another transact still gets its answer`() = runBlocking {
        val n = AtomicInteger()
        val inFirst = CountDownLatch(1)
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (n.getAndIncrement() == 0) { inFirst.countDown(); Thread.sleep(1_800) } else Thread.sleep(500)
                appliedReply(reply)
                return true
            }
        }
        val client = clientOver(fake)

        val first = async(Dispatchers.IO) { client.setAutoVoiceActive(true) }
        assertTrue(inFirst.await(2, TimeUnit.SECONDS))
        val second = client.setAutoVoiceActive(false)

        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, first.await()?.outcome)
        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, second?.outcome)
        assertEquals(2, n.get())
    }

    @Test fun `a call queued past the lock wait gives up unsent and the retry lands`() = runBlocking {
        val n = AtomicInteger()
        val inFirst = CountDownLatch(1)
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                if (n.getAndIncrement() == 0) { inFirst.countDown(); Thread.sleep(6_000) }
                appliedReply(reply)
                return true
            }
        }
        val client = clientOver(fake)

        val first = async(Dispatchers.IO) { client.setAutoVoiceActive(true) }
        assertTrue(inFirst.await(2, TimeUnit.SECONDS))
        val started = System.currentTimeMillis()
        val gaveUp = client.setAutoVoiceActive(false)
        val waited = System.currentTimeMillis() - started

        assertNull(gaveUp)
        assertTrue("waited $waited ms", waited in 4_500..5_900)
        assertEquals(1, n.get()) // the queued call never reached the daemon
        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, first.await()?.outcome)

        assertEquals(HelperBinderProtocol.AUTO_VOICE_APPLIED, client.setAutoVoiceActive(false)?.outcome)
    }

    @Test fun `dead daemon fails fast`() = runBlocking {
        val n = AtomicInteger()
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                n.incrementAndGet()
                throw android.os.DeadObjectException()
            }
        }
        val started = System.currentTimeMillis()

        val r = clientOver(fake).setAutoVoiceActive(true)

        assertNull(r)
        assertTrue(System.currentTimeMillis() - started < 1_000)
        assertEquals(2, n.get()) // one retry on a stale binder
    }

    @Test fun `dead daemon through AutoVoiceDuck - marker kept and the off call is retried`() = runBlocking {
        val n = AtomicInteger()
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                n.incrementAndGet()
                throw android.os.DeadObjectException()
            }
        }
        val client = clientOver(fake)
        val backing = mutableMapOf<String, Any?>()
        val d = AutoVoiceDuck(client::setAutoVoiceActive, fakeSplitSharedPrefs(backing), offRetryDelaysMs = listOf(50L, 50L))

        val lease = d.activate({ null }, {})
        d.deactivate(lease)
        val deadline = System.currentTimeMillis() + 3_000
        while (n.get() < 6 && System.currentTimeMillis() < deadline) delay(20) // on + off + retry, 2 tries each

        assertTrue("transacts: ${n.get()}", n.get() >= 6)
        assertEquals(true, backing[AutoVoiceDuck.KEY_MARKER])
    }
}
