package com.bydmate.app.data.vehicle

import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import com.bydmate.app.helper.HelperBinderProtocol
import com.bydmate.app.helper.offreport.OffReportOutcome
import com.bydmate.app.helper.offreport.OffReportState
import com.bydmate.app.helper.offreport.OffReportStatus
import com.bydmate.app.helper.offreport.readOffReportArm
import com.bydmate.app.helper.offreport.writeOffReportStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HelperClientOffReportTest {

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

    @Test fun `arm carries id, token, chat and text and reads status 0 as armed`() = runBlocking {
        var code = -1
        var id: String? = null
        var token: String? = null
        val fake = object : FakeIBinder() {
            override fun transact(c: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                code = c
                data.setDataPosition(0)
                data.enforceInterface(HelperBinderProtocol.DESCRIPTOR)
                val request = readOffReportArm(data)!!
                id = request.id
                token = request.token
                reply!!.writeInt(0)
                reply.setDataPosition(0)
                return true
            }
        }
        assertTrue(clientWith(fake).offReportArm("a1", "tok", 42L, "text"))
        assertEquals(HelperBinderProtocol.TX_OFFREPORT_ARM, code)
        assertEquals("a1", id)
        assertEquals("tok", token)
    }

    @Test fun `status is read back whole`() = runBlocking {
        val expected = OffReportStatus(
            OffReportOutcome("a1", OffReportState.SENT, 10L, 900L, 2, "200"), "", 0L, 2,
            OffReportOutcome("a1", OffReportState.SENT, 10L, 900L, 2, "200"),
        )
        val fake = object : FakeIBinder() {
            override fun transact(c: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
                reply!!.writeInt(0)
                writeOffReportStatus(reply, expected)
                reply.setDataPosition(0)
                return true
            }
        }
        assertEquals(expected, clientWith(fake).offReportStatus("a1"))
    }

    @Test fun `an old daemon that does not know the verbs gives false and null`() = runBlocking {
        val old = object : FakeIBinder() {
            override fun transact(c: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
        }
        val client = clientWith(old)
        assertFalse(client.offReportArm("a1", "tok", 42L, "text"))
        assertFalse(client.offReportDisarm())
        assertNull(client.offReportStatus("a1"))
    }
}
