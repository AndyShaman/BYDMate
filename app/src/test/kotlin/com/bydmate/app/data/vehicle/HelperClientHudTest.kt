package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.helper.readHudSdkInvocation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** HUD wave 1 transactions: the SDK navigation status call and setBuffer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientHudTest {

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

    @Test fun `hudNaviStatus marshals the status and reads outcome and SDK return`() = runBlocking {
        var seenCode = -1
        var seenStatus = -99
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                seenCode = code
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                seenStatus = data.readInt()
                reply!!.writeInt(HelperBinderProtocol.HUD_NAVI_CALLED); reply.writeInt(0)
                reply.setDataPosition(0)
                return true
            }
        }
        val reply = clientWith(fake).hudNaviStatus(2)
        assertEquals(HelperBinderProtocol.TX_HUD_NAVI_STATUS, seenCode)
        assertEquals(2, seenStatus)
        assertEquals(HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, 0), reply)
        assertTrue(reply!!.accepted)
    }

    @Test fun `SDK refusal and absence are not accepted`() {
        assertFalse(HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, -10011).accepted)
        assertFalse(HudNaviReply(HelperBinderProtocol.HUD_NAVI_ABSENT, 0).accepted)
        assertFalse(HudNaviReply(HelperBinderProtocol.HUD_NAVI_THREW, 0).accepted)
    }

    @Test fun `outdated daemon without the transaction answers null`() = runBlocking {
        // An old daemon falls through to Binder.onTransact, which returns false for unknown codes.
        val stale = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        assertNull(clientWith(stale).hudNaviStatus(2))
        assertNull(clientWith(stale).writeBufferStatus(1007, 1140461576, byteArrayOf(1)))
    }

    @Test fun `hudSdk marshals each call so the daemon reads the same method and arguments`() = runBlocking {
        val seen = mutableListOf<String>()
        var seenCode = -1
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                seenCode = code
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                val call = readHudSdkInvocation(data)
                seen += "${call?.method}(${call?.args?.joinToString(",")})"
                reply!!.writeInt(HelperBinderProtocol.HUD_NAVI_CALLED); reply.writeInt(-5)
                reply.setDataPosition(0)
                return true
            }
        }
        val client = clientWith(fake)
        assertEquals(HudNaviReply(HelperBinderProtocol.HUD_NAVI_CALLED, -5), client.hudSdk(HudSdkCall.Guidance(2, 300)))
        assertEquals(HelperBinderProtocol.TX_HUD_SDK, seenCode)
        client.hudSdk(HudSdkCall.PathName("Main St"))
        client.hudSdk(HudSdkCall.RestRoute(1, 5, 4_294_967_294L))
        assertEquals(
            listOf("sendSimpleGuidanceInfo(2,300)", "sendNextPathName(Main St)", "sendRestRouteInfo(1,5,4294967294)"),
            seen,
        )
    }

    @Test fun `an unknown SDK method selector reads as nothing to call`() {
        val p = Parcel.obtain()
        try {
            p.writeInt(99)
            p.setDataPosition(0)
            assertNull(readHudSdkInvocation(p))
        } finally {
            p.recycle()
        }
    }

    @Test fun `outdated daemon without the SDK transaction answers null`() = runBlocking {
        val stale = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        assertNull(clientWith(stale).hudSdk(HudSdkCall.Guidance(2, 300)))
    }

    @Test fun `writeBufferStatus marshals dev fid and bytes and returns the raw status`() = runBlocking {
        var seenCode = -1
        var seenDev = -1
        var seenFid = -1
        var seenBytes: ByteArray? = null
        val fake = object : FakeIBinder() {
            override fun transact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                seenCode = code
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                seenDev = data.readInt()
                seenFid = data.readInt()
                seenBytes = data.createByteArray()
                reply!!.writeInt(-10011); reply.writeInt(0)
                reply.setDataPosition(0)
                return true
            }
        }
        val road = "BYDMATE 3".toByteArray(Charsets.UTF_16LE)
        val status = clientWith(fake).writeBufferStatus(1007, 1140461576, road)
        assertEquals(HelperBinderProtocol.TX_WRITE_BUFFER, seenCode)
        assertEquals(1007, seenDev)
        assertEquals(1140461576, seenFid)
        assertArrayEquals(road, seenBytes)
        assertEquals(-10011, status)
    }
}
